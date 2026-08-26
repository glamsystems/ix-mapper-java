package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.programs.Discriminator;
import software.sava.core.tx.Instruction;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

final class GlamIxTests {

  private static final PublicKey INVOKED_PROGRAM = PublicKey.fromBase58Encoded("GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc");

  /// The mappings root the suite reads: the downloaded `../glam` clone by default, or the
  /// directory named by the `glam.mappings.dir` system property (wired from the
  /// `glamMappingsDir` Gradle property) so regenerated configs face the same validation.
  static Path mappingsRoot() {
    return Path.of(System.getProperty("glam.mappings.dir", "../glam"));
  }

  record GlamVaultAccounts(AccountMeta readGlamState,
                           AccountMeta writeGlamState,
                           AccountMeta readGlamVault,
                           AccountMeta writeGlamVault) {

    static GlamVaultAccounts createAccounts(final PublicKey stateAccount, final PublicKey vaultAccount) {
      return new GlamVaultAccounts(
          AccountMeta.createRead(stateAccount),
          AccountMeta.createWrite(stateAccount),
          AccountMeta.createRead(vaultAccount),
          AccountMeta.createWrite(vaultAccount)
      );
    }
  }

  public static <A> TransactionMapper<A> createMapper(final Path mappingFileDirectory,
                                                      final AccountMeta invokedProxyProgram,
                                                      final Map<PublicKey, ProgramProxy<A>> programProxiesOutput,
                                                      final Function<DynamicAccountConfig, DynamicAccount<A>> dynamicAccountFactory) {
    // Used to de-duplicate AccountMeta objects.
    final var accountMetaCache = new HashMap<AccountMeta, AccountMeta>(256);
    final var indexedAccountMetaCache = new HashMap<IndexedAccountMeta, IndexedAccountMeta>(256);

    try (final var paths = Files.walk(mappingFileDirectory, 1)) {
      paths
          .filter(Files::isRegularFile)
          .filter(Files::isReadable)
          .filter(f -> f.getFileName().toString().endsWith(".json"))
          .forEach(mappingFile -> ProgramMapConfig.createProxies(
              mappingFile,
              invokedProxyProgram,
              programProxiesOutput,
              dynamicAccountFactory,
              accountMetaCache,
              indexedAccountMetaCache
          ));
      return TransactionMapper.createMapper(INVOKED_PROGRAM, programProxiesOutput);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static <A> TransactionMapper<A> createMapper(final Path mappingFileDirectory,
                                                      final AccountMeta invokedProxyProgram,
                                                      final Function<DynamicAccountConfig, DynamicAccount<A>> dynamicAccountFactory) {
    return createMapper(
        mappingFileDirectory,
        invokedProxyProgram,
        new HashMap<>(),
        dynamicAccountFactory
    );
  }

  private static final Function<DynamicAccountConfig, DynamicAccount<GlamVaultAccounts>> DYNAMIC_ACCOUNT_FACTORY = accountConfig -> {
    final int index = accountConfig.index();
    final boolean w = accountConfig.writable();
    return switch (accountConfig.name()) {
      case "glam_state" -> (mappedAccounts, _, _, _, vaultAccounts) -> mappedAccounts[index] = w
          ? vaultAccounts.writeGlamState() : vaultAccounts.readGlamState();
      case "glam_vault" -> (mappedAccounts, _, _, _, vaultAccounts) -> mappedAccounts[index] = w
          ? vaultAccounts.writeGlamVault() : vaultAccounts.readGlamVault();
      case "glam_signer" -> accountConfig.createFeePayerAccount();
      case "cpi_program" -> accountConfig.createReadCpiProgram();
      case "integration_authority" -> accountConfig.createReadIntegrationAuthority();
      default -> throw new IllegalStateException("Unknown dynamic account type: " + accountConfig.name());
    };
  };

  private static final TransactionMapper<GlamVaultAccounts> txMapper = createMapper(
      mappingsRoot().resolve("mapping-configs-v1"),
      AccountMeta.createInvoked(GlamIxTests.INVOKED_PROGRAM),
      new HashMap<>(),
      DYNAMIC_ACCOUNT_FACTORY
  );

  private static Collection<ProgramProxy<GlamVaultAccounts>> createProxies(final byte[] mappingJson) {
    final var ji = JsonIterator.parse(mappingJson);

    final var accountMetaCache = new HashMap<AccountMeta, AccountMeta>();
    final var indexedAccountMetaCache = new HashMap<IndexedAccountMeta, IndexedAccountMeta>();

    final var programMapConfig = ProgramMapConfig.parseConfig(accountMetaCache, indexedAccountMetaCache, ji);

    return programMapConfig.createProgramProxies(AccountMeta.createInvoked(GlamIxTests.INVOKED_PROGRAM), DYNAMIC_ACCOUNT_FACTORY);
  }

  private static void validateGlamAccounts(final PublicKey feePayer,
                                           final GlamVaultAccounts vaultAccounts,
                                           final Instruction sourceIx,
                                           final Instruction mappedIx) {
    final var mappedAccounts = mappedIx.accounts();
    assertEquals(vaultAccounts.readGlamState.publicKey(), mappedAccounts.getFirst().publicKey());
    assertEquals(vaultAccounts.writeGlamVault, mappedAccounts.get(1));

    final var mappedFeePayer = mappedAccounts.get(2);
    assertEquals(feePayer, mappedFeePayer.publicKey());
    assertTrue(mappedFeePayer.signer());
    assertTrue(mappedFeePayer.write());

    assertEquals(sourceIx.programId().publicKey(), mappedAccounts.get(3).publicKey());
  }

  private static void validateMappedIx(final PublicKey feePayer,
                                       final GlamVaultAccounts vaultAccounts,
                                       final Instruction sourceIx,
                                       final Discriminator sourceDiscriminator,
                                       final Instruction mappedIx,
                                       final Discriminator proxyDiscriminator) {
    assertEquals(INVOKED_PROGRAM, mappedIx.programId().publicKey());
    validateGlamAccounts(feePayer, vaultAccounts, sourceIx, mappedIx);

    final int srcDiscriminatorLen = sourceDiscriminator.length();
    assertEquals(sourceDiscriminator, sourceIx.wrapDiscriminator(srcDiscriminatorLen));
    final int proxyDiscriminatorLen = proxyDiscriminator.length();
    assertEquals(proxyDiscriminator, mappedIx.wrapDiscriminator(proxyDiscriminatorLen));

    final var sourceData = sourceIx.data();
    final var mappedData = mappedIx.data();
    assertEquals(proxyDiscriminatorLen - srcDiscriminatorLen, mappedData.length - sourceData.length);

    assertArrayEquals(
        Arrays.copyOfRange(sourceData, srcDiscriminatorLen, sourceData.length),
        Arrays.copyOfRange(mappedData, proxyDiscriminatorLen, mappedData.length)
    );
  }

  private static List<Instruction> parseInstructions(final String ixData) {
    final var instructions = new ArrayList<Instruction>();
    final var ji = JsonIterator.parse(ixData);
    while (ji.readArray()) {
      final var programId = PublicKey.fromBase58Encoded(ji.skipUntil("programId").readString());
      final var accounts = new ArrayList<AccountMeta>();
      for (ji.skipUntil("accounts"); ji.readArray(); ) {
        final var parser = new AccountMetaParser();
        ji.testObject(parser);
        accounts.add(parser.createMeta());
      }
      final byte[] data = ji.skipUntil("data").decodeBase64String();
      final var ix = Instruction.createInstruction(programId, accounts, data);
      instructions.add(ix);
      ji.closeObj();
    }
    return instructions;
  }

  static final class AccountMetaParser implements FieldBufferPredicate {

    private PublicKey publicKey;
    private boolean feePayer;
    private boolean signer;
    private boolean writable;
    private boolean invoked;

    private AccountMetaParser() {
    }

    AccountMeta createMeta() {
      return AccountMeta.createMeta(
          publicKey,
          invoked,
          feePayer,
          writable,
          signer
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("publicKey", buf, offset, len)) {
        publicKey = PublicKey.fromBase58Encoded(ji.readString());
      } else if (fieldEquals("feePayer", buf, offset, len)) {
        feePayer = ji.readBoolean();
      } else if (fieldEquals("signer", buf, offset, len)) {
        signer = ji.readBoolean();
      } else if (fieldEquals("writable", buf, offset, len)) {
        writable = ji.readBoolean();
      } else if (fieldEquals("invoked", buf, offset, len)) {
        invoked = ji.readBoolean();
      } else {
        throw new IllegalStateException("Unexpected field: " + new String(buf, offset, len));
      }
      return true;
    }
  }
}
