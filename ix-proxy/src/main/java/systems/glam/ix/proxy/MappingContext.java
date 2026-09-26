package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;

import java.util.List;
import java.util.function.Function;

/// What a mapping needs from the caller: the GLAM accounts a document seats dynamically, and
/// the accounts an entry lists as supplied. The mapper derives no address.
///
/// @param integrationAuthority the integration authority of a proxy program, for a document
///                             that seats one; null when the caller supplies none. A null
///                             result, or a lookup that throws an exception, refuses the
///                             instruction with reason [UnsupportedReason#CONTEXT] rather
///                             than escaping (an `Error` propagates).
/// @param suppliedAccounts     the accounts a document lists as supplied, for an entry that
///                             lists any: called once per such instruction with the roles and
///                             the addresses at their `of` positions, answering in the same
///                             order, the required ones first, a trailing run of optional ones
///                             left out at will; null when the caller supplies none. A null
///                             answer, or a supplier that throws an exception, refuses the
///                             instruction with reason [UnsupportedReason#CONTEXT]; a wrong
///                             count or a null element with [UnsupportedReason#SUPPLIED_ACCOUNTS];
///                             neither escapes (an `Error` propagates).
public record MappingContext(PublicKey glamState,
                             PublicKey glamVault,
                             PublicKey glamSigner,
                             Function<PublicKey, PublicKey> integrationAuthority,
                             Function<SuppliedAccountsRequest, List<PublicKey>> suppliedAccounts) {

  public MappingContext(final PublicKey glamState, final PublicKey glamVault, final PublicKey glamSigner) {
    this(glamState, glamVault, glamSigner, null, null);
  }

  public MappingContext(final PublicKey glamState,
                        final PublicKey glamVault,
                        final PublicKey glamSigner,
                        final Function<PublicKey, PublicKey> integrationAuthority) {
    this(glamState, glamVault, glamSigner, integrationAuthority, null);
  }
}
