package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Jazzer entry point for the instruction-mapping hot path. Instructions reach `map` decoded
/// from user-submitted transactions, untrusted input, and the mapping does index arithmetic
/// on the instruction's shape (account count against the document's positions, data span
/// against its buffer, data length against the discriminator length).
///
/// The fuzz payload is carved into an instruction against a fixed mapper built from two
/// documents covering every seat kind, both optional kinds, a sentinel, an expectation and
/// both remaining-accounts rules:
/// - byte 0 selects the target program (A, B, or one without a document);
/// - byte 1 packs the account count (low three bits) and a rotation of the account pool;
/// - byte 2 carries the writable and signer flags of accounts 0 to 3, two bits each;
/// - byte 3 carries the flags of accounts 4 to 6 in its low six bits, and bit 6 puts program
///   A's id at position 2, where document A's sentinel seat rewrites it;
/// - bytes 4 and 5 are a data span: an offset and a length over the rest, which may point
///   outside it, as a malformed transaction's last instruction does;
/// - the rest is the buffer the span reads.
///
/// Contract: `map` never throws, whatever the instruction; every outcome is a result. On a
/// mapped result three properties must hold, and their violation escapes as an
/// `AssertionError`:
/// 1. the mapped program is the document's proxy program;
/// 2. the mapped data is the handler's discriminator followed by the payload behind the
///    source discriminator, byte for byte, both taken from the document literals below;
/// 3. the mapped accounts are the document's seats in index order, each holding what the
///    seat names (the context's account, the static address, or the source position's key
///    with the seat's flags; the proxy program, read-only, at a sentinel seat whose position
///    holds the source program), less the omittable positions left out, then the accounts
///    beyond the list as they came.
///
/// These properties restate the mapper's rules over the parsed model, so the harness
/// catches a crash, an escape, a mapped instruction whose shape departs from the document,
/// and a mapping that dropped a forwarded position the document does not let a client omit;
/// it cannot flag any other instruction the rules should have refused but mapped. The
/// contract's expected results are the conformance cases (`MapperConformanceTest`), and
/// each seed's outcome is pinned by `IxMapperFuzzSeedsTests`.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :ix-proxy:fuzzIxMapper [-PmaxFuzzTime=<seconds>]`.
public final class IxMapperFuzz {

  static PublicKey key(final int marker) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) marker;
    return PublicKey.createPubKey(bytes);
  }

  static final PublicKey PROGRAM_A = key(1);
  static final PublicKey PROGRAM_B = key(2);
  static final PublicKey UNKNOWN_PROGRAM = key(3);
  static final PublicKey PROXY = key(4);
  static final PublicKey STATE = key(5);
  static final PublicKey VAULT = key(6);
  static final PublicKey SIGNER = key(7);
  static final PublicKey AUTHORITY = key(8);
  static final PublicKey[] POOL = {VAULT, SIGNER, key(9), key(10), key(11), key(12), key(13), key(14)};

  /// Document A's `full` entry: source discriminator [1], handler discriminator eight 9s.
  static final byte[] FULL_DISCRIMINATOR = {1};
  static final byte[] PROXY_FULL_DISCRIMINATOR = {9, 9, 9, 9, 9, 9, 9, 9};
  /// Document B's `strict` entry: source discriminator [1, 2, 3, 4], handler [8, 8].
  static final byte[] STRICT_DISCRIMINATOR = {1, 2, 3, 4};
  static final byte[] PROXY_STRICT_DISCRIMINATOR = {8, 8};

  static final MappingContext CONTEXT = new MappingContext(STATE, VAULT, SIGNER, proxy -> AUTHORITY);

  static final InstructionMapper MAPPER = InstructionMapper.createMapper(List.of(
      MappingDocumentParser.parse("""
          {
            "schema_version": 1, "environment": "fuzz",
            "program_id": "%s", "proxy_program_id": "%s",
            "instructions": [
              {
                "name": "full", "discriminator": [1], "disposition": "map",
                "handler": { "name": "proxy_full", "discriminator": [9, 9, 9, 9, 9, 9, 9, 9] },
                "source_accounts": [
                  { "name": "owner", "writable": true, "signer": true, "expect": "glam_vault" },
                  { "name": "thing", "writable": true, "signer": false },
                  { "name": "maybe", "writable": false, "signer": false, "optional": "program_id" },
                  { "name": "trailing", "writable": false, "signer": false, "optional": "omitted" }
                ],
                "destination_accounts": [
                  { "index": 0, "kind": "dynamic", "name": "glam_state", "writable": false, "signer": false },
                  { "index": 1, "kind": "dynamic", "name": "glam_vault", "writable": true, "signer": false },
                  { "index": 2, "kind": "dynamic", "name": "glam_signer", "writable": true, "signer": true },
                  { "index": 3, "kind": "dynamic", "name": "integration_authority", "writable": false, "signer": false },
                  { "index": 4, "kind": "static", "address": "%s", "writable": false, "signer": false },
                  { "index": 5, "kind": "source", "source": 1, "writable": true, "signer": false },
                  { "index": 6, "kind": "source", "source": 2, "writable": false, "signer": false, "sentinel": true },
                  { "index": 7, "kind": "source", "source": 3, "writable": false, "signer": false }
                ],
                "remaining_accounts": { "kind": "any" }
              },
              { "name": "read", "discriminator": [2], "disposition": "passthrough", "reason": "nothing signs" },
              { "name": "other", "discriminator": [3], "disposition": "unsupported", "reason": "no handler" }
            ]
          }
          """.formatted(PROGRAM_A.toBase58(), PROXY.toBase58(), PROGRAM_A.toBase58()), "a"),
      MappingDocumentParser.parse("""
          {
            "schema_version": 1, "environment": "fuzz",
            "program_id": "%s", "proxy_program_id": "%s",
            "instructions": [
              {
                "name": "strict", "discriminator": [1, 2, 3, 4], "disposition": "map",
                "handler": { "name": "proxy_strict", "discriminator": [8, 8] },
                "source_accounts": [
                  { "name": "payer", "writable": true, "signer": true, "dynamic_signer": true },
                  { "name": "thing", "writable": false, "signer": false }
                ],
                "destination_accounts": [
                  { "index": 0, "kind": "source", "source": 1, "writable": false, "signer": false },
                  { "index": 1, "kind": "source", "source": 0, "writable": true, "signer": true }
                ],
                "remaining_accounts": { "kind": "none" }
              }
            ]
          }
          """.formatted(PROGRAM_B.toBase58(), PROXY.toBase58()), "b")
  ));

  /// The instruction the payload carves, or null for a payload too short to carve.
  static Instruction carve(final byte[] data) {
    if (data.length < 6) {
      return null;
    }
    final var program = switch (data[0] & 3) {
      case 0 -> PROGRAM_A;
      case 1 -> PROGRAM_B;
      default -> UNKNOWN_PROGRAM;
    };
    final int count = data[1] & 7;
    final int rotation = (data[1] & 0xff) >>> 3;
    final int flags = (data[2] & 0xff) | ((data[3] & 0x3f) << 8);
    final boolean sentinel = (data[3] & 0x40) != 0;
    final var accounts = new ArrayList<AccountMeta>(count);
    for (int i = 0; i < count; i++) {
      final var key = sentinel && i == 2 ? PROGRAM_A : POOL[(i + rotation) % POOL.length];
      final int flag = (flags >>> (2 * i)) & 3;
      accounts.add(AccountMeta.createMeta(key, (flag & 1) != 0, (flag & 2) != 0));
    }
    final byte[] buffer = Arrays.copyOfRange(data, 6, data.length);
    // a span the transaction declared: usually inside the buffer, sometimes not
    final int offset = data[4] & 0xff;
    final int len = data[5] & 0xff;
    return Instruction.createInstruction(program, accounts, buffer, offset, len);
  }

  public static void fuzzerTestOneInput(final byte[] data) {
    final var instruction = carve(data);
    if (instruction == null) {
      return;
    }
    final MapResult result = MAPPER.map(instruction, CONTEXT);
    if (result instanceof MapResult.Mapped mapped) {
      check(instruction, mapped);
    }
  }

  static void check(final Instruction instruction, final MapResult.Mapped mapped) {
    final var program = instruction.programId().publicKey();
    if (!mapped.instruction().programId().publicKey().equals(PROXY)) {
      throw new AssertionError("a mapped instruction targets " + mapped.instruction().programId().publicKey());
    }
    final byte[] sourceDiscriminator = program.equals(PROGRAM_A) ? FULL_DISCRIMINATOR : STRICT_DISCRIMINATOR;
    final byte[] handlerDiscriminator = program.equals(PROGRAM_A) ? PROXY_FULL_DISCRIMINATOR : PROXY_STRICT_DISCRIMINATOR;
    final int positions = program.equals(PROGRAM_A) ? 4 : 2;
    final int seats = program.equals(PROGRAM_A) ? 8 : 2;
    final int[] forwardedSources = program.equals(PROGRAM_A) ? new int[]{1, 2, 3} : new int[]{1, 0};
    final int payloadLength = instruction.len() - sourceDiscriminator.length;
    final var expectedData = new byte[handlerDiscriminator.length + payloadLength];
    System.arraycopy(handlerDiscriminator, 0, expectedData, 0, handlerDiscriminator.length);
    System.arraycopy(instruction.data(), instruction.offset() + sourceDiscriminator.length, expectedData, handlerDiscriminator.length, payloadLength);
    if (!Arrays.equals(expectedData, mapped.instruction().data())) {
      throw new AssertionError("the mapped data is not the handler discriminator plus the payload");
    }
    final var accounts = instruction.accounts();
    final int count = accounts.size();
    final var entry = (InstructionEntry.Mapped) MAPPER.documentOf(program).instructions().getFirst();
    final var expected = new ArrayList<AccountMeta>();
    for (final var seat : entry.destinationAccounts()) {
      switch (seat) {
        case DestinationAccount.Dynamic dynamic -> expected.add(AccountMeta.createMeta(switch (dynamic.name()) {
          case GLAM_STATE -> STATE;
          case GLAM_VAULT -> VAULT;
          case GLAM_SIGNER -> SIGNER;
          case INTEGRATION_AUTHORITY -> AUTHORITY;
        }, seat.writable(), seat.signer()));
        case DestinationAccount.Static fixed -> expected.add(AccountMeta.createMeta(fixed.address(), seat.writable(), seat.signer()));
        case DestinationAccount.Source forwarded -> {
          if (forwarded.source() >= count) {
            // a position the instruction left out has no seat, and only an omittable one may be
            if (entry.sourceAccounts().get(forwarded.source()).optional() != OptionalKind.OMITTED) {
              throw new AssertionError("mapped without position " + forwarded.source() + ", which is not omittable");
            }
            continue;
          }
          final var key = accounts.get(forwarded.source()).publicKey();
          expected.add(forwarded.sentinel() && key.equals(program)
              ? AccountMeta.createRead(PROXY)
              : AccountMeta.createMeta(key, seat.writable(), seat.signer()));
        }
      }
    }
    for (int i = positions; i < count; i++) {
      expected.add(accounts.get(i));
    }
    final var actual = mapped.instruction().accounts();
    if (actual.size() != expected.size()) {
      throw new AssertionError("expected " + expected.size() + " mapped accounts, got " + actual.size());
    }
    for (int i = 0; i < expected.size(); i++) {
      final var want = expected.get(i);
      final var got = actual.get(i);
      if (!want.publicKey().equals(got.publicKey()) || want.write() != got.write() || want.signer() != got.signer()) {
        throw new AssertionError("mapped account " + i + " is " + got + ", expected " + want);
      }
    }
    if (seats - forwardedSources.length > expected.size()) {
      throw new AssertionError("fewer mapped accounts than the fixed seats");
    }
  }
}
