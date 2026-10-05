package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;

/// One seat of the mapped instruction, with the flags the handler declares for it: a GLAM
/// account by name, a fixed address, a source position forwarded, or an account the context
/// supplies by its role.
public sealed interface DestinationAccount
    permits DestinationAccount.Dynamic, DestinationAccount.Static, DestinationAccount.Source,
    DestinationAccount.Supplied {

  int index();

  boolean writable();

  boolean signer();

  record Dynamic(int index,
                 DynamicAccountName name,
                 boolean writable,
                 boolean signer) implements DestinationAccount {

    public Dynamic {
      Records.requireIndex(index, "DestinationAccount.Dynamic", "index");
      Records.require(name, "DestinationAccount.Dynamic", "name");
    }
  }

  record Static(int index,
                PublicKey address,
                boolean writable,
                boolean signer) implements DestinationAccount {

    public Static {
      Records.requireIndex(index, "DestinationAccount.Static", "index");
      Records.require(address, "DestinationAccount.Static", "address");
    }
  }

  /// @param source   the position of the source account list this seat forwards
  /// @param sentinel an optional account of the handler's own: absence reaches it as the
  ///                 proxy program's id, so the source program's id found at the position
  ///                 is rewritten to the proxy program
  record Source(int index,
                int source,
                boolean writable,
                boolean signer,
                boolean sentinel) implements DestinationAccount {

    public Source {
      Records.requireIndex(index, "DestinationAccount.Source", "index");
      Records.requireIndex(source, "DestinationAccount.Source", "source");
    }
  }

  /// A declared account a native instruction never carries, which the context supplies at
  /// this account index: the mapper asks the supplier for it by its role and places the answer
  /// here with the handler's writable flag, unsigned. A mapper refuses a document in which one
  /// signs.
  ///
  /// @param role       what the account is, in the caller's vocabulary; the mapper hands it to
  ///                   the supplier uninterpreted
  /// @param derivation how the handler's IDL derives the account, or null when it states
  ///                   nothing
  record Supplied(int index,
                  String role,
                  boolean writable,
                  boolean signer,
                  Derivation derivation) implements DestinationAccount {

    public Supplied {
      Records.requireIndex(index, "DestinationAccount.Supplied", "index");
      Records.requireName(role, "DestinationAccount.Supplied", "role");
    }
  }
}
