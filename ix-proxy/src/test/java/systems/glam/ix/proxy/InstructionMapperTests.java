package systems.glam.ix.proxy;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// The mapper over a document set, and the transaction convenience: what the conformance
/// cases do not exercise.
final class InstructionMapperTests {

  private static final PublicKey PROGRAM = PublicKey.fromBase58Encoded("Src1111111111111111111111111111111111111111");
  private static final PublicKey PROXY = PublicKey.fromBase58Encoded("Proxy11111111111111111111111111111111111111");
  private static final PublicKey OTHER_PROGRAM = PublicKey.fromBase58Encoded("Other11111111111111111111111111111111111111".replace('O', 'A'));
  private static final PublicKey STATE = PublicKey.fromBase58Encoded("State11111111111111111111111111111111111111");
  private static final PublicKey VAULT = PublicKey.fromBase58Encoded("Vau1t11111111111111111111111111111111111111");
  private static final PublicKey SIGNER = PublicKey.fromBase58Encoded("Signer1111111111111111111111111111111111111");
  private static final PublicKey THING = PublicKey.fromBase58Encoded("Thing11111111111111111111111111111111111111");

  private static final MappingContext CONTEXT = new MappingContext(STATE, VAULT, SIGNER);

  /// A document of one mapped entry that forwards its position and every account beyond it.
  static MappingDocument relaying(final String environment, final PublicKey program) {
    final var json = """
        {
          "schema_version": 1,
          "environment": "%s",
          "program_id": "%s",
          "proxy_program_id": "%s",
          "instructions": [
            {
              "name": "relay",
              "discriminator": [1],
              "disposition": "map",
              "handler": { "name": "proxy_relay", "discriminator": [9, 9, 9, 9, 9, 9, 9, 9] },
              "source_accounts": [{ "name": "thing", "writable": true, "signer": false }],
              "destination_accounts": [
                { "index": 0, "kind": "dynamic", "name": "glam_state", "writable": false, "signer": false },
                { "index": 1, "kind": "source", "source": 0, "writable": true, "signer": false }
              ],
              "remaining_accounts": { "kind": "any" }
            },
            { "name": "refused", "discriminator": [2], "disposition": "unsupported", "reason": "no handler" }
          ]
        }
        """.formatted(environment, program.toBase58(), PROXY.toBase58());
    return MappingDocumentParser.parse(json, "relaying");
  }

  private static Instruction relay(final PublicKey program, final byte... data) {
    return Instruction.createInstruction(program, List.of(AccountMeta.createWrite(THING)), data);
  }

  @Test
  void refusesAnEmptySet() {
    final var e = assertThrows(MappingDocumentException.class, () -> InstructionMapper.createMapper(List.of()));
    assertEquals("mapper: at least one mapping document is required", e.getMessage());
  }

  @Test
  void refusesANullDocument() {
    final var e = assertThrows(MappingDocumentException.class, () -> InstructionMapper.createMapper(java.util.Collections.singletonList(null)));
    assertEquals("mapper: a document is null", e.getMessage());
  }

  @Test
  void refusesTwoEnvironments() {
    final var e = assertThrows(MappingDocumentException.class, () -> InstructionMapper.createMapper(List.of(
        relaying("production", PROGRAM), relaying("staging", OTHER_PROGRAM)
    )));
    assertEquals(OTHER_PROGRAM.toBase58() + ": declares environment staging; the mapper's is production", e.getMessage());
  }

  @Test
  void refusesTwoDocumentsForOneProgram() {
    final var e = assertThrows(MappingDocumentException.class, () -> InstructionMapper.createMapper(List.of(
        relaying("test", PROGRAM), relaying("test", PROGRAM)
    )));
    assertEquals(PROGRAM.toBase58() + ": two documents for one program", e.getMessage());
  }

  @Test
  void holdsOneEnvironmentAndOneDocumentPerProgram() {
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM), relaying("test", OTHER_PROGRAM)));
    assertEquals("test", mapper.environment());
    assertEquals(2, mapper.documents().size());
    assertEquals(PROGRAM, mapper.documentOf(PROGRAM).programId());
    assertNull(mapper.documentOf(PROXY));
  }

  @Test
  void anUndocumentedProgramPassesThroughAsTheCallersOwnObject() {
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM)));
    final var instruction = relay(OTHER_PROGRAM, (byte) 1);
    final var result = assertInstanceOf(MapResult.Passthrough.class, mapper.map(instruction, CONTEXT));
    assertSame(instruction, result.instruction());
    assertNull(result.source());
    assertEquals("the program has no mapping document", result.reason());
  }

  @Test
  void mapsEveryInstructionInOrder() {
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM)));
    final var results = mapper.map(List.of(relay(PROGRAM, (byte) 1, (byte) 7), relay(PROGRAM, (byte) 2), relay(OTHER_PROGRAM, (byte) 5)), CONTEXT);
    assertEquals(3, results.size());
    final var mapped = assertInstanceOf(MapResult.Mapped.class, results.get(0));
    assertEquals(PROXY, mapped.instruction().programId().publicKey());
    assertEquals("proxy_relay", mapped.handler());
    assertArrayEquals(new byte[]{9, 9, 9, 9, 9, 9, 9, 9, 7}, mapped.instruction().data());
    assertEquals(List.of(AccountMeta.createRead(STATE), AccountMeta.createWrite(THING)), mapped.instruction().accounts());
    final var refused = assertInstanceOf(MapResult.Unsupported.class, results.get(1));
    assertEquals(UnsupportedReason.REFUSED_INSTRUCTION, refused.reason());
    assertEquals("refused", refused.source());
    assertInstanceOf(MapResult.Passthrough.class, results.get(2));
  }

  @Test
  void mapTransactionKeepsTheFeePayerAndCarriesEveryMappedInstruction() {
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM)));
    final var feePayer = AccountMeta.createFeePayer(SIGNER);
    final var transaction = Transaction.createTx(feePayer, List.of(relay(PROGRAM, (byte) 1), relay(OTHER_PROGRAM, (byte) 5)));
    final var mapped = mapper.mapTransaction(transaction, CONTEXT);
    assertEquals(SIGNER, mapped.feePayer().publicKey());
    assertEquals(2, mapped.instructions().size());
    assertEquals(PROXY, mapped.instructions().get(0).programId().publicKey());
    assertEquals(OTHER_PROGRAM, mapped.instructions().get(1).programId().publicKey());
  }

  @Test
  void mapTransactionThrowsAtTheFirstRefusalNamingItsPosition() {
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM)));
    final var transaction = Transaction.createTx(AccountMeta.createFeePayer(SIGNER), List.of(relay(PROGRAM, (byte) 1), relay(PROGRAM, (byte) 2)));
    final var e = assertThrows(UnsupportedInstructionException.class, () -> mapper.mapTransaction(transaction, CONTEXT));
    assertEquals(1, e.index());
    assertEquals(UnsupportedReason.REFUSED_INSTRUCTION, e.result().reason());
    assertEquals("no handler", e.result().message());
    assertSame(transaction.instructions().get(1), e.instruction());
    assertTrue(e.getMessage().contains("instruction 1 of " + PROGRAM.toBase58()), e.getMessage());
  }

  private static software.sava.core.accounts.lookup.AddressLookupTable table(final PublicKey address, final PublicKey... addresses) {
    // an active table: discriminator 1, no deactivation slot, no authority, then the addresses
    final byte[] data = new byte[software.sava.core.accounts.lookup.AddressLookupTable.LOOKUP_TABLE_META_SIZE + 32 * addresses.length];
    software.sava.core.encoding.ByteUtil.putInt32LE(data, 0, 1);
    software.sava.core.encoding.ByteUtil.putInt64LE(data, 4, -1L);
    for (int i = 0; i < addresses.length; i++) {
      addresses[i].write(data, software.sava.core.accounts.lookup.AddressLookupTable.LOOKUP_TABLE_META_SIZE + 32 * i);
    }
    return software.sava.core.accounts.lookup.AddressLookupTable.FACTORY.apply(address, data);
  }

  /// A transaction's lookup table rides along: the mapped transaction carries the same one.
  @Test
  void mapTransactionKeepsTheLookupTable() {
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM)));
    final var table = table(PROXY, THING, STATE);
    final var transaction = Transaction.createTx(AccountMeta.createFeePayer(SIGNER), List.of(relay(PROGRAM, (byte) 1)), table);
    final var mapped = mapper.mapTransaction(transaction, CONTEXT);
    assertEquals(PROXY, mapped.instructions().getFirst().programId().publicKey());
    assertArrayEquals(
        Transaction.createTx(AccountMeta.createFeePayer(SIGNER), mapped.instructions(), table).serialized(),
        mapped.serialized(),
        "the mapped transaction is the one a caller would build over the same table"
    );
    assertNotEquals(
        Transaction.createTx(AccountMeta.createFeePayer(SIGNER), mapped.instructions()).size(),
        mapped.size(),
        "the table is in the mapped transaction"
    );
  }

  /// An expectation the context cannot resolve refuses the instruction rather than escaping:
  /// a position expected to be the integration authority with no lookup, or a failing one.
  @Test
  void anExpectationTheContextCannotResolveIsARefusal() {
    final var json = """
        {
          "schema_version": 1, "environment": "test",
          "program_id": "%s", "proxy_program_id": "%s",
          "instructions": [{
            "name": "auth", "discriminator": [1], "disposition": "map",
            "handler": { "name": "proxy_auth", "discriminator": [9] },
            "source_accounts": [{ "name": "authority", "writable": false, "signer": false, "expect": "integration_authority" }],
            "destination_accounts": [
              { "index": 0, "kind": "source", "source": 0, "writable": false, "signer": false }
            ]
          }]
        }
        """.formatted(PROGRAM.toBase58(), PROXY.toBase58());
    final var mapper = InstructionMapper.createMapper(List.of(MappingDocumentParser.parse(json, "auth")));
    final var instruction = Instruction.createInstruction(PROGRAM, List.of(AccountMeta.createRead(THING)), new byte[]{1});

    final var absent = assertInstanceOf(MapResult.Unsupported.class, mapper.map(instruction, CONTEXT));
    assertEquals(UnsupportedReason.CONTEXT, absent.reason());
    assertEquals("the context supplies no integration_authority for " + PROXY.toBase58(), absent.message());

    final var throwing = new MappingContext(STATE, VAULT, SIGNER, proxy -> {
      throw new IllegalStateException("boom");
    });
    final var failed = assertInstanceOf(MapResult.Unsupported.class, mapper.map(instruction, throwing));
    assertEquals("the context's integration authority failed for " + PROXY.toBase58() + ": boom", failed.message());

    final var supplied = new MappingContext(STATE, VAULT, SIGNER, Map.of(PROXY, THING)::get);
    final var mapped = assertInstanceOf(MapResult.Mapped.class, mapper.map(instruction, supplied));
    assertEquals(List.of(AccountMeta.createRead(THING)), mapped.instruction().accounts());
    final var other = new MappingContext(STATE, VAULT, SIGNER, Map.of(PROXY, STATE)::get);
    final var refused = assertInstanceOf(MapResult.Unsupported.class, mapper.map(instruction, other));
    assertEquals(UnsupportedReason.ACCOUNT_EXPECTATION, refused.reason());
    assertEquals("auth account 0 (authority) must be integration_authority", refused.message());
  }

  @Test
  void aThrowingIntegrationAuthorityIsARefusalNotAnEscape() {
    final var json = """
        {
          "schema_version": 1, "environment": "test",
          "program_id": "%s", "proxy_program_id": "%s",
          "instructions": [{
            "name": "auth", "discriminator": [1], "disposition": "map",
            "handler": { "name": "proxy_auth", "discriminator": [9] },
            "source_accounts": [],
            "destination_accounts": [
              { "index": 0, "kind": "dynamic", "name": "integration_authority", "writable": false, "signer": false }
            ]
          }]
        }
        """.formatted(PROGRAM.toBase58(), PROXY.toBase58());
    final var mapper = InstructionMapper.createMapper(List.of(MappingDocumentParser.parse(json, "auth")));
    final var instruction = Instruction.createInstruction(PROGRAM, List.of(), new byte[]{1});

    final var absent = assertInstanceOf(MapResult.Unsupported.class, mapper.map(instruction, CONTEXT));
    assertEquals(UnsupportedReason.CONTEXT, absent.reason());
    assertEquals("the context supplies no integration_authority for " + PROXY.toBase58(), absent.message());

    final var throwing = new MappingContext(STATE, VAULT, SIGNER, proxy -> {
      throw new IllegalStateException("boom");
    });
    final var failed = assertInstanceOf(MapResult.Unsupported.class, mapper.map(instruction, throwing));
    assertEquals(UnsupportedReason.CONTEXT, failed.reason());
    assertEquals("the context's integration authority failed for " + PROXY.toBase58() + ": boom", failed.message());

    final var unnamed = new MappingContext(STATE, VAULT, SIGNER, proxy -> {
      throw new IllegalStateException();
    });
    final var failedWithoutAMessage = assertInstanceOf(MapResult.Unsupported.class, mapper.map(instruction, unnamed));
    assertEquals("the context's integration authority failed for " + PROXY.toBase58() + ": java.lang.IllegalStateException", failedWithoutAMessage.message());

    final var unknown = new MappingContext(STATE, VAULT, SIGNER, proxy -> null);
    final var missing = assertInstanceOf(MapResult.Unsupported.class, mapper.map(instruction, unknown));
    assertEquals(UnsupportedReason.CONTEXT, missing.reason());
    assertEquals("the context supplies no integration_authority for " + PROXY.toBase58(), missing.message());

    final var supplied = new MappingContext(STATE, VAULT, SIGNER, Map.of(PROXY, THING)::get);
    final var mapped = assertInstanceOf(MapResult.Mapped.class, mapper.map(instruction, supplied));
    assertEquals(List.of(AccountMeta.createRead(THING)), mapped.instruction().accounts());
  }

  /// A transaction without tables maps to one without tables, and serializes.
  @Test
  void mapTransactionWithoutTablesSerializes() {
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM)));
    final var transaction = Transaction.createTx(AccountMeta.createFeePayer(SIGNER), List.of(relay(PROGRAM, (byte) 1)));
    final var mapped = mapper.mapTransaction(transaction, CONTEXT);
    assertNull(mapped.lookupTable());
    assertTrue(mapped.tableAccountMetas() == null || mapped.tableAccountMetas().length == 0);
    assertTrue(mapped.serialized().length > 0);
  }

  /// A transaction's table account metas ride along, as its single table does: two tables
  /// stay two. (A table that indexes no account of the transaction is dropped by the
  /// transaction itself, and one table is the single-table form, whichever constructor built
  /// it, so each table here covers an account of the instruction.)
  @Test
  void mapTransactionKeepsTheTableAccountMetas() {
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM)));
    final var tables = software.sava.core.accounts.meta.LookupTableAccountMeta.createMetas(List.of(table(PROXY, THING), table(VAULT, STATE)));
    final var instruction = Instruction.createInstruction(PROGRAM, List.of(AccountMeta.createWrite(THING), AccountMeta.createRead(STATE)), new byte[]{1});
    final var transaction = Transaction.createTx(AccountMeta.createFeePayer(SIGNER), List.of(instruction), tables);
    assertEquals(2, transaction.tableAccountMetas().length, "both tables index an account of the source transaction");
    final var mapped = mapper.mapTransaction(transaction, CONTEXT);
    assertArrayEquals(tables, mapped.tableAccountMetas());
    assertEquals(PROXY, mapped.instructions().getFirst().programId().publicKey());
    assertArrayEquals(
        Transaction.createTx(AccountMeta.createFeePayer(SIGNER), mapped.instructions(), tables).serialized(),
        mapped.serialized(),
        "the mapped transaction is the one a caller would build over the same tables"
    );
  }

  /// A document built from records takes the parser's checks across entries at creation: a
  /// seat after one a client may leave out, or a discriminator that is a prefix of another's,
  /// forms no mapper. (Each record checks its own fields when it is built: RecordsTests.)
  @Test
  void aDocumentBuiltFromRecordsIsCheckedAtCreation() {
    final var shifted = new InstructionEntry.Mapped(
        "shifted",
        software.sava.core.programs.Discriminator.createDiscriminator(new byte[]{1}),
        new Handler("proxy_shifted", software.sava.core.programs.Discriminator.createDiscriminator(new byte[]{9})),
        List.of(
            new SourceAccount("thing", true, false, false, null, null),
            new SourceAccount("trailing", false, false, false, OptionalKind.OMITTED, null)
        ),
        List.of(
            new DestinationAccount.Source(0, 1, false, false, false),
            new DestinationAccount.Dynamic(1, DynamicAccountName.GLAM_STATE, false, false)
        ),
        RemainingAccounts.ANY
    );
    final var e = assertThrows(MappingDocumentException.class, () -> InstructionMapper.createMapper(List.of(
        new MappingDocument(1, "test", PROGRAM, PROXY, null, List.of(shifted)))));
    assertTrue(e.getMessage().contains("follows a seat a client may leave out"), e.getMessage());
    assertEquals(PROGRAM.toBase58() + " instructions[0]", e.at());

    final var shadowed = new InstructionEntry.Passthrough("short", software.sava.core.programs.Discriminator.createDiscriminator(new byte[]{1}), "r");
    final var longer = new InstructionEntry.Passthrough("long", software.sava.core.programs.Discriminator.createDiscriminator(new byte[]{1, 2}), "r");
    final var shadow = assertThrows(MappingDocumentException.class, () -> InstructionMapper.createMapper(List.of(
        new MappingDocument(1, "test", PROGRAM, PROXY, null, List.of(shadowed, longer)))));
    assertTrue(shadow.getMessage().contains("is a prefix of long's"), shadow.getMessage());
  }

  /// An instruction whose data span lies outside its buffer, as a transaction whose last
  /// instruction declares more data than the buffer holds deserializes to, is refused,
  /// whatever its program: one with no document does not pass through unread.
  @Test
  void aDataSpanOutsideTheBufferIsRefused() {
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM)));
    final byte[] data = {1, 7};
    final var accounts = List.of(AccountMeta.createWrite(THING));
    for (final int[] span : new int[][]{{3, 1}, {-1, 2}, {0, 10}, {0, Integer.MAX_VALUE}, {1, -1}, {2, 1}}) {
      for (final var program : List.of(PROGRAM, OTHER_PROGRAM)) {
        final var instruction = Instruction.createInstruction(program, accounts, data, span[0], span[1]);
        final var refused = assertInstanceOf(MapResult.Unsupported.class, mapper.map(instruction, CONTEXT), java.util.Arrays.toString(span));
        assertEquals(UnsupportedReason.UNREADABLE_INSTRUCTION, refused.reason(), java.util.Arrays.toString(span));
        assertTrue(refused.message().contains("lies outside its buffer of 2 bytes"), refused.message());
        assertEquals(program, refused.program());
        assertNull(refused.source());
      }
    }
    // a span inside the buffer maps from its own offset and length, whatever lies around it
    final byte[] padded = {0, 0, 1, 7, 5, 5};
    final var inside = Instruction.createInstruction(PROGRAM, accounts, padded, 2, 2);
    final var mapped = assertInstanceOf(MapResult.Mapped.class, mapper.map(inside, CONTEXT));
    assertArrayEquals(new byte[]{9, 9, 9, 9, 9, 9, 9, 9, 7}, mapped.instruction().data());
    final var exact = assertInstanceOf(MapResult.Mapped.class, mapper.map(relay(PROGRAM, (byte) 1, (byte) 7), CONTEXT));
    assertArrayEquals(mapped.instruction().data(), exact.instruction().data());
    assertEquals(mapped.instruction().accounts(), exact.instruction().accounts());
  }

  /// An account a transaction did not resolve (a lookup-table account, left null by the
  /// skeleton) is refused rather than dereferenced.
  @Test
  void anUnresolvedAccountIsRefused() {
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM)));
    final var accounts = new java.util.ArrayList<AccountMeta>();
    accounts.add(null);
    for (final var program : List.of(PROGRAM, OTHER_PROGRAM)) {
      final var instruction = Instruction.createInstruction(program, accounts, new byte[]{1});
      final var refused = assertInstanceOf(MapResult.Unsupported.class, mapper.map(instruction, CONTEXT));
      assertEquals(UnsupportedReason.UNREADABLE_INSTRUCTION, refused.reason());
      assertEquals("account 0 is unresolved; a lookup-table account must be loaded before mapping", refused.message());
      assertEquals(program, refused.program());
    }
    // the deserialized form of a v0 transaction holds its table accounts as null until resolved
    final var table = table(PROXY, THING);
    final var v0 = Transaction.createTx(AccountMeta.createFeePayer(SIGNER), List.of(relay(PROGRAM, (byte) 1)), table);
    final var unresolved = software.sava.core.tx.TransactionSkeleton.deserializeSkeleton(v0.serialized()).createTransaction();
    assertNull(unresolved.instructions().getFirst().accounts().getFirst(), "the table account is unresolved");
    final var e = assertThrows(UnsupportedInstructionException.class, () -> mapper.mapTransaction(unresolved, CONTEXT));
    assertEquals(UnsupportedReason.UNREADABLE_INSTRUCTION, e.result().reason());
  }

  /// A transaction is mapped whole before it is rebuilt: an unreadable instruction after a
  /// mapped or passed-through one is refused at its position rather than handed to the
  /// rebuild, which could not serialize it.
  @Test
  void mapTransactionRefusesAnUnreadableInstructionBeforeRebuilding() {
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM)));
    final var table = table(PROXY, VAULT);
    final var viaTable = Instruction.createInstruction(OTHER_PROGRAM, List.of(AccountMeta.createRead(VAULT)), new byte[]{5});
    for (final var first : List.of(relay(PROGRAM, (byte) 1), relay(OTHER_PROGRAM, (byte) 5))) {
      final var v0 = Transaction.createTx(AccountMeta.createFeePayer(SIGNER), List.of(first, viaTable), table);
      assertEquals(0, v0.version());
      final var unresolved = software.sava.core.tx.TransactionSkeleton.deserializeSkeleton(v0.serialized()).createTransaction();
      assertNotNull(unresolved.instructions().get(0).accounts().getFirst(), "the static account is resolved");
      assertNull(unresolved.instructions().get(1).accounts().getFirst(), "the table account is unresolved");
      final var e = assertThrows(UnsupportedInstructionException.class, () -> mapper.mapTransaction(unresolved, CONTEXT));
      assertEquals(1, e.index());
      assertEquals(UnsupportedReason.UNREADABLE_INSTRUCTION, e.result().reason());
      assertSame(unresolved.instructions().get(1), e.instruction());
    }
  }

  /// A transaction from the wire whose last instruction declares more data than the bytes
  /// hold deserializes to an instruction with a span outside its buffer; mapTransaction
  /// refuses it at its position rather than handing it to the rebuild.
  @Test
  void mapTransactionRefusesADataSpanOutsideTheBufferFromTheWire() {
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM)));
    final var whole = Transaction.createTx(AccountMeta.createFeePayer(SIGNER), List.of(relay(PROGRAM, (byte) 1), relay(OTHER_PROGRAM, (byte) 5, (byte) 6, (byte) 7))).serialized();
    final var truncated = software.sava.core.tx.TransactionSkeleton.deserializeSkeleton(java.util.Arrays.copyOf(whole, whole.length - 2)).createTransaction();
    final var last = truncated.instructions().get(1);
    assertTrue(last.offset() + last.len() > last.data().length, "the last instruction's span runs past the bytes");
    final var e = assertThrows(UnsupportedInstructionException.class, () -> mapper.mapTransaction(truncated, CONTEXT));
    assertEquals(1, e.index());
    assertEquals(UnsupportedReason.UNREADABLE_INSTRUCTION, e.result().reason());
  }

  /// The rebuild is sava's: a v1 transaction that the seats a mapping adds push past 64
  /// accounts cannot be formed, and sava's own exception says so.
  @Test
  void mapTransactionLetsSavaRefuseATransactionTheMappedInstructionsCannotForm() {
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM)));
    // fee payer, PROGRAM, THING, OTHER_PROGRAM and 60 fillers: 64 accounts, the v1 limit;
    // mapping relay adds the proxy program and the state, one more than PROGRAM releases
    final var fillers = new java.util.ArrayList<AccountMeta>();
    for (int i = 0; i < 60; i++) {
      final byte[] key = new byte[32];
      key[0] = (byte) 0x7f;
      key[1] = (byte) i;
      fillers.add(AccountMeta.createRead(PublicKey.createPubKey(key)));
    }
    final var builder = software.sava.core.tx.TxBuilder.createBuilder()
        .feePayer(AccountMeta.createFeePayer(SIGNER))
        .addInstruction(relay(PROGRAM, (byte) 1))
        .addInstruction(Instruction.createInstruction(OTHER_PROGRAM, fillers, new byte[]{5}));
    final var source = builder.createTransaction();
    assertEquals(1, source.version());
    final var e = assertThrows(IllegalStateException.class, () -> mapper.mapTransaction(source, CONTEXT));
    assertTrue(e.getMessage().contains("64 accounts"), e.getMessage());
    assertTrue(e.getStackTrace()[0].getClassName().startsWith("software.sava."), "thrown by sava itself: " + e.getStackTrace()[0]);
  }

  /// Each mapped instruction is replaced in its own rebuild, and sava checks every
  /// intermediate: with two instructions of one program, the first replacement references the
  /// source program and its proxy together, so a v1 transaction whose fully mapped form fits
  /// 64 accounts exactly is refused on the way there.
  @Test
  void mapTransactionChecksEachIntermediateRebuild() {
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM)));
    // fee payer, PROGRAM, THING, OTHER_PROGRAM and 59 fillers: 63 accounts; fully mapped, the
    // two relays trade PROGRAM for the proxy program and the state: 64, which sava accepts
    final var fillers = new java.util.ArrayList<AccountMeta>();
    for (int i = 0; i < 59; i++) {
      final byte[] key = new byte[32];
      key[0] = (byte) 0x7e;
      key[1] = (byte) i;
      fillers.add(AccountMeta.createRead(PublicKey.createPubKey(key)));
    }
    final var filler = Instruction.createInstruction(OTHER_PROGRAM, fillers, new byte[]{5});
    final var source = software.sava.core.tx.TxBuilder.createBuilder()
        .feePayer(AccountMeta.createFeePayer(SIGNER))
        .addInstruction(relay(PROGRAM, (byte) 1))
        .addInstruction(relay(PROGRAM, (byte) 1, (byte) 2))
        .addInstruction(filler)
        .createTransaction();
    final var mapped = mapper.map(source.instructions(), CONTEXT);
    final var whole = software.sava.core.tx.TxBuilder.createBuilder()
        .feePayer(AccountMeta.createFeePayer(SIGNER))
        .addInstruction(((MapResult.Mapped) mapped.get(0)).instruction())
        .addInstruction(((MapResult.Mapped) mapped.get(1)).instruction())
        .addInstruction(filler)
        .createTransaction();
    assertEquals(1, whole.version(), "the fully mapped transaction builds: 64 accounts");
    final var e = assertThrows(IllegalStateException.class, () -> mapper.mapTransaction(source, CONTEXT));
    assertTrue(e.getMessage().contains("64 accounts"), e.getMessage());
  }

  /// The rebuilt transaction keeps the recent blockhash and carries no signatures; one in
  /// which nothing mapped is the caller's own object.
  @Test
  void mapTransactionReturnsAnUnsignedRebuildOrTheCallersOwnObject() {
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM)));
    final var signer = software.sava.core.accounts.Signer.createFromPrivateKey(new byte[]{
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
    final byte[] blockhash = new byte[32];
    for (int i = 0; i < blockhash.length; i++) {
      blockhash[i] = (byte) (0x5a + i);
    }
    final var untouched = Transaction.createTx(AccountMeta.createFeePayer(signer.publicKey()), List.of(relay(OTHER_PROGRAM, (byte) 5)));
    untouched.setRecentBlockHash(blockhash);
    untouched.sign(signer);
    assertSame(untouched, mapper.mapTransaction(untouched, CONTEXT));
    final var source = Transaction.createTx(AccountMeta.createFeePayer(signer.publicKey()), List.of(relay(PROGRAM, (byte) 1)));
    source.setRecentBlockHash(blockhash);
    source.sign(signer);
    final var rebuilt = mapper.mapTransaction(source, CONTEXT);
    assertNotSame(source, rebuilt);
    assertArrayEquals(blockhash, rebuilt.recentBlockHash(), "the rebuild keeps the recent blockhash");
    // one signature slot each (the count is the first byte); the rebuilt one is empty
    final byte[] rebuiltBytes = rebuilt.serialized();
    final byte[] sourceBytes = source.serialized();
    assertEquals(1, rebuiltBytes[0]);
    assertArrayEquals(new byte[64], java.util.Arrays.copyOfRange(rebuiltBytes, 1, 65), "no signature on the rebuilt transaction");
    assertFalse(java.util.Arrays.equals(new byte[64], java.util.Arrays.copyOfRange(sourceBytes, 1, 65)), "the source keeps its signature");
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> RuntimeException sneaky(final Throwable t) throws E {
    throw (E) t;
  }

  /// A checked exception thrown sneakily by the lookup is a refusal like any other exception.
  @Test
  void aSneakyCheckedExceptionFromTheLookupIsARefusal() {
    final var json = """
        {
          "schema_version": 1, "environment": "test",
          "program_id": "%s", "proxy_program_id": "%s",
          "instructions": [{
            "name": "auth", "discriminator": [1], "disposition": "map",
            "handler": { "name": "proxy_auth", "discriminator": [9] },
            "source_accounts": [],
            "destination_accounts": [
              { "index": 0, "kind": "dynamic", "name": "integration_authority", "writable": false, "signer": false }
            ]
          }]
        }
        """.formatted(PROGRAM.toBase58(), PROXY.toBase58());
    final var mapper = InstructionMapper.createMapper(List.of(MappingDocumentParser.parse(json, "auth")));
    final var instruction = Instruction.createInstruction(PROGRAM, List.of(), new byte[]{1});
    final var sneaky = new MappingContext(STATE, VAULT, SIGNER, proxy -> {
      throw sneaky(new java.io.IOException("io"));
    });
    final var refused = assertInstanceOf(MapResult.Unsupported.class, mapper.map(instruction, sneaky));
    assertEquals(UnsupportedReason.CONTEXT, refused.reason());
    assertEquals("the context's integration authority failed for " + PROXY.toBase58() + ": io", refused.message());
    // an interruption is a refusal too, and the thread keeps its interrupt flag
    final var interrupting = new MappingContext(STATE, VAULT, SIGNER, proxy -> {
      throw sneaky(new InterruptedException("interrupted"));
    });
    assertFalse(Thread.currentThread().isInterrupted());
    final var interrupted = assertInstanceOf(MapResult.Unsupported.class, mapper.map(instruction, interrupting));
    assertTrue(Thread.interrupted(), "the interrupt flag is set again for the caller");
    assertEquals(UnsupportedReason.CONTEXT, interrupted.reason());
    assertEquals("the context's integration authority failed for " + PROXY.toBase58() + ": interrupted", interrupted.message());
  }

  /// A v1 transaction is rebuilt as a v1 transaction with its settings: the mapped
  /// transaction serializes exactly as the caller would have built it over the mapped
  /// instruction.
  @Test
  void mapTransactionKeepsAV1TransactionAndItsSettings() {
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM)));
    final var source = software.sava.core.tx.TxBuilder.createBuilder()
        .feePayer(AccountMeta.createFeePayer(SIGNER))
        .addInstruction(relay(PROGRAM, (byte) 1, (byte) 7))
        .addInstruction(relay(OTHER_PROGRAM, (byte) 5))
        .priorityFeeLamports(12_345L)
        .computeUnitLimit(200_000)
        .heapSize(65_536)
        .createTransaction();
    assertEquals(1, source.version());
    final var mapped = mapper.mapTransaction(source, CONTEXT);
    assertEquals(1, mapped.version());
    final var mappedInstruction = assertInstanceOf(MapResult.Mapped.class, mapper.map(relay(PROGRAM, (byte) 1, (byte) 7), CONTEXT)).instruction();
    final var expected = software.sava.core.tx.TxBuilder.createBuilder()
        .feePayer(AccountMeta.createFeePayer(SIGNER))
        .addInstruction(mappedInstruction)
        .addInstruction(relay(OTHER_PROGRAM, (byte) 5))
        .priorityFeeLamports(12_345L)
        .computeUnitLimit(200_000)
        .heapSize(65_536)
        .createTransaction();
    assertArrayEquals(expected.serialized(), mapped.serialized());
    assertNotEquals(
        software.sava.core.tx.TxBuilder.createBuilder().feePayer(AccountMeta.createFeePayer(SIGNER))
            .addInstruction(mappedInstruction).addInstruction(relay(OTHER_PROGRAM, (byte) 5)).createTransaction().size(),
        mapped.size(),
        "the settings are in the mapped transaction"
    );
  }
}
