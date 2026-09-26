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
/// documents covering every seat kind, both optional kinds, a sentinel, an expectation, both
/// remaining-accounts rules and supplied accounts, and mapped under a context whose supplier
/// the payload selects:
/// - byte 0 selects the target program (A, B, or one without a document) in its low two
///   bits, and in bits 2 to 4 what the context's supplier does ([Supply], in declaration
///   order);
/// - byte 1 packs the account count (low three bits) and a rotation of the account pool;
/// - byte 2 carries the writable and signer flags of accounts 0 to 3, two bits each;
/// - byte 3 carries the flags of accounts 4 to 6 in its low six bits, and bit 6 puts program
///   A's id at position 2, where document A's sentinel seat rewrites it;
/// - bytes 4 and 5 are a data span: an offset and a length over the rest, which may point
///   outside it, as a malformed transaction's last instruction does;
/// - the rest is the buffer the span reads.
///
/// Contract: `map` never throws, whatever the instruction; every outcome is a result. On a
/// mapped result four properties must hold, and on a refusal a fifth; their violation
/// escapes as an `AssertionError`:
/// 1. the mapped program is the document's proxy program;
/// 2. the mapped data is the handler's discriminator followed by the payload behind the
///    source discriminator, byte for byte, both taken from the document literals below;
/// 3. the mapped accounts are the document's seats in index order, each holding what the
///    seat names (the context's account, the static address, or the source position's key
///    with the seat's flags; the proxy program, read-only, at a sentinel seat whose position
///    holds the source program), less the omittable positions left out, then the supplier's
///    answer, read-only and unsigned, then the accounts beyond the list as they came;
/// 4. an entry that lists supplied accounts maps only under a supplier that answers them
///    all, or all the required ones, asked once;
/// 5. a refusal for the context or the supplied accounts comes only from that entry, with
///    the reason its supplier draws ([Supply#refusal]), after asking the supplier once when
///    the context has one.
///
/// Whatever the result, the supplier is asked at most once per instruction.
///
/// The supplier asserts its request too: the entry's programs and names, and each role with
/// the addresses at its `of` positions.
///
/// These properties restate the mapper's rules over the parsed model, so the harness
/// catches a crash, an escape, a mapped instruction whose shape departs from the document,
/// a mapping that dropped a forwarded position the document does not let a client omit, and
/// an outcome that departs from what the supplier answered; it cannot flag any other
/// instruction the rules should have refused but mapped. The contract's expected results are
/// the conformance cases (`MapperConformanceTest`), and each seed's outcome is pinned by
/// `IxMapperFuzzSeedsTests`.
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
  /// Document A's `priced` entry: source discriminator [4], handler discriminator eight 7s.
  static final byte[] PRICED_DISCRIMINATOR = {4};
  static final byte[] PROXY_PRICED_DISCRIMINATOR = {7, 7, 7, 7, 7, 7, 7, 7};
  /// Document B's `strict` entry: source discriminator [1, 2, 3, 4], handler [8, 8].
  static final byte[] STRICT_DISCRIMINATOR = {1, 2, 3, 4};
  static final byte[] PROXY_STRICT_DISCRIMINATOR = {8, 8};

  /// A supplied account of the `priced` entry, as the document lists it, and the key the
  /// supplier answers for it.
  record Supplied(String role, int[] of, boolean optional, PublicKey answer) {
  }

  static final List<Supplied> PRICED_SUPPLIED = List.of(
      new Supplied("price_oracle", new int[]{0}, false, key(15)),
      new Supplied("reserve", new int[0], false, key(16)),
      new Supplied("market", new int[]{1, 0}, true, key(17))
  );
  static final int PRICED_REQUIRED = 2;
  /// The key a supplier that answers too many adds past the listed ones.
  static final PublicKey EXTRA = key(18);

  /// What the context's supplier does when asked.
  enum Supply {
    ALL(null),
    /// answers the required accounts and leaves out the optional tail
    REQUIRED(null),
    /// the context has no supplier
    NO_SUPPLIER(UnsupportedReason.CONTEXT),
    NULL_ANSWER(UnsupportedReason.CONTEXT),
    THROWS(UnsupportedReason.CONTEXT),
    /// answers one fewer than the required accounts
    TOO_FEW(UnsupportedReason.SUPPLIED_ACCOUNTS),
    /// answers every account and one more
    TOO_MANY(UnsupportedReason.SUPPLIED_ACCOUNTS),
    /// answers every account, the last one null
    NULL_ELEMENT(UnsupportedReason.SUPPLIED_ACCOUNTS);

    /// the refusal an entry that lists supplied accounts draws; null when it maps
    final UnsupportedReason refusal;

    Supply(final UnsupportedReason refusal) {
      this.refusal = refusal;
    }
  }

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
              { "name": "other", "discriminator": [3], "disposition": "unsupported", "reason": "no handler" },
              {
                "name": "priced", "discriminator": [4], "disposition": "map",
                "handler": { "name": "proxy_priced", "discriminator": [7, 7, 7, 7, 7, 7, 7, 7] },
                "source_accounts": [
                  { "name": "mint", "writable": false, "signer": false },
                  { "name": "position", "writable": true, "signer": false }
                ],
                "destination_accounts": [
                  { "index": 0, "kind": "dynamic", "name": "glam_state", "writable": false, "signer": false },
                  { "index": 1, "kind": "dynamic", "name": "glam_vault", "writable": true, "signer": false },
                  { "index": 2, "kind": "dynamic", "name": "glam_signer", "writable": true, "signer": true },
                  { "index": 3, "kind": "source", "source": 1, "writable": true, "signer": false },
                  { "index": 4, "kind": "source", "source": 0, "writable": false, "signer": false }
                ],
                "remaining_accounts": { "kind": "any" },
                "supplied_accounts": [
                  { "role": "price_oracle", "of": [0] },
                  { "role": "reserve" },
                  { "role": "market", "of": [1, 0], "optional": true }
                ]
              }
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

  /// What the payload's supplier does; the payload is at least one byte.
  static Supply supply(final byte[] data) {
    return Supply.values()[(data[0] >>> 2) & 7];
  }

  /// The context for a supply, whose supplier counts in `asks[0]` the times it is asked.
  static MappingContext context(final Supply supply, final int[] asks) {
    return new MappingContext(STATE, VAULT, SIGNER, proxy -> AUTHORITY, supply == Supply.NO_SUPPLIER ? null : request -> {
      ++asks[0];
      return answer(supply, request);
    });
  }

  /// The supplier's answer, after it checks the request against the `priced` entry, the one
  /// that lists supplied accounts.
  static List<PublicKey> answer(final Supply supply, final SuppliedAccountsRequest request) {
    if (!request.proxyProgram().equals(PROXY) || !request.program().equals(PROGRAM_A)
        || !request.source().equals("priced") || !request.handler().equals("proxy_priced")
        || request.roles().size() != PRICED_SUPPLIED.size()) {
      throw new AssertionError("the supplier was asked " + request);
    }
    final var accounts = request.instruction().accounts();
    for (int i = 0; i < PRICED_SUPPLIED.size(); i++) {
      final var listed = PRICED_SUPPLIED.get(i);
      final var of = new ArrayList<PublicKey>(listed.of().length);
      for (final int position : listed.of()) {
        of.add(accounts.get(position).publicKey());
      }
      final var role = request.roles().get(i);
      if (!role.role().equals(listed.role()) || role.optional() != listed.optional() || !role.of().equals(of)) {
        throw new AssertionError("role " + i + " of the request is " + role);
      }
    }
    final var answer = new ArrayList<PublicKey>(PRICED_SUPPLIED.size() + 1);
    for (final var listed : PRICED_SUPPLIED) {
      answer.add(listed.answer());
    }
    return switch (supply) {
      case ALL -> answer;
      case REQUIRED -> answer.subList(0, PRICED_REQUIRED);
      case NO_SUPPLIER -> throw new AssertionError("a context without a supplier was asked");
      case NULL_ANSWER -> null;
      case THROWS -> throw new IllegalStateException("the supplier cannot answer");
      case TOO_FEW -> answer.subList(0, PRICED_REQUIRED - 1);
      case TOO_MANY -> {
        answer.add(EXTRA);
        yield answer;
      }
      case NULL_ELEMENT -> {
        answer.set(answer.size() - 1, null);
        yield answer;
      }
    };
  }

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
    mapChecked(data);
  }

  /// A mapping's result and the times the supplier was asked for it.
  record Checked(MapResult result, int asks) {
  }

  /// The result of mapping the instruction the payload carves under the context its supplier
  /// byte selects, once the properties hold; null for a payload too short to carve.
  static Checked mapChecked(final byte[] data) {
    final var instruction = carve(data);
    if (instruction == null) {
      return null;
    }
    final var supply = supply(data);
    final int[] asks = {0};
    final MapResult result = MAPPER.map(instruction, context(supply, asks));
    if (asks[0] > 1) {
      throw new AssertionError("the supplier was asked " + asks[0] + " times for one instruction");
    }
    switch (result) {
      case MapResult.Mapped mapped -> check(instruction, supply, asks[0], mapped);
      case MapResult.Unsupported unsupported -> checkRefusal(supply, asks[0], unsupported);
      case MapResult.Passthrough _ -> {
      }
    }
    return new Checked(result, asks[0]);
  }

  static void checkRefusal(final Supply supply, final int asks, final MapResult.Unsupported unsupported) {
    final var reason = unsupported.reason();
    if ((reason == UnsupportedReason.CONTEXT || reason == UnsupportedReason.SUPPLIED_ACCOUNTS)
        && (reason != supply.refusal || !"priced".equals(unsupported.source())
        || asks != (supply == Supply.NO_SUPPLIER ? 0 : 1))) {
      throw new AssertionError(unsupported.source() + " refused for " + reason + " under supplier " + supply
          + ", asked " + asks + " times: " + unsupported.message());
    }
  }

  static void check(final Instruction instruction, final Supply supply, final int asks, final MapResult.Mapped mapped) {
    final var program = instruction.programId().publicKey();
    if (!mapped.instruction().programId().publicKey().equals(PROXY)) {
      throw new AssertionError("a mapped instruction targets " + mapped.instruction().programId().publicKey());
    }
    final String name;
    final byte[] sourceDiscriminator;
    final byte[] handlerDiscriminator;
    if (!program.equals(PROGRAM_A)) {
      name = "strict";
      sourceDiscriminator = STRICT_DISCRIMINATOR;
      handlerDiscriminator = PROXY_STRICT_DISCRIMINATOR;
    } else if (instruction.data()[instruction.offset()] == PRICED_DISCRIMINATOR[0]) {
      name = "priced";
      sourceDiscriminator = PRICED_DISCRIMINATOR;
      handlerDiscriminator = PROXY_PRICED_DISCRIMINATOR;
    } else {
      name = "full";
      sourceDiscriminator = FULL_DISCRIMINATOR;
      handlerDiscriminator = PROXY_FULL_DISCRIMINATOR;
    }
    final boolean supplied = name.equals("priced");
    if (supplied && supply.refusal != null) {
      throw new AssertionError(name + " mapped under supplier " + supply);
    }
    if (asks != (supplied ? 1 : 0)) {
      throw new AssertionError(name + " mapped with the supplier asked " + asks + " times");
    }
    final int payloadLength = instruction.len() - sourceDiscriminator.length;
    final var expectedData = new byte[handlerDiscriminator.length + payloadLength];
    System.arraycopy(handlerDiscriminator, 0, expectedData, 0, handlerDiscriminator.length);
    System.arraycopy(instruction.data(), instruction.offset() + sourceDiscriminator.length, expectedData, handlerDiscriminator.length, payloadLength);
    if (!Arrays.equals(expectedData, mapped.instruction().data())) {
      throw new AssertionError("the mapped data is not the handler discriminator plus the payload");
    }
    final var accounts = instruction.accounts();
    final int count = accounts.size();
    final var entry = MAPPER.documentOf(program).instructions().stream()
        .filter(candidate -> candidate.name().equals(name))
        .map(InstructionEntry.Mapped.class::cast)
        .findFirst().orElseThrow();
    final int positions = entry.sourceAccounts().size();
    final var expected = new ArrayList<AccountMeta>();
    int fixedSeats = 0;
    for (final var seat : entry.destinationAccounts()) {
      switch (seat) {
        case DestinationAccount.Dynamic dynamic -> {
          ++fixedSeats;
          expected.add(AccountMeta.createMeta(switch (dynamic.name()) {
            case GLAM_STATE -> STATE;
            case GLAM_VAULT -> VAULT;
            case GLAM_SIGNER -> SIGNER;
            case INTEGRATION_AUTHORITY -> AUTHORITY;
          }, seat.writable(), seat.signer()));
        }
        case DestinationAccount.Static fixed -> {
          ++fixedSeats;
          expected.add(AccountMeta.createMeta(fixed.address(), seat.writable(), seat.signer()));
        }
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
    if (supplied) {
      final int answered = supply == Supply.ALL ? PRICED_SUPPLIED.size() : PRICED_REQUIRED;
      for (int i = 0; i < answered; i++) {
        expected.add(AccountMeta.createRead(PRICED_SUPPLIED.get(i).answer()));
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
    if (fixedSeats > expected.size()) {
      throw new AssertionError("fewer mapped accounts than the fixed seats");
    }
  }
}
