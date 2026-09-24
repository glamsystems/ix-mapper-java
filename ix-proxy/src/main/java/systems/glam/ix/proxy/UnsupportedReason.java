package systems.glam.ix.proxy;

/// Why a mapper refused an instruction; the result's message says which account or rule.
public enum UnsupportedReason {

  /// The program has a document, but no entry matches the instruction data.
  UNKNOWN_INSTRUCTION("unknown_instruction"),
  /// The document refuses the instruction; the message carries its reason.
  REFUSED_INSTRUCTION("refused_instruction"),
  /// The instruction leaves out an account the document needs.
  ACCOUNT_COUNT("account_count"),
  /// An account is not the one the document expects at its position.
  ACCOUNT_EXPECTATION("account_expectation"),
  /// A forwarded account's signer privilege disagrees with the seat it fills.
  ACCOUNT_PRIVILEGE("account_privilege"),
  /// The instruction carries accounts beyond the list and the document forbids them.
  REMAINING_ACCOUNTS("remaining_accounts"),
  /// The context supplies no address for a GLAM account the document seats.
  CONTEXT("context"),
  /// The instruction itself cannot be read: its data span lies outside its buffer, or an
  /// account is unresolved (a lookup-table account the transaction did not load).
  UNREADABLE_INSTRUCTION("unreadable_instruction");

  private final String jsonName;

  UnsupportedReason(final String jsonName) {
    this.jsonName = jsonName;
  }

  /// The reason's name in the results' vocabulary.
  public String jsonName() {
    return jsonName;
  }
}
