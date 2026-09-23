package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/// Jazzer entry point for the instruction-remapping hot path. Instructions
/// reach `mapInstruction` decoded from user-submitted transactions — untrusted
/// input, unlike the semi-trusted mapping configs — and the mapping does raw
/// index arithmetic on the instruction's shape (account count vs the config's
/// index map, data length vs the discriminator length).
///
/// The fuzz payload is carved into an instruction against a fixed mapper
/// built from one fixed-length and one variable-length program config
/// covering every proxy shape (payer, identity, full rewrite with a static
/// account and an optional-account sentinel slot):
/// byte 0 selects the target program, byte 1 packs the account count and an
/// account-pool rotation, and the rest is the instruction data.
///
/// Malformed-instruction contract: `IllegalStateException` out, by message,
/// never an index or array error, a hang or memory exhaustion. On a successful
/// mapping four properties must hold, and their violation escapes as an
/// `AssertionError`:
/// 1. no mapped account is null;
/// 2. mapped data length is the source length plus the discriminator delta;
/// 3. the payload behind the discriminator is preserved byte-for-byte;
/// 4. the mapped program is the invoked proxy program or, for payer/identity
///    mappings, the source program.
///
/// Precondition the rejection branch relies on: no configured discriminator
/// ends in a zero byte. The fixed-length lookup zero-pads data shorter than
/// its discriminator (`Instruction.wrapDiscriminator` copies past the end), so
/// a zero-terminated one would let a short source match and reach the mapping
/// with less data than the discriminator, which the unchecked path does not
/// validate.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test
/// sources.
///
/// Run with `./gradlew :ix-proxy:fuzzIxMapper [-PmaxFuzzTime=<seconds>]`.
public final class IxMapperFuzz {

  private static PublicKey key(final int marker) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) marker;
    return PublicKey.createPubKey(bytes);
  }

  private static final PublicKey PROGRAM_A = key(1);
  private static final PublicKey PROGRAM_B = key(2);
  private static final PublicKey UNKNOWN_PROGRAM = key(3);
  private static final PublicKey INVOKED_KEY = key(4);
  private static final PublicKey FEE_PAYER_KEY = key(5);
  private static final AccountMeta FEE_PAYER = AccountMeta.createFeePayer(FEE_PAYER_KEY);

  /// Every writable/signer combination, for the fee payer's key and others,
  /// so the payer proxy's keep-vs-replace decision is reachable both ways, and
  /// the source program id, the optional-account None sentinel the rewrite
  /// proxy swaps for the proxy program in a placeholder slot.
  private static final AccountMeta[] ACCOUNT_POOL = {
      AccountMeta.createFeePayer(FEE_PAYER_KEY),
      AccountMeta.createWrite(FEE_PAYER_KEY),
      AccountMeta.createReadOnlySigner(FEE_PAYER_KEY),
      AccountMeta.createRead(FEE_PAYER_KEY),
      AccountMeta.createWritableSigner(key(6)),
      AccountMeta.createWrite(key(7)),
      AccountMeta.createRead(key(8)),
      AccountMeta.createRead(PROGRAM_A),
  };

  private static final TransactionMapper<Void> MAPPER = createMapper();

  private static TransactionMapper<Void> createMapper() {
    final var fixedLength = """
        {
          "program_id": "%s",
          "instructions": [
            {
              "src_ix_name": "transfer",
              "src_discriminator": [1, 2],
              "dst_ix_name": "proxy_transfer",
              "dst_discriminator": [3, 4, 5],
              "dynamic_accounts": [{"name": "glam_signer", "index": 0, "writable": true, "signer": true}],
              "static_accounts": [{"account": "%s", "index": 1, "writable": false, "signer": false}],
              "index_map": [2, -1],
              "program_id_placeholder_indices": [0]
            },
            {
              "src_discriminator": [5, 6],
              "dynamic_accounts": [{"name": "glam_signer", "index": 0, "writable": true, "signer": true}],
              "index_map": [-1, 1]
            },
            {"src_discriminator": [8, 9]}
          ]
        }""".formatted(PROGRAM_A.toBase58(), key(9).toBase58());
    final var variableLength = """
        {
          "program_id": "%s",
          "instructions": [
            {"src_discriminator": [7]},
            {"src_discriminator": [8, 9], "dst_discriminator": [20], "index_map": [0, 1]}
          ]
        }""".formatted(PROGRAM_B.toBase58());

    final var accountMetaCache = new HashMap<AccountMeta, AccountMeta>();
    final var indexedAccountMetaCache = new HashMap<IndexedAccountMeta, IndexedAccountMeta>();
    final var configs = List.of(
        ProgramMapConfig.parseConfig(accountMetaCache, indexedAccountMetaCache, JsonIterator.parse(fixedLength)),
        ProgramMapConfig.parseConfig(accountMetaCache, indexedAccountMetaCache, JsonIterator.parse(variableLength))
    );
    return TransactionMapper.createMapper(
        AccountMeta.createInvoked(INVOKED_KEY),
        DynamicAccountConfig::<Void>createFeePayerAccount,
        configs
    );
  }

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length < 2) {
      return;
    }
    final var programId = switch (data[0] & 3) {
      case 0 -> PROGRAM_A;
      case 1 -> PROGRAM_B;
      default -> UNKNOWN_PROGRAM;
    };
    final int numAccounts = data[1] & 0xF;
    final int rotation = (data[1] >>> 4) & 7;
    final var accounts = new ArrayList<AccountMeta>(numAccounts);
    for (int i = 0; i < numAccounts; ++i) {
      accounts.add(ACCOUNT_POOL[(i + rotation) % ACCOUNT_POOL.length]);
    }
    final byte[] ixData = Arrays.copyOfRange(data, 2, data.length);
    final var instruction = Instruction.createInstruction(programId, accounts, ixData);

    final var programProxy = MAPPER.programProxy(programId);
    if (programProxy == null) {
      if (MAPPER.mapInstruction(FEE_PAYER, null, instruction) != instruction) {
        throw new AssertionError("An unknown program's instruction was rewritten.");
      }
      return;
    }

    // Lookup never throws: the fixed-length lookup zero-pads short data and the
    // variable-length scan skips a discriminator longer than the data.
    final var ixProxy = programProxy.lookupProxy(instruction);
    if (ixProxy == null) {
      return;
    }

    final Instruction mapped;
    try {
      mapped = programProxy.mapInstruction(FEE_PAYER, null, instruction);
    } catch (final IllegalStateException tolerated) {
      // rejection is in contract, by message; the unchecked path must fail closed the same way,
      // never with an index or array error, which a catch-all once hid
      try {
        programProxy.mapInstructionUnchecked(FEE_PAYER, null, instruction);
      } catch (final IllegalStateException alsoTolerated) {
      }
      return;
    }
    for (final var account : mapped.accounts()) {
      if (account == null) {
        throw new AssertionError("A mapped instruction carries a null account.");
      }
    }

    final int cpiLength = ixProxy.cpiDiscriminator().length();
    final int proxyLength = ixProxy.proxyDiscriminator().length();
    if (mapped.len() != instruction.len() + (proxyLength - cpiLength)) {
      throw new AssertionError("Mapped data length " + mapped.len()
          + " != source " + instruction.len() + " + delta " + (proxyLength - cpiLength));
    }
    if (!Arrays.equals(
        instruction.data(), instruction.offset() + cpiLength, instruction.offset() + instruction.len(),
        mapped.data(), mapped.offset() + proxyLength, mapped.offset() + mapped.len())) {
      throw new AssertionError("Mapped instruction did not preserve the payload behind the discriminator.");
    }
    final var mappedProgram = mapped.programId().publicKey();
    if (!mappedProgram.equals(INVOKED_KEY) && !mappedProgram.equals(programId)) {
      throw new AssertionError("Mapped to an unexpected program: " + mappedProgram);
    }
  }
}
