package systems.glam.ix.proxy;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.programs.Discriminator;
import software.sava.core.tx.Instruction;
import systems.comodal.jsoniter.JsonIterator;

import java.util.HashMap;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/// Direct parse-and-assert coverage of [ProgramMapConfig#parseConfig] and the
/// program-proxy construction and lookup paths it feeds: the fixed-length
/// discriminator map ([FixedLengthDiscriminatorProgramProxy]), the
/// variable-length scan ([ProgramProxyRecord]), and the shared
/// [BaseProgramProxy] lookup/mapping plumbing.
final class ProgramMapConfigTests {

  private static final PublicKey PROGRAM_A = key(40);
  private static final PublicKey PROGRAM_B = key(41);
  private static final PublicKey PROXY_PROGRAM = key(42);
  private static final AccountMeta DEFAULT_INVOKED = AccountMeta.createInvoked(key(43));
  private static final AccountMeta FEE_PAYER = AccountMeta.createFeePayer(key(44));

  private static final Function<DynamicAccountConfig, DynamicAccount<Void>> FACTORY =
      DynamicAccountConfig::createFeePayerAccount;

  private static PublicKey key(final int marker) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) marker;
    return PublicKey.createPubKey(bytes);
  }

  private static ProgramMapConfig parse(final String json) {
    return ProgramMapConfig.parseConfig(new HashMap<>(), new HashMap<>(), JsonIterator.parse(json));
  }

  private static String identityInstruction(final int... discriminator) {
    final var sb = new StringBuilder("{\"src_discriminator\": [");
    for (int i = 0; i < discriminator.length; ++i) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(discriminator[i]);
    }
    return sb.append("]}").toString();
  }

  @Test
  void parsesASingleProgramId() {
    final var config = parse("""
        {"program_id": "%s", "instructions": []}""".formatted(PROGRAM_A.toBase58()));
    assertEquals(1, config.programs().size());
    final var program = config.programs().iterator().next();
    assertEquals(PROGRAM_A, program.publicKey());
    assertFalse(program.write());
    assertFalse(program.signer());
    assertTrue(config.ixMapConfigs().isEmpty());
    assertEquals(0, config.discriminatorLength());
    assertFalse(config.fixedLengthDiscriminator());
    assertNull(config.invokedProxyProgram());
  }

  @Test
  void parsesAProgramIdArrayAndDropsDuplicates() {
    final var config = parse("""
        {"program_id": ["%s", "%s", "%s"], "instructions": []}"""
        .formatted(PROGRAM_A.toBase58(), PROGRAM_B.toBase58(), PROGRAM_A.toBase58()));
    assertEquals(2, config.programs().size());
    assertEquals(2, config.programs().stream().map(AccountMeta::publicKey).distinct().count());
  }

  @Test
  void parsesTheProxyProgramId() {
    final var config = parse("""
        {"program_id": "%s", "proxy_program_id": "%s", "instructions": []}"""
        .formatted(PROGRAM_A.toBase58(), PROXY_PROGRAM.toBase58()));
    assertEquals(PROXY_PROGRAM, config.invokedProxyProgram().publicKey());
  }

  @Test
  void unknownFieldsThrow() {
    assertThrows(IllegalStateException.class, () -> parse("""
        {"program_id": "%s", "bogus": true}""".formatted(PROGRAM_A.toBase58())));
  }

  @Test
  void consistentDiscriminatorLengthsAreFixed() {
    final var config = parse("""
        {"program_id": "%s", "instructions": [%s, %s]}"""
        .formatted(PROGRAM_A.toBase58(), identityInstruction(7), identityInstruction(8)));
    assertEquals(1, config.discriminatorLength());
    assertTrue(config.fixedLengthDiscriminator());
  }

  @Test
  void mixedDiscriminatorLengthsAreNotFixed() {
    final var config = parse("""
        {"program_id": "%s", "instructions": [%s, %s]}"""
        .formatted(PROGRAM_A.toBase58(), identityInstruction(7), identityInstruction(8, 9)));
    assertEquals(-1, config.discriminatorLength());
    assertFalse(config.fixedLengthDiscriminator());
  }

  @Test
  void fixedLengthProxiesLookupByWrappedDiscriminator() {
    final var config = parse("""
        {"program_id": ["%s", "%s"], "instructions": [%s, %s]}"""
        .formatted(PROGRAM_A.toBase58(), PROGRAM_B.toBase58(), identityInstruction(7), identityInstruction(8)));

    final var proxies = config.createProgramProxies(DEFAULT_INVOKED, FACTORY);
    assertEquals(2, proxies.size());
    assertEquals(2, proxies.stream().map(ProgramProxy::cpiProgram).distinct().count());

    final var proxy = proxies.iterator().next();
    final var known = Discriminator.createDiscriminator(new byte[]{7});
    assertNotNull(proxy.lookupProxy(known));
    assertSame(proxy.lookupProxy(known), proxy.lookupProxyOrThrow(known));
    assertNotNull(proxy.lookupProxy(Discriminator.createDiscriminator(new byte[]{8})));
    assertNull(proxy.lookupProxy(Discriminator.createDiscriminator(new byte[]{9})));
    // fixed-length lookup is exact, never a prefix scan
    assertNull(proxy.lookupProxy(Discriminator.createDiscriminator(new byte[]{7, 42})));

    final var ix = Instruction.createInstruction(PROGRAM_A, List.of(), new byte[]{7, 42});
    assertNotNull(proxy.lookupProxy(ix));
    assertSame(proxy.lookupProxy(ix), proxy.lookupProxyOrThrow(ix));

    final var unknownIx = Instruction.createInstruction(PROGRAM_A, List.of(), new byte[]{9, 42});
    assertNull(proxy.lookupProxy(unknownIx));
    assertThrows(IllegalStateException.class, () -> proxy.lookupProxyOrThrow(unknownIx));
    assertThrows(IllegalStateException.class, () ->
        proxy.lookupProxyOrThrow(Discriminator.createDiscriminator(new byte[]{9})));
  }

  @Test
  void variableLengthProxiesLookupByDiscriminatorScan() {
    final var config = parse("""
        {"program_id": "%s", "instructions": [%s, %s]}"""
        .formatted(PROGRAM_A.toBase58(), identityInstruction(7), identityInstruction(8, 9)));

    final var proxies = config.createProgramProxies(DEFAULT_INVOKED, FACTORY);
    assertEquals(1, proxies.size());
    final var proxy = proxies.iterator().next();
    assertEquals(PROGRAM_A, proxy.cpiProgram());

    final var shortMatch = proxy.lookupProxy(Discriminator.createDiscriminator(new byte[]{7}));
    assertNotNull(shortMatch);
    assertArrayEquals(new byte[]{7}, shortMatch.cpiDiscriminator().data());

    final var longMatch = proxy.lookupProxy(Instruction.createInstruction(PROGRAM_A, List.of(), new byte[]{8, 9, 42}));
    assertNotNull(longMatch);
    assertArrayEquals(new byte[]{8, 9}, longMatch.cpiDiscriminator().data());

    // [8] alone is a prefix of neither registered discriminator
    assertNull(proxy.lookupProxy(Discriminator.createDiscriminator(new byte[]{8})));
    assertThrows(IllegalStateException.class, () ->
        proxy.lookupProxyOrThrow(Instruction.createInstruction(PROGRAM_A, List.of(), new byte[]{6, 42})));
  }

  @Test
  void mapInstructionValidatesBeforeMappingAndUncheckedDoesNot() {
    final var config = parse("""
        {"program_id": "%s", "instructions": [%s]}"""
        .formatted(PROGRAM_A.toBase58(), identityInstruction(7)));
    final var proxy = config.createProgramProxies(DEFAULT_INVOKED, FACTORY).iterator().next();

    final var ix = Instruction.createInstruction(PROGRAM_A, List.of(), new byte[]{7, 42});
    assertSame(ix, proxy.mapInstruction(FEE_PAYER, null, ix));
    assertSame(ix, proxy.mapInstructionUnchecked(FEE_PAYER, null, ix));

    // identity mapping validates the program id on the checked path only
    final var wrongProgram = Instruction.createInstruction(PROGRAM_B, List.of(), new byte[]{7, 42});
    assertThrows(IllegalStateException.class, () -> proxy.mapInstruction(FEE_PAYER, null, wrongProgram));
    assertSame(wrongProgram, proxy.mapInstructionUnchecked(FEE_PAYER, null, wrongProgram));
  }

  @Test
  void theProxyProgramIdOverridesTheDefaultInvokedProgram() {
    final var withOverride = parse("""
        {
          "program_id": "%s",
          "proxy_program_id": "%s",
          "instructions": [{"src_discriminator": [7], "dst_discriminator": [1, 2], "index_map": [0]}]
        }""".formatted(PROGRAM_A.toBase58(), PROXY_PROGRAM.toBase58()));
    final var overridden = withOverride.createProgramProxies(DEFAULT_INVOKED, FACTORY).iterator().next();
    final var account = AccountMeta.createWrite(key(45));
    final var ix = Instruction.createInstruction(PROGRAM_A, List.of(account), new byte[]{7});
    assertEquals(PROXY_PROGRAM, overridden.mapInstruction(FEE_PAYER, null, ix).programId().publicKey());

    final var withoutOverride = parse("""
        {
          "program_id": "%s",
          "instructions": [{"src_discriminator": [7], "dst_discriminator": [1, 2], "index_map": [0]}]
        }""".formatted(PROGRAM_A.toBase58()));
    final var defaulted = withoutOverride.createProgramProxies(DEFAULT_INVOKED, FACTORY).iterator().next();
    assertEquals(DEFAULT_INVOKED.publicKey(), defaulted.mapInstruction(FEE_PAYER, null, ix).programId().publicKey());
  }

  @Test
  void createMapperBuildsFromParsedConfigs() {
    final var config = parse("""
        {"program_id": "%s", "instructions": [%s]}"""
        .formatted(PROGRAM_A.toBase58(), identityInstruction(7)));

    final var mapper = TransactionMapper.createMapper(DEFAULT_INVOKED, FACTORY, List.of(config));
    assertEquals(DEFAULT_INVOKED.publicKey(), mapper.invokedProxyProgram());
    assertEquals(PROGRAM_A, mapper.programProxy(PROGRAM_A).cpiProgram());

    final var ix = Instruction.createInstruction(PROGRAM_A, List.of(), new byte[]{7, 42});
    assertSame(ix, mapper.mapInstruction(FEE_PAYER, null, ix));
  }
}
