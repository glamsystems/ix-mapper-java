package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;

import java.util.List;

/// The mapping document: one per source program and environment, listing every instruction
/// of the program with a disposition. The shape is the contract between the generator and
/// every mapper of the document; [MappingDocumentParser] admits a document into it.
///
/// @param provenance where the document came from, or null when it says nothing
public record MappingDocument(int schemaVersion,
                              String environment,
                              PublicKey programId,
                              PublicKey proxyProgramId,
                              Provenance provenance,
                              List<InstructionEntry> instructions) {

  public static final int SCHEMA_VERSION = 1;

  /// A document built from records takes the field checks a parsed one took, and holds its
  /// own copy of the entries; [InstructionMapper#createMapper] adds the checks across entries.
  public MappingDocument {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new MappingDocumentException("MappingDocument", "schema_version is " + schemaVersion + ", not " + SCHEMA_VERSION);
    }
    Records.requireName(environment, "MappingDocument", "environment");
    Records.require(programId, "MappingDocument", "program_id");
    Records.require(proxyProgramId, "MappingDocument", "proxy_program_id");
    instructions = Records.copy(instructions, "MappingDocument", "instructions");
  }

  /// The entry whose discriminator prefixes the data, or null when none does. The parser
  /// refuses a document in which one discriminator prefixes another, so at most one matches.
  public InstructionEntry entryFor(final byte[] data, final int offset, final int length) {
    for (final var entry : instructions) {
      final var discriminator = entry.discriminator();
      final int discriminatorLength = discriminator.length();
      if (discriminatorLength <= length
          && java.util.Arrays.equals(
          data, offset, offset + discriminatorLength,
          discriminator.data(), 0, discriminatorLength)) {
        return entry;
      }
    }
    return null;
  }
}
