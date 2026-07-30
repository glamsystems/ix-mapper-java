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

/// Direct parse-and-assert coverage of [IxMapConfig#parseConfig] plus the
/// [IxMapConfig#createProxy] validation branches and the proxy behaviours
/// they produce ([IdentityIxProxy], [PayerIxProxy], [IxProxyRecord]).
final class IxMapConfigTests {

  private static final PublicKey CPI_PROGRAM = key(20);
  private static final AccountMeta READ_CPI_PROGRAM = AccountMeta.createRead(CPI_PROGRAM);
  private static final AccountMeta INVOKED_PROXY = AccountMeta.createInvoked(key(21));
  private static final AccountMeta FEE_PAYER = AccountMeta.createFeePayer(key(22));

  private static final Function<DynamicAccountConfig, DynamicAccount<Void>> FACTORY =
      DynamicAccountConfig::createFeePayerAccount;

  private static PublicKey key(final int marker) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) marker;
    return PublicKey.createPubKey(bytes);
  }

  private static IxMapConfig parse(final String json) {
    return IxMapConfig.parseConfig(new HashMap<>(), new HashMap<>(), JsonIterator.parse(json));
  }

  @Test
  void parsesEveryField() {
    final var staticKey = key(23);
    final var config = parse("""
        {
          "type": "payer",
          "src_ix_name": "transfer",
          "src_discriminator": [1, 2],
          "dst_ix_name": "proxy_transfer",
          "dst_discriminator": [3, 4, 5],
          "dynamic_accounts": [{"name": "glam_signer", "index": 0, "writable": true, "signer": true}],
          "static_accounts": [{"account": "%s", "index": 1, "writable": false, "signer": false}],
          "index_map": [2, -1],
          "program_id_placeholder_indices": [0]
        }""".formatted(staticKey.toBase58()));

    assertEquals("PAYER", String.valueOf((Object) config.proxyType()));
    assertEquals("transfer", config.cpiIxName());
    assertArrayEquals(new byte[]{1, 2}, config.cpiDiscriminator().data());
    assertEquals("proxy_transfer", config.proxyIxName());
    assertArrayEquals(new byte[]{3, 4, 5}, config.proxyDiscriminator().data());

    assertEquals(1, config.dynamicAccounts().size());
    final var dynamicAccount = config.dynamicAccounts().getFirst();
    assertEquals("glam_signer", dynamicAccount.name());
    assertEquals(0, dynamicAccount.index());
    assertTrue(dynamicAccount.writable());
    assertTrue(dynamicAccount.signer());

    assertEquals(1, config.staticAccounts().size());
    final var staticAccount = config.staticAccounts().getFirst();
    assertEquals(staticKey, staticAccount.accountMeta().publicKey());
    assertEquals(1, staticAccount.index());

    assertArrayEquals(new int[]{2, -1}, config.indexMap());
  }

  @Test
  void absentFieldsDefaultToEmpty() {
    final var config = parse("""
        {"src_discriminator": [2, 0, 0, 0]}""");
    assertNull(config.proxyType());
    assertNull(config.cpiIxName());
    assertNull(config.proxyIxName());
    assertNull(config.proxyDiscriminator());
    assertTrue(config.dynamicAccounts().isEmpty());
    assertTrue(config.staticAccounts().isEmpty());
    assertEquals(0, config.indexMap().length);
  }

  @Test
  void emptyArraysParseToEmpty() {
    final var config = parse("""
        {"src_discriminator": [7], "dynamic_accounts": [], "static_accounts": [], "index_map": []}""");
    assertTrue(config.dynamicAccounts().isEmpty());
    assertTrue(config.staticAccounts().isEmpty());
    assertEquals(0, config.indexMap().length);
  }

  @Test
  void proxyTypeParsesCaseInsensitively() {
    assertEquals("PAYER", String.valueOf((Object) parse("""
        {"type": "payer", "src_discriminator": [7]}""").proxyType()));
    assertEquals("PAYER", String.valueOf((Object) parse("""
        {"type": "PAYER", "src_discriminator": [7]}""").proxyType()));
  }

  @Test
  void unknownProxyTypeThrows() {
    final var ex = assertThrows(IllegalStateException.class, () -> parse("""
        {"type": "delegate", "src_discriminator": [7]}"""));
    assertTrue(ex.getMessage().contains("delegate"));
  }

  @Test
  void unknownFieldsThrow() {
    assertThrows(IllegalStateException.class, () -> parse("""
        {"src_discriminator": [7], "bogus_field": 1}"""));
  }

  // ----- createProxy validation: no proxy discriminator -----

  @Test
  void staticAccountsRequireAProxyDiscriminator() {
    final var config = parse("""
        {"src_discriminator": [7], "static_accounts": [{"account": "%s", "index": 0, "writable": false, "signer": false}]}"""
        .formatted(key(23).toBase58()));
    assertThrows(IllegalStateException.class, () -> config.createProxy(INVOKED_PROXY, FACTORY));
  }

  @Test
  void anIndexMapWithoutAccountsRequiresAProxyDiscriminator() {
    final var config = parse("""
        {"src_discriminator": [7], "index_map": [0]}""");
    assertThrows(IllegalStateException.class, () -> config.createProxy(INVOKED_PROXY, FACTORY));
  }

  @Test
  void moreThanOneDynamicAccountRequiresAProxyDiscriminator() {
    final var config = parse("""
        {"src_discriminator": [7], "dynamic_accounts": [
          {"name": "glam_signer", "index": 0, "writable": true, "signer": true},
          {"name": "glam_signer", "index": 1, "writable": true, "signer": true}
        ], "index_map": [-1, 1]}""");
    assertThrows(IllegalStateException.class, () -> config.createProxy(INVOKED_PROXY, FACTORY));
  }

  @Test
  void identityProxyPassesTheInstructionThroughUnchanged() {
    final var config = parse("""
        {"src_discriminator": [7, 8]}""");
    final var proxy = config.createProxy(INVOKED_PROXY, FACTORY);

    final var ix = Instruction.createInstruction(CPI_PROGRAM, List.of(), new byte[]{7, 8, 42});
    assertSame(ix, proxy.mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, ix));
    assertSame(config.cpiDiscriminator(), proxy.cpiDiscriminator());
    assertSame(proxy.cpiDiscriminator(), proxy.proxyDiscriminator());
  }

  @Test
  void identityProxyValidatesProgramAndDiscriminator() {
    final var proxy = parse("""
        {"src_discriminator": [7, 8]}""").createProxy(INVOKED_PROXY, FACTORY);

    final var wrongProgram = Instruction.createInstruction(key(29), List.of(), new byte[]{7, 8});
    assertThrows(IllegalStateException.class, () -> proxy.mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, wrongProgram));

    final var wrongDiscriminator = Instruction.createInstruction(CPI_PROGRAM, List.of(), new byte[]{7, 9});
    assertThrows(IllegalStateException.class, () -> proxy.mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, wrongDiscriminator));
  }

  @Test
  void matchesCpiDiscriminatorComparesThePrefixWithinBounds() {
    final var proxy = parse("""
        {"src_discriminator": [7, 8]}""").createProxy(INVOKED_PROXY, FACTORY);

    assertTrue(proxy.matchesCpiDiscriminator(new byte[]{7, 8, 42}, 0, 3));
    assertTrue(proxy.matchesCpiDiscriminator(new byte[]{0, 7, 8}, 1, 2));
    assertFalse(proxy.matchesCpiDiscriminator(new byte[]{7, 9, 42}, 0, 3));
    // a data window shorter than the discriminator can never match
    assertFalse(proxy.matchesCpiDiscriminator(new byte[]{7, 8}, 0, 1));
  }

  // ----- createProxy validation: payer shape -----

  private static IxMapConfig payerConfig(final String dynamicAccount, final String indexMap) {
    // the explicit type exercises the declared-type consistency check
    return parse("""
        {"type": "payer", "src_discriminator": [7], "dynamic_accounts": [%s], "index_map": %s}"""
        .formatted(dynamicAccount, indexMap));
  }

  private static final String VALID_PAYER = "{\"name\": \"glam_signer\", \"index\": 0, \"writable\": true, \"signer\": true}";

  @Test
  void payerAccountMustBeWritableAndSigner() {
    assertThrows(IllegalStateException.class, () ->
        payerConfig("{\"name\": \"glam_signer\", \"index\": 0, \"writable\": false, \"signer\": true}", "[-1]")
            .createProxy(INVOKED_PROXY, FACTORY));
    assertThrows(IllegalStateException.class, () ->
        payerConfig("{\"name\": \"glam_signer\", \"index\": 0, \"writable\": true, \"signer\": false}", "[-1]")
            .createProxy(INVOKED_PROXY, FACTORY));
  }

  @Test
  void payerIndexMapMustRemoveExactlyOneAccount() {
    assertThrows(IllegalStateException.class, () ->
        payerConfig(VALID_PAYER, "[0, 1]").createProxy(INVOKED_PROXY, FACTORY));
    assertThrows(IllegalStateException.class, () ->
        payerConfig(VALID_PAYER, "[-1, -1]").createProxy(INVOKED_PROXY, FACTORY));
  }

  @Test
  void payerIndexMapMustRemoveThePayer() {
    assertThrows(IllegalStateException.class, () ->
        payerConfig(VALID_PAYER, "[0, -1]").createProxy(INVOKED_PROXY, FACTORY));
  }

  @Test
  void payerProxyReplacesThePayerAccount() {
    // a non-negative second entry pins the removed-account count to entries
    // that are strictly negative
    final var proxy = payerConfig(VALID_PAYER, "[-1, 0]").createProxy(INVOKED_PROXY, FACTORY);

    final var otherPayer = AccountMeta.createWritableSigner(key(30));
    final var secondAccount = AccountMeta.createWrite(key(31));
    final var ix = Instruction.createInstruction(CPI_PROGRAM, List.of(otherPayer, secondAccount), new byte[]{7, 42});

    final var mapped = proxy.mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, ix);
    assertNotSame(ix, mapped);
    assertSame(FEE_PAYER, mapped.accounts().getFirst());
    assertSame(secondAccount, mapped.accounts().get(1));
    assertEquals(CPI_PROGRAM, mapped.programId().publicKey());
    assertArrayEquals(new byte[]{7, 42}, mapped.data());
    assertSame(proxy.cpiDiscriminator(), proxy.proxyDiscriminator());
  }

  @Test
  void payerProxyKeepsAnInstructionAlreadyPayedByTheFeePayer() {
    // no "type" field: the payer shape must also be inferred without one
    final var proxy = parse("""
        {"src_discriminator": [7], "dynamic_accounts": [%s], "index_map": [-1]}"""
        .formatted(VALID_PAYER)).createProxy(INVOKED_PROXY, FACTORY);

    final var alreadyPayer = AccountMeta.createWritableSigner(FEE_PAYER.publicKey());
    final var ix = Instruction.createInstruction(CPI_PROGRAM, List.of(alreadyPayer), new byte[]{7});
    assertSame(ix, proxy.mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, ix));

    // matching key but missing either flag must still be replaced
    final var readOnlyPayer = AccountMeta.createRead(FEE_PAYER.publicKey());
    final var readOnlyIx = Instruction.createInstruction(CPI_PROGRAM, List.of(readOnlyPayer), new byte[]{7});
    assertSame(FEE_PAYER, proxy.mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, readOnlyIx).accounts().getFirst());

    final var signerNotWritable = AccountMeta.createReadOnlySigner(FEE_PAYER.publicKey());
    final var signerIx = Instruction.createInstruction(CPI_PROGRAM, List.of(signerNotWritable), new byte[]{7});
    assertSame(FEE_PAYER, proxy.mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, signerIx).accounts().getFirst());

    final var writableNotSigner = AccountMeta.createWrite(FEE_PAYER.publicKey());
    final var writableIx = Instruction.createInstruction(CPI_PROGRAM, List.of(writableNotSigner), new byte[]{7});
    assertSame(FEE_PAYER, proxy.mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, writableIx).accounts().getFirst());
  }

  @Test
  void payerProxyRequiresEnoughAccounts() {
    final var proxy = payerConfig(VALID_PAYER, "[-1]").createProxy(INVOKED_PROXY, FACTORY);
    final var ix = Instruction.createInstruction(CPI_PROGRAM, List.of(), new byte[]{7});
    final var ex = assertThrows(IllegalStateException.class, () ->
        proxy.mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, ix));
    // payer index 0 requires one account
    assertTrue(ex.getMessage().contains("at least 1 "));
  }

  // ----- createProxy validation: proxy discriminator shape -----

  private static final String STATIC_ACCOUNT_JSON =
      "{\"account\": \"%s\", \"index\": 1, \"writable\": false, \"signer\": false}";

  @Test
  void duplicateDynamicAccountIndexesThrow() {
    final var config = parse("""
        {"src_discriminator": [7], "dst_discriminator": [9], "dynamic_accounts": [
          {"name": "glam_signer", "index": 0, "writable": true, "signer": true},
          {"name": "glam_signer", "index": 0, "writable": true, "signer": true}
        ]}""");
    final var ex = assertThrows(IllegalStateException.class, () -> config.createProxy(INVOKED_PROXY, FACTORY));
    assertTrue(ex.getMessage().contains("dynamic accounts"));
  }

  @Test
  void duplicateStaticAccountIndexesThrow() {
    final var config = parse(("""
        {"src_discriminator": [7], "dst_discriminator": [9], "static_accounts": [%s, %s]}""")
        .formatted(STATIC_ACCOUNT_JSON.formatted(key(23).toBase58()), STATIC_ACCOUNT_JSON.formatted(key(24).toBase58())));
    final var ex = assertThrows(IllegalStateException.class, () -> config.createProxy(INVOKED_PROXY, FACTORY));
    assertTrue(ex.getMessage().contains("static accounts"));
  }

  @Test
  void duplicateIndexMapTargetsThrow() {
    final var config = parse("""
        {"src_discriminator": [7], "dst_discriminator": [9], "index_map": [0, 0]}""");
    final var ex = assertThrows(IllegalStateException.class, () -> config.createProxy(INVOKED_PROXY, FACTORY));
    assertTrue(ex.getMessage().contains("index map"));
  }

  @Test
  void proxiedMappingRewritesAccountsAndData() {
    final var staticKey = key(25);
    final var config = parse(("""
        {
          "src_ix_name": "transfer",
          "src_discriminator": [9],
          "dst_ix_name": "proxy_transfer",
          "dst_discriminator": [7, 8],
          "dynamic_accounts": [{"name": "glam_signer", "index": 0, "writable": true, "signer": true}],
          "static_accounts": [%s],
          "index_map": [2, -1]
        }""").formatted(STATIC_ACCOUNT_JSON.formatted(staticKey.toBase58())));
    final var proxy = config.createProxy(INVOKED_PROXY, FACTORY);

    final var sourcePayer = AccountMeta.createWritableSigner(key(30));
    final var movedAccount = AccountMeta.createWrite(key(31));
    final var extraAccount = AccountMeta.createRead(key(32));
    final var secondExtraAccount = AccountMeta.createRead(key(33));
    final var ix = Instruction.createInstruction(
        CPI_PROGRAM,
        List.of(movedAccount, sourcePayer, extraAccount, secondExtraAccount),
        new byte[]{9, 42, 43}
    );

    final var mapped = proxy.mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, ix);

    assertEquals(INVOKED_PROXY.publicKey(), mapped.programId().publicKey());
    final var accounts = mapped.accounts();
    assertEquals(5, accounts.size());
    assertSame(FEE_PAYER, accounts.get(0));
    assertEquals(staticKey, accounts.get(1).publicKey());
    assertSame(movedAccount, accounts.get(2));
    // two extras pin the copy loop's forward advance through both cursors
    assertSame(extraAccount, accounts.get(3));
    assertSame(secondExtraAccount, accounts.get(4));
    // proxy discriminator replaces the source discriminator, payload preserved
    assertArrayEquals(new byte[]{7, 8, 42, 43}, mapped.data());

    assertArrayEquals(new byte[]{9}, proxy.cpiDiscriminator().data());
    assertArrayEquals(new byte[]{7, 8}, proxy.proxyDiscriminator().data());
  }

  @Test
  void anIndexMapCanTargetTheFirstProxySlot() {
    final var config = parse("""
        {
          "src_discriminator": [9],
          "dst_discriminator": [7, 8],
          "dynamic_accounts": [{"name": "glam_signer", "index": 1, "writable": true, "signer": true}],
          "index_map": [0]
        }""");
    final var proxy = config.createProxy(INVOKED_PROXY, FACTORY);

    final var sourceAccount = AccountMeta.createWrite(key(34));
    final var ix = Instruction.createInstruction(CPI_PROGRAM, List.of(sourceAccount), new byte[]{9});
    final var mapped = proxy.mapInstruction(READ_CPI_PROGRAM, FEE_PAYER, null, ix);

    assertEquals(2, mapped.accounts().size());
    assertSame(sourceAccount, mapped.accounts().get(0));
    assertSame(FEE_PAYER, mapped.accounts().get(1));
  }
}
