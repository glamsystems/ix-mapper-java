package systems.glam.ix.proxy;

/// A mapping document that does not admit ([MappingDocumentParser]), including text that is
/// not JSON, or a document set that forms no mapper: none, two environments, two documents
/// for one program, a record of the model built with a missing or invalid field (its
/// constructor), or a document built from records whose entries fail the checks across them
/// ([InstructionMapper#createMapper]). Mapping returns a [MapResult];
/// [InstructionMapper#mapTransaction] throws [UnsupportedInstructionException] for a refused
/// instruction and lets sava's own `IllegalStateException` or `IllegalArgumentException`
/// through for a transaction the mapped instructions cannot form, checked at each
/// replacement; reading files surfaces `UncheckedIOException`, and a path that is not a
/// directory `IllegalArgumentException` ([MappingDocuments]).
public final class MappingDocumentException extends RuntimeException {

  private final String at;
  private final String detail;

  /// @param at which document, or part of one, the message is about
  public MappingDocumentException(final String at, final String detail) {
    super(at + ": " + detail);
    this.at = at;
    this.detail = detail;
  }

  public String at() {
    return at;
  }

  /// The message without the `at` prefix: what is wrong.
  public String detail() {
    return detail;
  }
}
