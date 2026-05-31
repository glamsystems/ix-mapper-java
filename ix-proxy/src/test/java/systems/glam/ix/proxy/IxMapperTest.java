package systems.glam.ix.proxy;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/// Port of the TypeScript companion test suite `ix-mapper-ts/tests/index.spec.ts`.
final class IxMapperTest {

  private static final PublicKey SYSTEM_PROGRAM_ID = PublicKey.fromBase58Encoded("11111111111111111111111111111111");
  private static final PublicKey TOKEN_PROGRAM_ID = PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final PublicKey KAMINO_LEND_PROGRAM_ID = PublicKey.fromBase58Encoded("KLend2g3cP87fffoy8q1mQqGKjrxjC8boSyAYavgmjD");

  // Production proxy program IDs
  private static final PublicKey EXT_SPL_PROGRAM_ID = PublicKey.fromBase58Encoded("G1NTsQ36mjPe89HtPYqxKsjY5HmYsDR6CbD2gd2U2pta");
  private static final PublicKey EXT_KAMINO_PROGRAM_ID = PublicKey.fromBase58Encoded("G1NTkDEUR3pkEqGCKZtmtmVzCUEdYa86pezHkwYbLyde");

  // Staging proxy program IDs
  private static final PublicKey STAGING_EXT_SPL_PROGRAM_ID = PublicKey.fromBase58Encoded("gstgs9nJgX8PmRHWAAEP9H7xT3ZkaPWSGPYbj3mXdTa");
  private static final PublicKey STAGING_EXT_KAMINO_PROGRAM_ID = PublicKey.fromBase58Encoded("gstgKa2Gq9wf5hM3DFWx1TvUrGYzDYszyFGq3XBY9Uq");

  // GLAM program IDs (from pda.ts)
  private static final PublicKey GLAM_PROGRAM_ID = PublicKey.fromBase58Encoded("GLAMpaME8wdTEzxtiYEAa5yD8fZbxZiz2hNtV58RZiEz");
  private static final PublicKey GLAM_STAGING_PROGRAM_ID = PublicKey.fromBase58Encoded("gstgptmbgJVi5f8ZmSRVZjZkDQwqKa3xWuUtD5WmJHz");

  // Test fixtures
  private static final PublicKey glamState = PublicKey.fromBase58Encoded("F9kXvMXF38YbLWjvZ8sdx8B6qJ4gqjCZy1PXnkUDqKFp");
  private static final PublicKey glamSigner = PublicKey.fromBase58Encoded("8M5XgZWZWxGLDvJgXrv4b8ZFQT5BT8qjN5hPvVm4Cyqg");

  private record GlamAccounts(PublicKey glamState, PublicKey glamVault) {
  }

  private static PublicKey getGlamProgramId(final boolean staging) {
    return staging ? GLAM_STAGING_PROGRAM_ID : GLAM_PROGRAM_ID;
  }

  private static PublicKey getVaultPda(final PublicKey statePda, final boolean staging) {
    return PublicKey.findProgramAddress(
        List.of("vault".getBytes(StandardCharsets.UTF_8), statePda.toByteArray()),
        getGlamProgramId(staging)
    ).publicKey();
  }

  private static PublicKey getVaultPda(final PublicKey statePda) {
    return getVaultPda(statePda, false);
  }

  private static PublicKey getIntegrationAuthority(final PublicKey integrationProgram) {
    return PublicKey.findProgramAddress(
        List.of("integration-authority".getBytes(StandardCharsets.UTF_8)),
        integrationProgram
    ).publicKey();
  }

  private static final Function<DynamicAccountConfig, DynamicAccount<GlamAccounts>> DYNAMIC_ACCOUNT_FACTORY = config -> {
    final int index = config.index();
    final boolean w = config.writable();
    final boolean s = config.signer();
    return switch (config.name()) {
      case "glam_state" -> (mapped, proxyProgram, cpiProgram, feePayer, rt) ->
          mapped[index] = AccountMeta.createMeta(rt.glamState(), w, s);
      case "glam_vault" -> (mapped, proxyProgram, cpiProgram, feePayer, rt) ->
          mapped[index] = AccountMeta.createMeta(rt.glamVault(), w, s);
      case "glam_signer" -> new IndexedFeePayer<>(index);
      case "integration_authority" -> (mapped, proxyProgram, cpiProgram, feePayer, rt) ->
          mapped[index] = AccountMeta.createMeta(getIntegrationAuthority(proxyProgram), w, s);
      case "cpi_program" -> new IndexedReadOnlyProgram<>(index);
      default -> throw new IllegalStateException("Unknown dynamic account type: " + config.name());
    };
  };

  private static TransactionMapper<GlamAccounts> buildMapper(final Path dir) {
    final var defaultInvoked = AccountMeta.createInvoked(GLAM_PROGRAM_ID);
    final var accountMetaCache = new HashMap<AccountMeta, AccountMeta>(256);
    final var indexedAccountMetaCache = new HashMap<IndexedAccountMeta, IndexedAccountMeta>(256);
    final var out = new HashMap<PublicKey, ProgramProxy<GlamAccounts>>();
    try (final var paths = Files.walk(dir, 1)) {
      paths
          .filter(Files::isRegularFile)
          .filter(Files::isReadable)
          .filter(f -> f.getFileName().toString().endsWith(".json"))
          .forEach(f -> ProgramMapConfig.createProxies(
              f, defaultInvoked, out, DYNAMIC_ACCOUNT_FACTORY, accountMetaCache, indexedAccountMetaCache));
      return TransactionMapper.createMapper(GLAM_PROGRAM_ID, out);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final TransactionMapper<GlamAccounts> PROD_MAPPER = buildMapper(Path.of("../glam/mapping-configs-v1"));
  private static final TransactionMapper<GlamAccounts> STAGING_MAPPER = buildMapper(Path.of("../glam/mapping-configs-v1-staging"));

  private static Instruction mapToGlamIx(final Instruction ix,
                                         final PublicKey glamState,
                                         final PublicKey glamSigner,
                                         final boolean staging) {
    final var mapper = staging ? STAGING_MAPPER : PROD_MAPPER;
    final var proxy = mapper.programProxy(ix.programId().publicKey());
    if (proxy == null) {
      return null;
    }
    final var ixProxy = proxy.lookupProxy(ix);
    if (ixProxy == null) {
      return null;
    }
    final var feePayer = AccountMeta.createFeePayer(glamSigner);
    final var runtimeAccounts = new GlamAccounts(glamState, getVaultPda(glamState, staging));
    return proxy.mapInstruction(feePayer, runtimeAccounts, ix);
  }

  private static Instruction mapToGlamIx(final Instruction ix,
                                         final PublicKey glamState,
                                         final PublicKey glamSigner) {
    return mapToGlamIx(ix, glamState, glamSigner, false);
  }

  private static Instruction fixSignerAccounts(final Instruction ix,
                                               final PublicKey glamState,
                                               final PublicKey glamSigner,
                                               final boolean staging) {
    final var vaultPda = getVaultPda(glamState, staging);
    final var fixedKeys = new ArrayList<AccountMeta>(ix.accounts().size());
    for (final var meta : ix.accounts()) {
      if (meta.publicKey().equals(vaultPda) && meta.signer()) {
        fixedKeys.add(AccountMeta.createMeta(glamSigner, meta.write(), true));
      } else {
        fixedKeys.add(meta);
      }
    }
    return Instruction.createInstruction(ix.programId(), fixedKeys, actualData(ix));
  }

  // ----- helpers -----

  private static AccountMeta key(final PublicKey pubkey, final boolean signer, final boolean writable) {
    return AccountMeta.createMeta(pubkey, writable, signer);
  }

  private static byte[] le64(final long value) {
    final byte[] out = new byte[8];
    long v = value;
    for (int i = 0; i < 8; i++) {
      out[i] = (byte) (v & 0xFF);
      v >>>= 8;
    }
    return out;
  }

  private static byte[] concat(final byte[] a, final byte[] b) {
    final byte[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  private static byte[] actualData(final Instruction ix) {
    return Arrays.copyOfRange(ix.data(), ix.offset(), ix.offset() + ix.len());
  }

  private static byte[] subarray(final Instruction ix, final int from, final int to) {
    final byte[] data = actualData(ix);
    return Arrays.copyOfRange(data, from, to);
  }

  private static byte[] subarray(final Instruction ix, final int from) {
    final byte[] data = actualData(ix);
    return Arrays.copyOfRange(data, from, data.length);
  }

  private static void expectAccountMeta(final AccountMeta meta,
                                        final PublicKey pubkey,
                                        final boolean isSigner,
                                        final boolean isWritable) {
    assertEquals(pubkey, meta.publicKey());
    assertEquals(isSigner, meta.signer());
    assertEquals(isWritable, meta.write());
  }

  // ===== mapToGlamIx (production) =====

  @Test
  void shouldMapSystemTransferToGlam() {
    final byte[] lamportsBuffer = le64(1_000_000_000L);
    final var source = Instruction.createInstruction(
        SYSTEM_PROGRAM_ID,
        List.of(
            key(PublicKey.fromBase58Encoded("6ZXwM7dQqJZ7xVLDLvJEqFvL8AqLGbG4KqQgqJ8qJ8qJ"), true, true),
            key(PublicKey.fromBase58Encoded("7YXwM7dQqJZ7xVLDLvJEqFvL8AqLGbG4KqQgqJ8qJ8qJ"), false, true)
        ),
        concat(new byte[]{2, 0, 0, 0}, lamportsBuffer)
    );

    final var result = mapToGlamIx(source, glamState, glamSigner);

    assertNotNull(result);
    assertEquals(GLAM_PROGRAM_ID, result.programId().publicKey());
    assertArrayEquals(new byte[]{(byte) 167, (byte) 164, (byte) 195, (byte) 155, (byte) 219, (byte) 152, (byte) 191, (byte) 230}, subarray(result, 0, 8));
    assertArrayEquals(lamportsBuffer, subarray(result, 8));

    assertEquals(5, result.accounts().size());
    assertEquals(glamState, result.accounts().get(0).publicKey());
    assertEquals(glamSigner, result.accounts().get(2).publicKey());
    assertEquals(SYSTEM_PROGRAM_ID, result.accounts().get(3).publicKey());
    assertEquals(source.accounts().get(1).publicKey(), result.accounts().get(4).publicKey());
  }

  @Test
  void shouldDeriveVaultPdaCorrectly() {
    final var source = Instruction.createInstruction(
        SYSTEM_PROGRAM_ID,
        List.of(key(PublicKey.NONE, true, true), key(PublicKey.NONE, false, true)),
        new byte[]{2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
    );

    final var result = mapToGlamIx(source, glamState, glamSigner);
    final var expectedVaultPda = getVaultPda(glamState);

    assertNotNull(result);
    assertEquals(expectedVaultPda, result.accounts().get(1).publicKey());
  }

  @Test
  void shouldPreserveAccountMetadata() {
    final var source = Instruction.createInstruction(
        SYSTEM_PROGRAM_ID,
        List.of(key(PublicKey.NONE, true, true), key(PublicKey.NONE, false, true)),
        new byte[]{2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
    );

    final var result = mapToGlamIx(source, glamState, glamSigner);
    assertNotNull(result);
    final var keys = result.accounts();

    assertFalse(keys.get(0).write());
    assertFalse(keys.get(0).signer());
    assertTrue(keys.get(1).write());
    assertFalse(keys.get(1).signer());
    assertTrue(keys.get(2).write());
    assertTrue(keys.get(2).signer());
    assertFalse(keys.get(3).write());
    assertFalse(keys.get(3).signer());
  }

  @Test
  void shouldMapTokenTransferCheckedToGlam() {
    final byte[] amountBuffer = le64(1_000_000L);
    final byte decimals = 6;
    final var source = Instruction.createInstruction(
        TOKEN_PROGRAM_ID,
        List.of(
            key(PublicKey.fromBase58Encoded("6ZXwM7dQqJZ7xVLDLvJEqFvL8AqLGbG4KqQgqJ8qJ8qJ"), false, true),
            key(PublicKey.fromBase58Encoded("7YXwM7dQqJZ7xVLDLvJEqFvL8AqLGbG4KqQgqJ8qJ8qJ"), false, false),
            key(PublicKey.fromBase58Encoded("8ZXwM7dQqJZ7xVLDLvJEqFvL8AqLGbG4KqQgqJ8qJ8qJ"), false, true),
            key(PublicKey.fromBase58Encoded("9ZXwM7dQqJZ7xVLDLvJEqFvL8AqLGbG4KqQgqJ8qJ8qJ"), true, false)
        ),
        concat(concat(new byte[]{12}, amountBuffer), new byte[]{decimals})
    );

    final var result = mapToGlamIx(source, glamState, glamSigner);

    assertNotNull(result);
    assertEquals(EXT_SPL_PROGRAM_ID, result.programId().publicKey());
    assertArrayEquals(new byte[]{(byte) 169, (byte) 178, (byte) 117, (byte) 156, (byte) 169, (byte) 191, (byte) 199, (byte) 116}, subarray(result, 0, 8));
    assertArrayEquals(amountBuffer, subarray(result, 8, 16));
    assertEquals(decimals, actualData(result)[16]);
  }

  @Test
  void shouldIncludeIntegrationAuthorityForToken() {
    final var source = Instruction.createInstruction(
        TOKEN_PROGRAM_ID,
        List.of(
            key(PublicKey.NONE, false, true),
            key(PublicKey.NONE, false, false),
            key(PublicKey.NONE, false, true),
            key(PublicKey.NONE, true, false)
        ),
        new byte[]{12, 0, 0, 0, 0, 0, 0, 0, 0, 6}
    );

    final var result = mapToGlamIx(source, glamState, glamSigner);
    assertNotNull(result);
    assertEquals(getIntegrationAuthority(EXT_SPL_PROGRAM_ID), result.accounts().get(3).publicKey());
  }

  @Test
  void shouldReturnNullForUnsupportedProgram() {
    final var source = Instruction.createInstruction(
        PublicKey.fromBase58Encoded("9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"),
        List.of(),
        new byte[]{1, 2, 3, 4}
    );
    assertNull(mapToGlamIx(source, glamState, glamSigner));
  }

  @Test
  void shouldReturnNullForUnsupportedDiscriminator() {
    final var source = Instruction.createInstruction(
        SYSTEM_PROGRAM_ID,
        List.of(key(PublicKey.NONE, true, true), key(PublicKey.NONE, false, true)),
        new byte[]{99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0}
    );
    assertNull(mapToGlamIx(source, glamState, glamSigner));
  }

  @Test
  void shouldRejectMalformedInstructionWithTooFewKeys() {
    final var source = Instruction.createInstruction(
        SYSTEM_PROGRAM_ID,
        List.of(),
        new byte[]{2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
    );
    assertThrows(RuntimeException.class, () -> mapToGlamIx(source, glamState, glamSigner));
  }

  @Test
  void shouldHandleIndexMapWithMinusOne() {
    final var fromAccount = PublicKey.fromBase58Encoded("6ZXwM7dQqJZ7xVLDLvJEqFvL8AqLGbG4KqQgqJ8qJ8qJ");
    final var toAccount = PublicKey.fromBase58Encoded("7YXwM7dQqJZ7xVLDLvJEqFvL8AqLGbG4KqQgqJ8qJ8qJ");
    final var source = Instruction.createInstruction(
        SYSTEM_PROGRAM_ID,
        List.of(key(fromAccount, true, true), key(toAccount, false, true)),
        new byte[]{2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
    );

    final var result = mapToGlamIx(source, glamState, glamSigner);
    assertNotNull(result);
    assertFalse(result.accounts().stream().anyMatch(k -> k.publicKey().equals(fromAccount)));
    assertTrue(result.accounts().stream().anyMatch(k -> k.publicKey().equals(toAccount)));
  }

  @Test
  void shouldReplaceDiscriminatorAndPreservePayload() {
    final byte[] payload = {1, 2, 3, 4, 5, 6, 7, 8};
    final var source = Instruction.createInstruction(
        SYSTEM_PROGRAM_ID,
        List.of(key(PublicKey.NONE, true, true), key(PublicKey.NONE, false, true)),
        concat(new byte[]{2, 0, 0, 0}, payload)
    );

    final var result = mapToGlamIx(source, glamState, glamSigner);
    assertNotNull(result);
    assertArrayEquals(new byte[]{(byte) 167, (byte) 164, (byte) 195, (byte) 155, (byte) 219, (byte) 152, (byte) 191, (byte) 230}, subarray(result, 0, 8));
    assertArrayEquals(payload, subarray(result, 8));
  }

  @Test
  void shouldHandleEmptyPayload() {
    final var source = Instruction.createInstruction(
        SYSTEM_PROGRAM_ID,
        List.of(key(PublicKey.NONE, true, true), key(PublicKey.NONE, false, true)),
        new byte[]{2, 0, 0, 0}
    );

    final var result = mapToGlamIx(source, glamState, glamSigner);
    assertNotNull(result);
    assertEquals(8, actualData(result).length);
    assertArrayEquals(new byte[]{(byte) 167, (byte) 164, (byte) 195, (byte) 155, (byte) 219, (byte) 152, (byte) 191, (byte) 230}, actualData(result));
  }

  // ===== mapToGlamIx (staging) =====

  @Test
  void shouldMapSystemTransferToStagingGlam() {
    final byte[] lamportsBuffer = le64(1_000_000_000L);
    final var source = Instruction.createInstruction(
        SYSTEM_PROGRAM_ID,
        List.of(
            key(PublicKey.fromBase58Encoded("6ZXwM7dQqJZ7xVLDLvJEqFvL8AqLGbG4KqQgqJ8qJ8qJ"), true, true),
            key(PublicKey.fromBase58Encoded("7YXwM7dQqJZ7xVLDLvJEqFvL8AqLGbG4KqQgqJ8qJ8qJ"), false, true)
        ),
        concat(new byte[]{2, 0, 0, 0}, lamportsBuffer)
    );

    final var result = mapToGlamIx(source, glamState, glamSigner, true);

    assertNotNull(result);
    assertEquals(GLAM_STAGING_PROGRAM_ID, result.programId().publicKey());
    assertArrayEquals(new byte[]{(byte) 167, (byte) 164, (byte) 195, (byte) 155, (byte) 219, (byte) 152, (byte) 191, (byte) 230}, subarray(result, 0, 8));
    assertArrayEquals(lamportsBuffer, subarray(result, 8));

    assertEquals(5, result.accounts().size());
    assertEquals(glamState, result.accounts().get(0).publicKey());
    assertEquals(glamSigner, result.accounts().get(2).publicKey());
    assertEquals(SYSTEM_PROGRAM_ID, result.accounts().get(3).publicKey());
    assertEquals(source.accounts().get(1).publicKey(), result.accounts().get(4).publicKey());
  }

  @Test
  void shouldDeriveVaultPdaUsingStagingProgramId() {
    final var source = Instruction.createInstruction(
        SYSTEM_PROGRAM_ID,
        List.of(key(PublicKey.NONE, true, true), key(PublicKey.NONE, false, true)),
        new byte[]{2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
    );

    final var result = mapToGlamIx(source, glamState, glamSigner, true);
    final var expectedStagingVaultPda = getVaultPda(glamState, true);
    final var productionVaultPda = getVaultPda(glamState, false);

    assertNotNull(result);
    assertEquals(expectedStagingVaultPda, result.accounts().get(1).publicKey());
    assertNotEquals(productionVaultPda, result.accounts().get(1).publicKey());
  }

  @Test
  void shouldMapToStagingExtSplProxyProgram() {
    final byte[] amountBuffer = le64(1_000_000L);
    final byte decimals = 6;
    final var source = Instruction.createInstruction(
        TOKEN_PROGRAM_ID,
        List.of(
            key(PublicKey.fromBase58Encoded("6ZXwM7dQqJZ7xVLDLvJEqFvL8AqLGbG4KqQgqJ8qJ8qJ"), false, true),
            key(PublicKey.fromBase58Encoded("7YXwM7dQqJZ7xVLDLvJEqFvL8AqLGbG4KqQgqJ8qJ8qJ"), false, false),
            key(PublicKey.fromBase58Encoded("8ZXwM7dQqJZ7xVLDLvJEqFvL8AqLGbG4KqQgqJ8qJ8qJ"), false, true),
            key(PublicKey.fromBase58Encoded("9ZXwM7dQqJZ7xVLDLvJEqFvL8AqLGbG4KqQgqJ8qJ8qJ"), true, false)
        ),
        concat(concat(new byte[]{12}, amountBuffer), new byte[]{decimals})
    );

    final var result = mapToGlamIx(source, glamState, glamSigner, true);

    assertNotNull(result);
    assertEquals(STAGING_EXT_SPL_PROGRAM_ID, result.programId().publicKey());
    assertArrayEquals(new byte[]{(byte) 169, (byte) 178, (byte) 117, (byte) 156, (byte) 169, (byte) 191, (byte) 199, (byte) 116}, subarray(result, 0, 8));
    assertArrayEquals(amountBuffer, subarray(result, 8, 16));
    assertEquals(decimals, actualData(result)[16]);
  }

  @Test
  void shouldDeriveIntegrationAuthorityFromStagingProxyProgram() {
    final var source = Instruction.createInstruction(
        TOKEN_PROGRAM_ID,
        List.of(
            key(PublicKey.NONE, false, true),
            key(PublicKey.NONE, false, false),
            key(PublicKey.NONE, false, true),
            key(PublicKey.NONE, true, false)
        ),
        new byte[]{12, 0, 0, 0, 0, 0, 0, 0, 0, 6}
    );

    final var result = mapToGlamIx(source, glamState, glamSigner, true);
    assertNotNull(result);
    assertEquals(getIntegrationAuthority(STAGING_EXT_SPL_PROGRAM_ID), result.accounts().get(3).publicKey());
    assertNotEquals(getIntegrationAuthority(EXT_SPL_PROGRAM_ID), result.accounts().get(3).publicKey());
  }

  @Test
  void shouldIncludeStagingGlamProgramIdInStaticAccounts() {
    final var source = Instruction.createInstruction(
        TOKEN_PROGRAM_ID,
        List.of(
            key(PublicKey.NONE, false, true),
            key(PublicKey.NONE, false, false),
            key(PublicKey.NONE, false, true),
            key(PublicKey.NONE, true, false)
        ),
        new byte[]{12, 0, 0, 0, 0, 0, 0, 0, 0, 6}
    );

    final var result = mapToGlamIx(source, glamState, glamSigner, true);
    assertNotNull(result);
    assertFalse(result.accounts().stream().anyMatch(k -> k.publicKey().equals(GLAM_PROGRAM_ID)));
    assertTrue(result.accounts().stream().anyMatch(k -> k.publicKey().equals(GLAM_STAGING_PROGRAM_ID)));
  }

  // ===== Staging vs Production - consistency =====

  @Test
  void shouldProduceDifferentProgramIdsForSameInput() {
    final var source = Instruction.createInstruction(
        SYSTEM_PROGRAM_ID,
        List.of(key(PublicKey.NONE, true, true), key(PublicKey.NONE, false, true)),
        new byte[]{2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
    );

    final var prodResult = mapToGlamIx(source, glamState, glamSigner);
    final var stagingResult = mapToGlamIx(source, glamState, glamSigner, true);

    assertNotNull(prodResult);
    assertNotNull(stagingResult);
    assertNotEquals(prodResult.programId().publicKey(), stagingResult.programId().publicKey());
    assertEquals(GLAM_PROGRAM_ID, prodResult.programId().publicKey());
    assertEquals(GLAM_STAGING_PROGRAM_ID, stagingResult.programId().publicKey());
  }

  @Test
  void shouldProduceSameDiscriminatorsForSameInstruction() {
    final var source = Instruction.createInstruction(
        SYSTEM_PROGRAM_ID,
        List.of(key(PublicKey.NONE, true, true), key(PublicKey.NONE, false, true)),
        new byte[]{2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
    );

    final var prodResult = mapToGlamIx(source, glamState, glamSigner);
    final var stagingResult = mapToGlamIx(source, glamState, glamSigner, true);

    assertNotNull(prodResult);
    assertNotNull(stagingResult);
    assertArrayEquals(subarray(prodResult, 0, 8), subarray(stagingResult, 0, 8));
  }

  @Test
  void shouldProduceDifferentVaultPdas() {
    final var source = Instruction.createInstruction(
        SYSTEM_PROGRAM_ID,
        List.of(key(PublicKey.NONE, true, true), key(PublicKey.NONE, false, true)),
        new byte[]{2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
    );

    final var prodResult = mapToGlamIx(source, glamState, glamSigner);
    final var stagingResult = mapToGlamIx(source, glamState, glamSigner, true);

    assertNotNull(prodResult);
    assertNotNull(stagingResult);
    assertNotEquals(prodResult.accounts().get(1).publicKey(), stagingResult.accounts().get(1).publicKey());
  }

  @Test
  void stagingFalseShouldBeTheDefault() {
    final var source = Instruction.createInstruction(
        SYSTEM_PROGRAM_ID,
        List.of(key(PublicKey.NONE, true, true), key(PublicKey.NONE, false, true)),
        new byte[]{2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
    );

    final var defaultResult = mapToGlamIx(source, glamState, glamSigner);
    final var explicitProdResult = mapToGlamIx(source, glamState, glamSigner, false);

    assertNotNull(defaultResult);
    assertNotNull(explicitProdResult);
    assertEquals(defaultResult.programId().publicKey(), explicitProdResult.programId().publicKey());
    assertArrayEquals(actualData(defaultResult), actualData(explicitProdResult));
    assertEquals(defaultResult.accounts().size(), explicitProdResult.accounts().size());
    for (int i = 0; i < defaultResult.accounts().size(); i++) {
      assertEquals(defaultResult.accounts().get(i).publicKey(), explicitProdResult.accounts().get(i).publicKey());
    }
  }

  // ===== Kamino Lending - request_elevation_group =====

  @Test
  void shouldMapRequestElevationGroupInProductionAndStaging() {
    final byte elevationGroup = 1;
    final var source = Instruction.createInstruction(
        KAMINO_LEND_PROGRAM_ID,
        List.of(
            key(PublicKey.NONE, true, true),
            key(PublicKey.NONE, false, true),
            key(PublicKey.NONE, false, true)
        ),
        new byte[]{36, 119, (byte) 251, (byte) 129, 34, (byte) 240, 7, (byte) 147, elevationGroup}
    );

    // Production
    final var prodResult = mapToGlamIx(source, glamState, glamSigner, false);
    assertNotNull(prodResult);
    assertEquals(EXT_KAMINO_PROGRAM_ID, prodResult.programId().publicKey());
    assertArrayEquals(new byte[]{(byte) 162, 119, (byte) 197, 54, (byte) 246, 84, 55, (byte) 153}, subarray(prodResult, 0, 8));
    assertEquals(elevationGroup, actualData(prodResult)[8]);
    assertEquals(9, prodResult.accounts().size());
    expectAccountMeta(prodResult.accounts().get(0), glamState, false, true);
    expectAccountMeta(prodResult.accounts().get(1), getVaultPda(glamState), false, true);
    expectAccountMeta(prodResult.accounts().get(2), glamSigner, true, true);
    expectAccountMeta(prodResult.accounts().get(3), getIntegrationAuthority(EXT_KAMINO_PROGRAM_ID), false, false);
    expectAccountMeta(prodResult.accounts().get(4), KAMINO_LEND_PROGRAM_ID, false, false);
    expectAccountMeta(prodResult.accounts().get(5), GLAM_PROGRAM_ID, false, false);
    expectAccountMeta(prodResult.accounts().get(6), SYSTEM_PROGRAM_ID, false, false);
    expectAccountMeta(prodResult.accounts().get(7), source.accounts().get(1).publicKey(), false, true);
    expectAccountMeta(prodResult.accounts().get(8), source.accounts().get(2).publicKey(), false, true);

    // Staging
    final var stagingResult = mapToGlamIx(source, glamState, glamSigner, true);
    assertNotNull(stagingResult);
    assertEquals(STAGING_EXT_KAMINO_PROGRAM_ID, stagingResult.programId().publicKey());
    assertArrayEquals(new byte[]{(byte) 162, 119, (byte) 197, 54, (byte) 246, 84, 55, (byte) 153}, subarray(stagingResult, 0, 8));
    assertEquals(elevationGroup, actualData(stagingResult)[8]);
    assertEquals(9, stagingResult.accounts().size());
    expectAccountMeta(stagingResult.accounts().get(0), glamState, false, true);
    expectAccountMeta(stagingResult.accounts().get(1), getVaultPda(glamState, true), false, true);
    expectAccountMeta(stagingResult.accounts().get(2), glamSigner, true, true);
    expectAccountMeta(stagingResult.accounts().get(3), getIntegrationAuthority(STAGING_EXT_KAMINO_PROGRAM_ID), false, false);
    expectAccountMeta(stagingResult.accounts().get(4), KAMINO_LEND_PROGRAM_ID, false, false);
    expectAccountMeta(stagingResult.accounts().get(5), GLAM_STAGING_PROGRAM_ID, false, false);
    expectAccountMeta(stagingResult.accounts().get(6), SYSTEM_PROGRAM_ID, false, false);
    expectAccountMeta(stagingResult.accounts().get(7), source.accounts().get(1).publicKey(), false, true);
    expectAccountMeta(stagingResult.accounts().get(8), source.accounts().get(2).publicKey(), false, true);
  }

  // ===== Edge cases (staging) =====

  @Test
  void shouldReturnNullForUnsupportedProgramInStaging() {
    final var source = Instruction.createInstruction(
        PublicKey.fromBase58Encoded("9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"),
        List.of(),
        new byte[]{1, 2, 3, 4}
    );
    assertNull(mapToGlamIx(source, glamState, glamSigner, true));
  }

  @Test
  void shouldReturnNullForUnsupportedDiscriminatorInStaging() {
    final var source = Instruction.createInstruction(
        SYSTEM_PROGRAM_ID,
        List.of(key(PublicKey.NONE, true, true), key(PublicKey.NONE, false, true)),
        new byte[]{99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0}
    );
    assertNull(mapToGlamIx(source, glamState, glamSigner, true));
  }

  // ===== fixSignerAccounts =====

  @Test
  void shouldReplaceSignerVaultPdasWithGlamSignerOnly() {
    final var vaultPda = getVaultPda(glamState);
    final var unrelatedSigner = PublicKey.fromBase58Encoded("9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin");
    final var ix = Instruction.createInstruction(
        EXT_KAMINO_PROGRAM_ID,
        List.of(
            key(vaultPda, true, true),
            key(vaultPda, false, true),
            key(unrelatedSigner, true, false)
        ),
        new byte[0]
    );

    final var result = fixSignerAccounts(ix, glamState, glamSigner, false);

    expectAccountMeta(result.accounts().get(0), glamSigner, true, true);
    expectAccountMeta(result.accounts().get(1), vaultPda, false, true);
    expectAccountMeta(result.accounts().get(2), unrelatedSigner, true, false);
  }

  @Test
  void shouldUseStagingVaultPdaWhenStaging() {
    final var stagingVaultPda = getVaultPda(glamState, true);
    final var ix = Instruction.createInstruction(
        STAGING_EXT_KAMINO_PROGRAM_ID,
        List.of(key(stagingVaultPda, true, false)),
        new byte[0]
    );

    final var result = fixSignerAccounts(ix, glamState, glamSigner, true);
    expectAccountMeta(result.accounts().getFirst(), glamSigner, true, false);
  }

  // ===== PDA utilities =====

  @Test
  void getVaultPdaShouldProduceDifferentPdasForStagingVsProduction() {
    final var prodPda = getVaultPda(glamState, false);
    final var stagingPda = getVaultPda(glamState, true);

    assertNotEquals(prodPda, stagingPda);
    assertEquals(getVaultPda(glamState, false), prodPda);
    assertEquals(getVaultPda(glamState, true), stagingPda);
  }

  @Test
  void getVaultPdaShouldDefaultToProduction() {
    assertEquals(getVaultPda(glamState, false), getVaultPda(glamState));
  }

  @Test
  void getIntegrationAuthorityShouldDeriveFromTheGivenProgram() {
    final var authority = getIntegrationAuthority(GLAM_PROGRAM_ID);
    final var stagingAuthority = getIntegrationAuthority(GLAM_STAGING_PROGRAM_ID);
    assertNotEquals(authority, stagingAuthority);
  }
}
