package systems.glam.ix.proxy;

/// What a mapped entry does with accounts beyond its listed positions.
public enum RemainingAccounts {

  /// Forwards them after the seats, with the flags the caller gave them.
  ANY("any"),
  /// Refuses the instruction.
  NONE("none");

  private final String jsonName;

  RemainingAccounts(final String jsonName) {
    this.jsonName = jsonName;
  }

  public String jsonName() {
    return jsonName;
  }

  public static RemainingAccounts fromJsonName(final String name) {
    for (final var value : values()) {
      if (value.jsonName.equals(name)) {
        return value;
      }
    }
    return null;
  }
}
