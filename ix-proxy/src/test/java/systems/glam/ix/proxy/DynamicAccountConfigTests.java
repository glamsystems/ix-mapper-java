package systems.glam.ix.proxy;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import systems.comodal.jsoniter.JsonIterator;

import static org.junit.jupiter.api.Assertions.*;

/// Field-level parse assertions for [DynamicAccountConfig] and the account
/// factories it exposes.
final class DynamicAccountConfigTests {

  private static PublicKey key(final int marker) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) marker;
    return PublicKey.createPubKey(bytes);
  }

  private static DynamicAccountConfig parse(final String json) {
    return DynamicAccountConfig.parseConfig(JsonIterator.parse(json));
  }

  @Test
  void parsesEveryField() {
    final var config = parse("""
        {"name": "glam_state", "index": 3, "writable": true, "signer": false}""");
    assertEquals("glam_state", config.name());
    assertEquals(3, config.index());
    assertTrue(config.writable());
    assertFalse(config.signer());

    final var flipped = parse("""
        {"name": "glam_signer", "index": 1, "writable": false, "signer": true}""");
    assertEquals("glam_signer", flipped.name());
    assertEquals(1, flipped.index());
    assertFalse(flipped.writable());
    assertTrue(flipped.signer());
  }

  @Test
  void unknownFieldsThrow() {
    assertThrows(IllegalStateException.class, () -> parse("""
        {"name": "glam_state", "bogus": 1}"""));
  }

  @Test
  void createMetaAppliesTheParsedFlags() {
    final var accountKey = key(50);
    final var writable = parse("""
        {"name": "glam_vault", "index": 0, "writable": true, "signer": false}""").createMeta(accountKey);
    assertEquals(accountKey, writable.publicKey());
    assertTrue(writable.write());
    assertFalse(writable.signer());

    final var signer = parse("""
        {"name": "glam_signer", "index": 0, "writable": false, "signer": true}""").createMeta(accountKey);
    assertFalse(signer.write());
    assertTrue(signer.signer());
  }

  @Test
  void feePayerAccountWritesTheFeePayerAtTheParsedIndex() {
    final var config = parse("""
        {"name": "glam_signer", "index": 1, "writable": true, "signer": true}""");
    final var feePayer = AccountMeta.createFeePayer(key(51));
    final var accounts = new AccountMeta[3];
    config.<Void>createFeePayerAccount().setAccount(accounts, key(52), AccountMeta.createRead(key(53)), feePayer, null);
    assertNull(accounts[0]);
    assertSame(feePayer, accounts[1]);
    assertNull(accounts[2]);
  }

  @Test
  void readIntegrationAuthorityWritesTheCpiProgramAtTheParsedIndex() {
    final var config = parse("""
        {"name": "integration_authority", "index": 0, "writable": false, "signer": false}""");
    final var cpiProgram = AccountMeta.createRead(key(53));
    final var accounts = new AccountMeta[2];
    config.<Void>createReadIntegrationAuthority().setAccount(accounts, key(52), cpiProgram, AccountMeta.createFeePayer(key(51)), null);
    assertSame(cpiProgram, accounts[0]);
    assertNull(accounts[1]);
  }

  @Test
  void readCpiProgramWritesTheCpiProgramAtTheParsedIndex() {
    final var config = parse("""
        {"name": "cpi_program", "index": 2, "writable": false, "signer": false}""");
    final var cpiProgram = AccountMeta.createRead(key(53));
    final var accounts = new AccountMeta[3];
    config.<Void>createReadCpiProgram().setAccount(accounts, key(52), cpiProgram, AccountMeta.createFeePayer(key(51)), null);
    assertNull(accounts[0]);
    assertNull(accounts[1]);
    assertSame(cpiProgram, accounts[2]);
  }
}
