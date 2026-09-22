package systems.glam.ix.proxy;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;
import systems.comodal.jsoniter.JsonIterator;

import java.util.HashMap;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/// A source instruction may arrive with fewer accounts than the index map covers: under the
/// omitted optional-account strategy a client leaves an absent trailing optional out
/// entirely (the Stake program's lockup authority). The proxy maps what arrives when every
/// missing position is one the map drops, and refuses by message when it would have to seat
/// an account it was not given.
final class OmittedTrailingAccountsTests {

  private static final PublicKey CPI_PROGRAM = key(30);
  private static final AccountMeta READ_CPI_PROGRAM = AccountMeta.createRead(CPI_PROGRAM);
  private static final AccountMeta INVOKED_PROXY = AccountMeta.createInvoked(key(31));
  private static final AccountMeta FEE_PAYER = AccountMeta.createFeePayer(key(32));
  private static final PublicKey STATIC_BEFORE = key(33);
  private static final PublicKey STATIC_AFTER = key(34);
  private static final Function<DynamicAccountConfig, DynamicAccount<Void>> FACTORY =
      DynamicAccountConfig::createFeePayerAccount;

  private static PublicKey key(final int marker) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) marker;
    return PublicKey.createPubKey(bytes);
  }

  /// Proxy layout: 0 fee payer, 1 static, 2 the source's first account, 3 static. The source
  /// declares three accounts, the last two dropped (an authority the payer stands in for and
  /// a trailing optional), like a Stake withdraw through GLAM.
  private static IxProxy<Void> proxy() {
    final var config = IxMapConfig.parseConfig(new HashMap<>(), new HashMap<>(), JsonIterator.parse("""
        {
          "src_ix_name": "withdraw",
          "src_discriminator": [4, 0, 0, 0],
          "dst_ix_name": "stake_withdraw",
          "dst_discriminator": [1, 2, 3, 4, 5, 6, 7, 8],
          "dynamic_accounts": [ { "name": "glam_signer", "index": 0, "writable": true, "signer": true } ],
          "static_accounts": [
            { "account": "%s", "index": 1, "writable": false, "signer": false },
            { "account": "%s", "index": 3, "writable": false, "signer": false }
          ],
          "index_map": [2, -1, -1]
        }
        """.formatted(STATIC_BEFORE.toBase58(), STATIC_AFTER.toBase58())));
    return config.createProxy(INVOKED_PROXY, FACTORY);
  }

  private static Instruction source(final int numAccounts) {
    final var accounts = new AccountMeta[numAccounts];
    for (int i = 0; i < numAccounts; ++i) {
      accounts[i] = AccountMeta.createWrite(key(40 + i));
    }
    return Instruction.createInstruction(CPI_PROGRAM, List.of(accounts), new byte[]{4, 0, 0, 0, 9});
  }

  private static List<PublicKey> keys(final Instruction mapped) {
    return mapped.accounts().stream().map(AccountMeta::publicKey).toList();
  }

  @Test
  void aSourceWithEveryDeclaredAccountMapsAsBefore() {
    final var mapped = proxy().mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, source(3));
    assertEquals(List.of(FEE_PAYER.publicKey(), STATIC_BEFORE, key(40), STATIC_AFTER), keys(mapped));
  }

  @Test
  void aSourceThatLeavesTrailingDroppedAccountsOutMapsTheSame() {
    for (final int supplied : new int[]{2, 1}) {
      final var mapped = proxy().mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, source(supplied));
      assertEquals(List.of(FEE_PAYER.publicKey(), STATIC_BEFORE, key(40), STATIC_AFTER), keys(mapped), supplied + " accounts");
      assertEquals(4, mapped.accounts().size(), supplied + " accounts");
    }
  }

  @Test
  void aSourceWithAccountsBeyondTheMapPassesThemThroughAsRemaining() {
    final var mapped = proxy().mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, source(4));
    assertEquals(List.of(FEE_PAYER.publicKey(), STATIC_BEFORE, key(40), STATIC_AFTER, key(43)), keys(mapped));
  }

  @Test
  void aSourceMissingAnAccountTheProxySeatsIsRefusedByMessage() {
    final var refused = assertThrows(IllegalStateException.class,
        () -> proxy().mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, source(0)));
    assertEquals("Instruction supplies 0 accounts, but the proxy seats account 0 at index 2.", refused.getMessage());
  }
  /// Index zero is a seat like any other: a source that leaves out an account the proxy maps
  /// there is refused, not treated as having nothing to seat.
  @Test
  void aRequiredAccountSeatedAtIndexZeroIsNotOmittable() {
    final var config = IxMapConfig.parseConfig(new HashMap<>(), new HashMap<>(), JsonIterator.parse("""
        {
          "src_ix_name": "pass",
          "src_discriminator": [4, 0, 0, 0],
          "dst_ix_name": "proxy_pass",
          "dst_discriminator": [8, 7, 6, 5, 4, 3, 2, 1],
          "dynamic_accounts": [ { "name": "glam_signer", "index": 1, "writable": true, "signer": true } ],
          "static_accounts": [],
          "index_map": [0]
        }
        """));
    final var proxy = config.createProxy(INVOKED_PROXY, FACTORY);
    final var mapped = proxy.mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, source(1));
    assertEquals(List.of(key(40), FEE_PAYER.publicKey()), keys(mapped));
    final var refused = assertThrows(IllegalStateException.class,
        () -> proxy.mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, source(0)));
    assertEquals("Instruction supplies 0 accounts, but the proxy seats account 0 at index 0.", refused.getMessage());
  }
}
