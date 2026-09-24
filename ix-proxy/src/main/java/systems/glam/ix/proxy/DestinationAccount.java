package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;

/// One seat of the mapped instruction, with the flags the handler declares for it: a GLAM
/// account by name, a fixed address, or a source position forwarded.
public sealed interface DestinationAccount
    permits DestinationAccount.Dynamic, DestinationAccount.Static, DestinationAccount.Source {

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
}
