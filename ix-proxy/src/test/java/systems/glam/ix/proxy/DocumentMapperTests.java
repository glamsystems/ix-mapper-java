package systems.glam.ix.proxy;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// The signer flag of a forwarded seat: a caller-chosen signer (`dynamic_signer`) keeps the
/// caller's flag at an unsigned seat and must sign at a signing seat, and a position whose
/// flag the IDL fixes must match its seat. The conformance cases carry the same rule; these
/// pin it without the synced case directory.
final class DocumentMapperTests {

  private static final PublicKey PROGRAM = PublicKey.fromBase58Encoded("Src1111111111111111111111111111111111111111");
  private static final PublicKey PROXY = PublicKey.fromBase58Encoded("Proxy11111111111111111111111111111111111111");
  private static final PublicKey STATE = PublicKey.fromBase58Encoded("State11111111111111111111111111111111111111");
  private static final PublicKey VAULT = PublicKey.fromBase58Encoded("Vau1t11111111111111111111111111111111111111");
  private static final PublicKey SIGNER = PublicKey.fromBase58Encoded("Signer1111111111111111111111111111111111111");
  private static final PublicKey AUTHORITY = PublicKey.fromBase58Encoded("Maybe11111111111111111111111111111111111111");
  private static final PublicKey PAYER = PublicKey.fromBase58Encoded("Payer11111111111111111111111111111111111111");
  private static final PublicKey THING = PublicKey.fromBase58Encoded("Thing11111111111111111111111111111111111111");

  private static final MappingContext CONTEXT = new MappingContext(STATE, VAULT, SIGNER);

  /// One entry: a caller-chosen signer at an unsigned seat, one at a signing seat, and a
  /// position the IDL fixes unsigned at an unsigned seat.
  private static InstructionMapper mapper() {
    final var json = """
        {
          "schema_version": 1, "environment": "test",
          "program_id": "%s", "proxy_program_id": "%s",
          "instructions": [{
            "name": "caller_decides", "discriminator": [2], "disposition": "map",
            "handler": { "name": "proxy_caller_decides", "discriminator": [8, 8] },
            "source_accounts": [
              { "name": "authority", "writable": false, "signer": false, "dynamic_signer": true },
              { "name": "payer", "writable": true, "signer": true, "dynamic_signer": true },
              { "name": "thing", "writable": false, "signer": false }
            ],
            "destination_accounts": [
              { "index": 0, "kind": "dynamic", "name": "glam_state", "writable": false, "signer": false },
              { "index": 1, "kind": "source", "source": 0, "writable": false, "signer": false },
              { "index": 2, "kind": "source", "source": 1, "writable": true, "signer": true },
              { "index": 3, "kind": "source", "source": 2, "writable": false, "signer": false }
            ],
            "remaining_accounts": { "kind": "none" }
          }]
        }
        """.formatted(PROGRAM.toBase58(), PROXY.toBase58());
    return InstructionMapper.createMapper(List.of(MappingDocumentParser.parse(json, "caller_decides")));
  }

  private static Instruction instruction(final AccountMeta authority, final AccountMeta payer, final AccountMeta thing) {
    return Instruction.createInstruction(PROGRAM, List.of(authority, payer, thing), new byte[]{2, 5});
  }

  /// At an unsigned seat the caller-chosen signer is seated signed when it signs and unsigned
  /// when it does not; its writable flag is the seat's, whatever the caller passed.
  @Test
  void anUnsignedSeatKeepsTheCallersSignerFlag() {
    final var mapper = mapper();
    for (final boolean signs : new boolean[]{true, false}) {
      final var result = mapper.map(instruction(
          AccountMeta.createMeta(AUTHORITY, true, signs),
          AccountMeta.createWritableSigner(PAYER),
          AccountMeta.createRead(THING)
      ), CONTEXT);
      final var mapped = assertInstanceOf(MapResult.Mapped.class, result, "authority signs: " + signs);
      assertEquals(List.of(
          AccountMeta.createRead(STATE),
          AccountMeta.createMeta(AUTHORITY, false, signs),
          AccountMeta.createWritableSigner(PAYER),
          AccountMeta.createRead(THING)
      ), mapped.instruction().accounts(), "authority signs: " + signs);
      assertArrayEquals(new byte[]{8, 8, 5}, mapped.instruction().data());
    }
  }

  /// At a signing seat the caller-chosen signer must sign: an unsigned one is refused with the
  /// contract's message, and a signing one is seated signed with the seat's writable flag.
  @Test
  void aSigningSeatRequiresTheCallersSignature() {
    final var mapper = mapper();
    final var unsigned = mapper.map(instruction(
        AccountMeta.createRead(AUTHORITY),
        AccountMeta.createWrite(PAYER),
        AccountMeta.createRead(THING)
    ), CONTEXT);
    final var refused = assertInstanceOf(MapResult.Unsupported.class, unsigned);
    assertEquals(UnsupportedReason.ACCOUNT_PRIVILEGE, refused.reason());
    assertEquals("caller_decides", refused.source());
    assertEquals("caller_decides account 1 (payer) must sign", refused.message());

    final var signing = mapper.map(instruction(
        AccountMeta.createRead(AUTHORITY),
        AccountMeta.createReadOnlySigner(PAYER),
        AccountMeta.createRead(THING)
    ), CONTEXT);
    final var mapped = assertInstanceOf(MapResult.Mapped.class, signing);
    assertEquals(List.of(
        AccountMeta.createRead(STATE),
        AccountMeta.createRead(AUTHORITY),
        AccountMeta.createWritableSigner(PAYER),
        AccountMeta.createRead(THING)
    ), mapped.instruction().accounts());
  }

  /// A position whose signer flag the IDL fixes takes the seat's flag and nothing else: a
  /// signing account at its unsigned seat is refused.
  @Test
  void aFixedPositionAtAnUnsignedSeatRefusesASigningAccount() {
    final var result = mapper().map(instruction(
        AccountMeta.createRead(AUTHORITY),
        AccountMeta.createWritableSigner(PAYER),
        AccountMeta.createReadOnlySigner(THING)
    ), CONTEXT);
    final var refused = assertInstanceOf(MapResult.Unsupported.class, result);
    assertEquals(UnsupportedReason.ACCOUNT_PRIVILEGE, refused.reason());
    assertEquals("caller_decides account 2 (thing) signs, but the handler takes it unsigned", refused.message());
  }
}
