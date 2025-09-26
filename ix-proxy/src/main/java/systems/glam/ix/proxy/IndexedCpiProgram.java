package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;

public record IndexedCpiProgram<A>(int index) implements DynamicAccount<A> {

  @Override
  public void setAccount(final AccountMeta[] mappedAccounts,
                         final PublicKey proxyProgram,
                         final AccountMeta cpiProgram,
                         final AccountMeta feePayer,
                         final A runtimeAccounts) {
    mappedAccounts[index] = cpiProgram;
  }
}
