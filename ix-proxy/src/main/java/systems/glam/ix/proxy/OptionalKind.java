package systems.glam.ix.proxy;

/// How an absent optional account reaches the program.
public enum OptionalKind {

  /// A client leaves it out of the account list; only a trailing run may be absent.
  OMITTED("omitted"),
  /// A client passes the source program's id in its place.
  PROGRAM_ID("program_id");

  private final String jsonName;

  OptionalKind(final String jsonName) {
    this.jsonName = jsonName;
  }

  public String jsonName() {
    return jsonName;
  }

  public static OptionalKind fromJsonName(final String name) {
    for (final var value : values()) {
      if (value.jsonName.equals(name)) {
        return value;
      }
    }
    return null;
  }
}
