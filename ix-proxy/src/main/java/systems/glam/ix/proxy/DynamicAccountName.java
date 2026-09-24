package systems.glam.ix.proxy;

/// The GLAM accounts a document may seat, by the names the document uses; the
/// [MappingContext] supplies their addresses.
public enum DynamicAccountName {

  GLAM_STATE("glam_state"),
  GLAM_VAULT("glam_vault"),
  GLAM_SIGNER("glam_signer"),
  INTEGRATION_AUTHORITY("integration_authority");

  private final String jsonName;

  DynamicAccountName(final String jsonName) {
    this.jsonName = jsonName;
  }

  public String jsonName() {
    return jsonName;
  }

  /// The name as the document spells it, or null for a name no document may use.
  public static DynamicAccountName fromJsonName(final String name) {
    for (final var value : values()) {
      if (value.jsonName.equals(name)) {
        return value;
      }
    }
    return null;
  }
}
