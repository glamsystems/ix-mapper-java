package systems.glam.ix.proxy;

/// One position of the source instruction's account list, with the flags the source IDL
/// declares for it.
///
/// @param dynamicSigner the signer privilege is the caller's choice, not the IDL's
/// @param optional      how an absent optional reaches the program, or null when the position
///                      is required
/// @param expect        the account a mapper must find here, or null when any account may
///                      stand there
public record SourceAccount(String name,
                            boolean writable,
                            boolean signer,
                            boolean dynamicSigner,
                            OptionalKind optional,
                            Expectation expect) {

  public SourceAccount {
    Records.requireName(name, "SourceAccount", "name");
  }
}
