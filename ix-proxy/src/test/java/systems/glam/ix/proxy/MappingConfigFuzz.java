package systems.glam.ix.proxy;

/// Jazzer entry point for the mapping-document parser, the path every consumer drives at
/// startup over the generated documents (external input: written into the tracked
/// `ix-mapper-ts/` directory by another repository's workflow, parsed once ahead of the
/// account-remapping hot path).
///
/// The fuzz payload is arbitrary bytes parsed as a document, exactly as
/// [MappingDocuments#read] parses a file's bytes, then a mapper is built over the result.
/// Malformed-input contract: garbage in -> [MappingDocumentException] out, whether the bytes
/// are not JSON or the document does not admit (a bad document is a startup failure, not a
/// hang). Jazzer flags what the contract forbids: any other throwable, hangs (deeply nested
/// JSON, huge literals) and memory exhaustion.
///
/// Seeded from real documents under src/test/resources/fuzz/mappingConfig; the nested
/// entry/seat structure is unreachable from scratch, so a mutator only makes progress from a
/// real seed.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :ix-proxy:fuzzMappingConfig [-PmaxFuzzTime=<seconds>]`.
public final class MappingConfigFuzz {

  public static void fuzzerTestOneInput(final byte[] data) {
    final MappingDocument document;
    try {
      document = MappingDocumentParser.parse(data, "fuzz");
    } catch (final MappingDocumentException tolerated) {
      // text that is not JSON, or a document that does not admit: refusal is the contract,
      // and any other exception is a finding
      return;
    }
    // touch the parsed structure the way the mapper does, so a document that parses into a
    // nonsense shape surfaces here rather than at first use on the remapping path
    final var mapper = InstructionMapper.createMapper(java.util.List.of(document));
    if (mapper.documentOf(document.programId()) != document) {
      throw new AssertionError("the mapper does not hold the document it was built over");
    }
    for (final var entry : document.instructions()) {
      if (entry.discriminator().length() == 0) {
        throw new AssertionError("an admitted entry has no discriminator");
      }
      if (entry instanceof InstructionEntry.Mapped mapped) {
        final int seats = mapped.destinationAccounts().size();
        for (final var seat : mapped.destinationAccounts()) {
          if (seat.index() < 0 || seat.index() >= seats) {
            throw new AssertionError("an admitted seat index is out of range");
          }
        }
      }
    }
  }
}
