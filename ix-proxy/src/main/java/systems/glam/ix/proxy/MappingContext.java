package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;

import java.util.function.Function;

/// What a mapping needs from the caller: the GLAM accounts a document seats dynamically. The
/// mapper derives no address.
///
/// @param integrationAuthority the integration authority of a proxy program, for a document
///                             that seats one; null when the caller supplies none. A null
///                             result, or a lookup that throws an exception, refuses the
///                             instruction with reason [UnsupportedReason#CONTEXT] rather
///                             than escaping (an `Error` propagates).
public record MappingContext(PublicKey glamState,
                             PublicKey glamVault,
                             PublicKey glamSigner,
                             Function<PublicKey, PublicKey> integrationAuthority) {

  public MappingContext(final PublicKey glamState, final PublicKey glamVault, final PublicKey glamSigner) {
    this(glamState, glamVault, glamSigner, null);
  }
}
