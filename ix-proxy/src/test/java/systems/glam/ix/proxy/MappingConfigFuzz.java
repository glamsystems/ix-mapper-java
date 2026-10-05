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
/// Seeded from real documents and hand-written ones under
/// src/test/resources/fuzz/mappingConfig; the nested entry/seat structure is unreachable from
/// scratch, so a mutator only makes progress from a seed. Each seed's outcome is pinned by
/// `MappingConfigFuzzSeedsTests`.
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
        for (final var destination : mapped.destinationAccounts()) {
          if (destination instanceof DestinationAccount.Supplied supplied) {
            checkSuppliedAtAnAccountIndex(mapped, supplied);
          }
        }
      }
    }
  }

  /// An admitted supplied account at an account index never signs, and its derivation is one
  /// the mapper's resolution reads without a check of its own: each account seed names an
  /// account index inside the entry that the context does not supply and a client may not
  /// leave out (the mapper indexes the accounts it placed), and no constant seed is longer
  /// than a seed may be.
  private static void checkSuppliedAtAnAccountIndex(final InstructionEntry.Mapped mapped, final DestinationAccount.Supplied supplied) {
    if (supplied.signer()) {
      throw new AssertionError("an admitted supplied account at an account index signs");
    }
    if (supplied.derivation() == null) {
      return;
    }
    final var destinations = mapped.destinationAccounts();
    for (final var seed : supplied.derivation().seeds()) {
      switch (seed) {
        case Derivation.Account account -> {
          final var named = destinations.stream().filter(destination -> destination.index() == account.index()).findFirst();
          if (named.isEmpty() || named.get() instanceof DestinationAccount.Supplied) {
            throw new AssertionError("an admitted derivation names account index " + account.index() + ", which the mapper does not fill");
          }
          if (named.get() instanceof DestinationAccount.Source forward
              && mapped.sourceAccounts().get(forward.source()).optional() == OptionalKind.OMITTED) {
            throw new AssertionError("an admitted derivation names account index " + account.index() + ", which a client may leave out");
          }
        }
        case Derivation.Const constant -> {
          if (constant.value().length > 32) {
            throw new AssertionError("an admitted derivation carries a constant seed of " + constant.value().length + " bytes, longer than a seed may be");
          }
        }
        case Derivation.Arg _ -> {
        }
      }
    }
  }
}
