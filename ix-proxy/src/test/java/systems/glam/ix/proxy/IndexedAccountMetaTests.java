package systems.glam.ix.proxy;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import systems.comodal.jsoniter.JsonIterator;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Field-level parse assertions for [IndexedAccountMeta] and the de-duplication
/// contract of its shared caches.
final class IndexedAccountMetaTests {

  private static PublicKey key(final int marker) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) marker;
    return PublicKey.createPubKey(bytes);
  }

  private static IndexedAccountMeta parse(final String json,
                                          final Map<AccountMeta, AccountMeta> accountMetaCache,
                                          final Map<IndexedAccountMeta, IndexedAccountMeta> indexedCache) {
    return IndexedAccountMeta.parseConfig(accountMetaCache, indexedCache, JsonIterator.parse(json));
  }

  @Test
  void parsesEveryField() {
    final var accountKey = key(60);
    final var indexed = parse("""
        {"account": "%s", "index": 4, "writable": true, "signer": false}""".formatted(accountKey.toBase58()),
        new HashMap<>(), new HashMap<>());
    assertEquals(accountKey, indexed.accountMeta().publicKey());
    assertEquals(4, indexed.index());
    assertTrue(indexed.accountMeta().write());
    assertFalse(indexed.accountMeta().signer());

    final var flipped = parse("""
        {"account": "%s", "index": 0, "writable": false, "signer": true}""".formatted(accountKey.toBase58()),
        new HashMap<>(), new HashMap<>());
    assertEquals(0, flipped.index());
    assertFalse(flipped.accountMeta().write());
    assertTrue(flipped.accountMeta().signer());
  }

  @Test
  void unknownFieldsThrow() {
    assertThrows(IllegalStateException.class, () -> parse("""
        {"account": "%s", "bogus": 1}""".formatted(key(60).toBase58()), new HashMap<>(), new HashMap<>()));
  }

  @Test
  void setAccountWritesTheMetaAtTheParsedIndex() {
    final var indexed = parse("""
        {"account": "%s", "index": 1, "writable": false, "signer": false}""".formatted(key(60).toBase58()),
        new HashMap<>(), new HashMap<>());
    final var accounts = new AccountMeta[3];
    indexed.setAccount(accounts);
    assertNull(accounts[0]);
    assertSame(indexed.accountMeta(), accounts[1]);
    assertNull(accounts[2]);
  }

  @Test
  void sharedCachesDeduplicateIdenticalEntries() {
    final var accountMetaCache = new HashMap<AccountMeta, AccountMeta>();
    final var indexedCache = new HashMap<IndexedAccountMeta, IndexedAccountMeta>();
    final var json = """
        {"account": "%s", "index": 2, "writable": true, "signer": false}""".formatted(key(60).toBase58());

    final var first = parse(json, accountMetaCache, indexedCache);
    final var second = parse(json, accountMetaCache, indexedCache);
    assertSame(first, second);

    // same account meta at a different index: new indexed entry, cached meta
    final var differentIndex = parse("""
        {"account": "%s", "index": 3, "writable": true, "signer": false}""".formatted(key(60).toBase58()),
        accountMetaCache, indexedCache);
    assertNotSame(first, differentIndex);
    assertSame(first.accountMeta(), differentIndex.accountMeta());
  }
}
