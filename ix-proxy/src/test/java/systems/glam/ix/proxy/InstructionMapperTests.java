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

    // one records-built entry per rule over supplied accounts
    final var thing = new SourceAccount("thing", true, false, false, null, null);
    final var trailing = new SourceAccount("trailing", false, false, false, OptionalKind.OMITTED, null);
    final var maybe = new SourceAccount("maybe", false, false, false, OptionalKind.PROGRAM_ID, null);
    final var seatZero = new DestinationAccount.Source(0, 0, true, false, false);
    final var supplied = List.of(
        java.util.Map.entry(
            withSupplied(List.of(thing), List.of(seatZero), new SuppliedAccount("r", List.of(3), false)),
            "supplied_accounts[0] names source position 3, which is out of range of 1"),
        java.util.Map.entry(
            withSupplied(List.of(thing, trailing), List.of(seatZero), new SuppliedAccount("r", List.of(1), false)),
            "supplied_accounts[0] names source position 1, which a client may leave out; an absent run would shift it"),
        java.util.Map.entry(
            withSupplied(List.of(thing, maybe), List.of(seatZero), new SuppliedAccount("r", List.of(1), false)),
            "supplied_accounts[0] names source position 1, which a client may pass as the program id; an absent optional names no account"),
        java.util.Map.entry(
            withSupplied(List.of(thing), List.of(seatZero), new SuppliedAccount("a", List.of(), true), new SuppliedAccount("b", List.of(), false)),
            "supplied_accounts[1] is required after an optional one; optional accounts trail"),
        java.util.Map.entry(
            withSupplied(List.of(thing, trailing), List.of(seatZero, new DestinationAccount.Source(1, 1, false, false, false)), new SuppliedAccount("r", List.of(), false)),
            "supplied_accounts follow seat 1, which a client may leave out; an absent one would shift them")
    );
    for (final var row : supplied) {
      final var refused = assertThrows(MappingDocumentException.class, () -> InstructionMapper.createMapper(List.of(
          new MappingDocument(1, "test", PROGRAM, PROXY, null, List.of(row.getKey())))), row.getValue());
      assertTrue(refused.getMessage().contains(row.getValue()), refused.getMessage());
      assertEquals(PROGRAM.toBase58() + " instructions[0]", refused.at());
    }
    // the same entries with the rule satisfied form a mapper
    InstructionMapper.createMapper(List.of(new MappingDocument(1, "test", PROGRAM, PROXY, null, List.of(
        withSupplied(List.of(thing, maybe), List.of(seatZero), new SuppliedAccount("r", List.of(0), false), new SuppliedAccount("s", List.of(), true))))));

    final var shadowed = new InstructionEntry.Passthrough("short", software.sava.core.programs.Discriminator.createDiscriminator(new byte[]{1}), "r");
    final var longer = new InstructionEntry.Passthrough("long", software.sava.core.programs.Discriminator.createDiscriminator(new byte[]{1, 2}), "r");
    final var shadow = assertThrows(MappingDocumentException.class, () -> InstructionMapper.createMapper(List.of(
        new MappingDocument(1, "test", PROGRAM, PROXY, null, List.of(shadowed, longer)))));
    assertTrue(shadow.getMessage().contains("is a prefix of long's"), shadow.getMessage());
  }

  private static InstructionEntry.Mapped withSupplied(final List<SourceAccount> sources,
                                                      final List<DestinationAccount> seats,
                                                      final SuppliedAccount... supplied) {
    return new InstructionEntry.Mapped(
        "place",
        software.sava.core.programs.Discriminator.createDiscriminator(new byte[]{7}),
        new Handler("proxy_place", software.sava.core.programs.Discriminator.createDiscriminator(new byte[]{9})),
        sources,
        seats,
        RemainingAccounts.ANY,
        List.of(supplied)
    );
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

  private static final String SUPPLIED_DOCUMENT = """
      {
        "schema_version": 1, "environment": "test",
        "program_id": "%s", "proxy_program_id": "%s",
        "instructions": [{
          "name": "place", "discriminator": [7], "disposition": "map",
          "handler": { "name": "proxy_place", "discriminator": [9, 9] },
          "source_accounts": [
            { "name": "mint_a", "writable": false, "signer": false },
            { "name": "mint_b", "writable": false, "signer": false }
          ],
          "destination_accounts": [
            { "index": 0, "kind": "dynamic", "name": "glam_vault", "writable": true, "signer": false },
            { "index": 1, "kind": "source", "source": 0, "writable": false, "signer": false },
            { "index": 2, "kind": "source", "source": 1, "writable": false, "signer": false }
          ],
          "supplied_accounts": [
            { "role": "asset_oracle", "of": [0] },
            { "role": "asset_oracle", "of": [1] },
            { "role": "sol_usd_oracle", "optional": true }
          ]
        }]
      }
      """;
  private static final PublicKey MINT_A = PublicKey.fromBase58Encoded("MintA11111111111111111111111111111111111111");
  private static final PublicKey MINT_B = PublicKey.fromBase58Encoded("MintB11111111111111111111111111111111111111");
  private static final PublicKey PRICE_A = PublicKey.fromBase58Encoded("Price1A111111111111111111111111111111111111");
  private static final PublicKey PRICE_B = PublicKey.fromBase58Encoded("Price1B111111111111111111111111111111111111");
  private static final PublicKey SOL_USD = PublicKey.fromBase58Encoded("SoLUsd1111111111111111111111111111111111111");

  private static InstructionMapper suppliedMapper() {
    return InstructionMapper.createMapper(List.of(MappingDocumentParser.parse(
        SUPPLIED_DOCUMENT.formatted(PROGRAM.toBase58(), PROXY.toBase58()), "place"
    )));
  }

  private static Instruction placeInstruction() {
    return Instruction.createInstruction(
        PROGRAM,
        List.of(AccountMeta.createRead(MINT_A), AccountMeta.createRead(MINT_B), AccountMeta.createWrite(THING)),
        new byte[]{7, 5, 6}
    );
  }

  /// The supplier is asked once, with the roles and the addresses at their `of` positions, and
  /// its answer sits after the seats and before the accounts beyond the list, read-only and
  /// unsigned, whatever the caller supplied.
  @Test
  void suppliedAccountsRideAfterTheSeatsAndTheRequestNamesTheRoles() {
    final var requests = new java.util.ArrayList<SuppliedAccountsRequest>();
    final var instruction = placeInstruction();
    final var context = new MappingContext(STATE, VAULT, SIGNER, null, request -> {
      requests.add(request);
      return List.of(PRICE_A, PRICE_B, SOL_USD);
    });
    final var mapped = assertInstanceOf(MapResult.Mapped.class, suppliedMapper().map(instruction, context));
    assertEquals(List.of(
        AccountMeta.createWrite(VAULT),
        AccountMeta.createRead(MINT_A),
        AccountMeta.createRead(MINT_B),
        AccountMeta.createRead(PRICE_A),
        AccountMeta.createRead(PRICE_B),
        AccountMeta.createRead(SOL_USD),
        AccountMeta.createWrite(THING)
    ), mapped.instruction().accounts());
    assertArrayEquals(new byte[]{9, 9, 5, 6}, mapped.instruction().data());
    assertEquals(1, requests.size());
    final var request = requests.getFirst();
    assertEquals(PROXY, request.proxyProgram());
    assertEquals(PROGRAM, request.program());
    assertEquals("place", request.source());
    assertEquals("proxy_place", request.handler());
    assertSame(instruction, request.instruction());
    assertEquals(List.of(
        new SuppliedAccountsRequest.Role("asset_oracle", List.of(MINT_A), false),
        new SuppliedAccountsRequest.Role("asset_oracle", List.of(MINT_B), false),
        new SuppliedAccountsRequest.Role("sol_usd_oracle", List.of(), true)
    ), request.roles());
  }

  @Test
  void aSupplierMayLeaveOutTheOptionalTailOnly() {
    final var mapper = suppliedMapper();
    final var two = new MappingContext(STATE, VAULT, SIGNER, null, request -> List.of(PRICE_A, PRICE_B));
    final var mapped = assertInstanceOf(MapResult.Mapped.class, mapper.map(placeInstruction(), two));
    assertEquals(7 - 1, mapped.instruction().accounts().size());
    assertEquals(AccountMeta.createWrite(THING), mapped.instruction().accounts().getLast());
    final var one = new MappingContext(STATE, VAULT, SIGNER, null, request -> List.of(PRICE_A));
    final var few = assertInstanceOf(MapResult.Unsupported.class, mapper.map(placeInstruction(), one));
    assertEquals(UnsupportedReason.SUPPLIED_ACCOUNTS, few.reason());
    assertEquals("place takes 2 to 3 supplied accounts (asset_oracle, asset_oracle, sol_usd_oracle?); the context supplied 1", few.message());
    final var four = new MappingContext(STATE, VAULT, SIGNER, null, request -> List.of(PRICE_A, PRICE_B, SOL_USD, THING));
    final var many = assertInstanceOf(MapResult.Unsupported.class, mapper.map(placeInstruction(), four));
    assertEquals("place takes 2 to 3 supplied accounts (asset_oracle, asset_oracle, sol_usd_oracle?); the context supplied 4", many.message());
    final var hole = new MappingContext(STATE, VAULT, SIGNER, null, request -> java.util.Arrays.asList(PRICE_A, null, SOL_USD));
    final var withHole = assertInstanceOf(MapResult.Unsupported.class, mapper.map(placeInstruction(), hole));
    assertEquals(UnsupportedReason.SUPPLIED_ACCOUNTS, withHole.reason());
    assertEquals("the context supplied a null account at 1 for place", withHole.message());
  }

  /// An entry that lists no supplied accounts never asks: the supplier of a context that has
  /// one is left alone, and a mapper that asked would refuse every ordinary instruction under
  /// a supplier that answers null for what it does not know.
  @Test
  void anEntryWithoutSuppliedAccountsLeavesTheSupplierAlone() {
    final var asked = new java.util.concurrent.atomic.AtomicInteger();
    final var context = new MappingContext(STATE, VAULT, SIGNER, null, request -> {
      asked.incrementAndGet();
      return null;
    });
    final var mapper = InstructionMapper.createMapper(List.of(relaying("test", PROGRAM)));
    final var instruction = Instruction.createInstruction(PROGRAM, List.of(AccountMeta.createWrite(THING)), new byte[]{1, 7});
    assertInstanceOf(MapResult.Mapped.class, mapper.map(instruction, context));
    assertEquals(0, asked.get());
  }

  /// A role the parser admits builds the request: U+001C is not blank to the parser
  /// (JavaScript's `trim`), so it is not blank to the request either, and mapping returns a
  /// result rather than throwing from the record.
  @Test
  void aRoleTheParserAdmitsBuildsTheRequest() {
    final var document = SUPPLIED_DOCUMENT.formatted(PROGRAM.toBase58(), PROXY.toBase58())
        .replace("\"role\": \"sol_usd_oracle\"", "\"role\": \"\\u001c\"");
    final var mapper = InstructionMapper.createMapper(List.of(MappingDocumentParser.parse(document, "place")));
    final var roles = new java.util.ArrayList<String>();
    final var context = new MappingContext(STATE, VAULT, SIGNER, null, request -> {
      request.roles().forEach(role -> roles.add(role.role()));
      return List.of(PRICE_A, PRICE_B, SOL_USD);
    });
    assertInstanceOf(MapResult.Mapped.class, mapper.map(placeInstruction(), context));
    assertEquals(List.of("asset_oracle", "asset_oracle", "\u001c"), roles);
  }

  /// A refusal at the positions or the seats comes before the supplier is asked.
  @Test
  void aSuppliedEntryRefusedAtAPositionOrASeatAsksNothing() {
    final var base = SUPPLIED_DOCUMENT.formatted(PROGRAM.toBase58(), PROXY.toBase58());
    final var expecting = base.replace(
        "{ \"name\": \"mint_a\", \"writable\": false, \"signer\": false }",
        "{ \"name\": \"mint_a\", \"writable\": false, \"signer\": false, \"expect\": \"glam_vault\" }");
    final var signing = base
        .replace("{ \"name\": \"mint_a\", \"writable\": false, \"signer\": false }",
            "{ \"name\": \"mint_a\", \"writable\": false, \"signer\": true }")
        .replace("{ \"index\": 1, \"kind\": \"source\", \"source\": 0, \"writable\": false, \"signer\": false }",
            "{ \"index\": 1, \"kind\": \"source\", \"source\": 0, \"writable\": false, \"signer\": true }");
    assertNotEquals(base, expecting);
    assertNotEquals(base, signing);
    final var asked = new java.util.concurrent.atomic.AtomicInteger();
    final var context = new MappingContext(STATE, VAULT, SIGNER, null, request -> {
      asked.incrementAndGet();
      return List.of(PRICE_A, PRICE_B, SOL_USD);
    });
    final var atPosition = InstructionMapper.createMapper(List.of(MappingDocumentParser.parse(expecting, "place")))
        .map(placeInstruction(), context);
    assertEquals(UnsupportedReason.ACCOUNT_EXPECTATION, assertInstanceOf(MapResult.Unsupported.class, atPosition).reason());
    final var atSeat = InstructionMapper.createMapper(List.of(MappingDocumentParser.parse(signing, "place")))
        .map(placeInstruction(), context);
    assertEquals(UnsupportedReason.ACCOUNT_PRIVILEGE, assertInstanceOf(MapResult.Unsupported.class, atSeat).reason());
    assertEquals(0, asked.get());
  }

  @Test
  void aContextWithoutASupplierOrWithANullAnswerIsARefusal() {
    final var mapper = suppliedMapper();
    final var none = assertInstanceOf(MapResult.Unsupported.class, mapper.map(placeInstruction(), CONTEXT));
    assertEquals(UnsupportedReason.CONTEXT, none.reason());
    assertEquals("the context supplies no accounts for place", none.message());
    final var unknown = new MappingContext(STATE, VAULT, SIGNER, null, request -> null);
    final var absent = assertInstanceOf(MapResult.Unsupported.class, mapper.map(placeInstruction(), unknown));
    assertEquals(UnsupportedReason.CONTEXT, absent.reason());
    assertEquals("the context supplies no accounts for place", absent.message());
  }

  /// The catch is for exceptions: an `Error` a supplier throws is the caller's to see, as one
  /// from the integration-authority lookup is.
  @Test
  void anErrorFromASupplierEscapes() {
    final var mapper = suppliedMapper();
    final var failing = new MappingContext(STATE, VAULT, SIGNER, null, request -> {
      throw new AssertionError("escapes");
    });
    final var escaped = assertThrows(AssertionError.class, () -> mapper.map(placeInstruction(), failing));
    assertEquals("escapes", escaped.getMessage());
  }

  /// The answer is read once, inside the same guard as the call: a list that fails while it
  /// is read is the supplier's failure, a refusal, not an escape.
  @Test
  void anAnswerThatFailsWhileReadIsARefusal() {
    final var lazy = new java.util.AbstractList<PublicKey>() {
      @Override
      public PublicKey get(final int index) {
        throw new IllegalStateException("lookup failed at " + index);
      }

      @Override
      public int size() {
        throw new IllegalStateException("size unknown");
      }
    };
    final var context = new MappingContext(STATE, VAULT, SIGNER, null, request -> lazy);
    final var failed = assertInstanceOf(MapResult.Unsupported.class, suppliedMapper().map(placeInstruction(), context));
    assertEquals(UnsupportedReason.CONTEXT, failed.reason());
    assertEquals("the context's supplied accounts failed for place: size unknown", failed.message());
  }

  @Test
  void aThrowingSupplierIsARefusalNotAnEscape() {
    final var mapper = suppliedMapper();
    final var throwing = new MappingContext(STATE, VAULT, SIGNER, null, request -> {
      throw new IllegalStateException("boom");
    });
    final var failed = assertInstanceOf(MapResult.Unsupported.class, mapper.map(placeInstruction(), throwing));
    assertEquals(UnsupportedReason.CONTEXT, failed.reason());
    assertEquals("the context's supplied accounts failed for place: boom", failed.message());
    final var unnamed = new MappingContext(STATE, VAULT, SIGNER, null, request -> {
      throw new IllegalStateException();
    });
    final var failedWithoutAMessage = assertInstanceOf(MapResult.Unsupported.class, mapper.map(placeInstruction(), unnamed));
    assertEquals("the context's supplied accounts failed for place: java.lang.IllegalStateException", failedWithoutAMessage.message());
  }

  /// An entry with one required supplied account names it in the singular.
  @Test
  void aSingleSuppliedAccountIsNamedInTheSingular() {
    final var json = """
        {
          "schema_version": 1, "environment": "test",
          "program_id": "%s", "proxy_program_id": "%s",
          "instructions": [{
            "name": "one", "discriminator": [1], "disposition": "map",
            "handler": { "name": "proxy_one", "discriminator": [9] },
            "source_accounts": [],
            "destination_accounts": [],
            "supplied_accounts": [{ "role": "loopscale_strategy_market" }]
          }]
        }
        """.formatted(PROGRAM.toBase58(), PROXY.toBase58());
    final var mapper = InstructionMapper.createMapper(List.of(MappingDocumentParser.parse(json, "one")));
    final var instruction = Instruction.createInstruction(PROGRAM, List.of(), new byte[]{1});
    final var empty = new MappingContext(STATE, VAULT, SIGNER, null, request -> List.of());
    final var refused = assertInstanceOf(MapResult.Unsupported.class, mapper.map(instruction, empty));
    assertEquals("one takes 1 supplied account (loopscale_strategy_market); the context supplied 0", refused.message());
    final var served = new MappingContext(STATE, VAULT, SIGNER, null, request -> List.of(THING));
    final var mapped = assertInstanceOf(MapResult.Mapped.class, mapper.map(instruction, served));
    assertEquals(List.of(AccountMeta.createRead(THING)), mapped.instruction().accounts());
  }

  /// A checked exception or an interruption from the supplier is a refusal too, and the
  /// thread keeps its interrupt flag.
  @Test
  void aSneakyCheckedExceptionOrAnInterruptFromTheSupplierIsARefusal() {
    final var mapper = suppliedMapper();
    final var sneakyIo = new MappingContext(STATE, VAULT, SIGNER, null, request -> {
      throw sneaky(new java.io.IOException("io"));
    });
    final var refused = assertInstanceOf(MapResult.Unsupported.class, mapper.map(placeInstruction(), sneakyIo));
    assertEquals(UnsupportedReason.CONTEXT, refused.reason());
    assertEquals("the context's supplied accounts failed for place: io", refused.message());
    assertFalse(Thread.currentThread().isInterrupted());
    final var interrupting = new MappingContext(STATE, VAULT, SIGNER, null, request -> {
      throw sneaky(new InterruptedException("interrupted"));
    });
    final var interrupted = assertInstanceOf(MapResult.Unsupported.class, mapper.map(placeInstruction(), interrupting));
    assertTrue(Thread.interrupted(), "the interrupt flag is set again for the caller");
    assertEquals(UnsupportedReason.CONTEXT, interrupted.reason());
    assertEquals("the context's supplied accounts failed for place: interrupted", interrupted.message());
  }

  /// The older constructors still build a context that supplies nothing.
  @Test
  void theShorterContextConstructorsSupplyNoAccounts() {
    assertNull(new MappingContext(STATE, VAULT, SIGNER).suppliedAccounts());
    assertNull(new MappingContext(STATE, VAULT, SIGNER).integrationAuthority());
    final var withAuthority = new MappingContext(STATE, VAULT, SIGNER, proxy -> THING);
    assertNull(withAuthority.suppliedAccounts());
    assertEquals(THING, withAuthority.integrationAuthority().apply(PROXY));
  }

  private static final PublicKey ROUTES = PublicKey.fromBase58Encoded("Routes1111111111111111111111111111111111111");
  private static final PublicKey LEDGER = PublicKey.fromBase58Encoded("Ledger1111111111111111111111111111111111111");
  private static final PublicKey FIXED = PublicKey.fromBase58Encoded("Fixed11111111111111111111111111111111111111");
  private static final PublicKey MAYBE = PublicKey.fromBase58Encoded("Maybe11111111111111111111111111111111111111");
  private static final PublicKey EXTRA = PublicKey.fromBase58Encoded("Extra11111111111111111111111111111111111111");
  /// "routes"
  private static final byte[] ROUTES_SEED = {114, 111, 117, 116, 101, 115};

  /// An entry that supplies two accounts at an account index and lists two more: `routes` at
  /// account index 2, read-only, derived under the proxy program from a constant, the accounts
  /// at account indexes 0 (a GLAM account), 1 (a fixed address), 3 (a forwarded one) and 5 (a
  /// forwarded optional a sentinel may rewrite) and an argument; and `ledger` at 4, writable,
  /// with no derivation, listed ahead of `routes`, so the request's order is the account
  /// indexes' and not the list's.
  private static final String PLACED_DOCUMENT = """
      {
        "schema_version": 1, "environment": "test",
        "program_id": "%1$s", "proxy_program_id": "%2$s",
        "instructions": [{
          "name": "route", "discriminator": [6], "disposition": "map",
          "handler": { "name": "proxy_route", "discriminator": [8, 8] },
          "source_accounts": [
            { "name": "mint", "writable": false, "signer": false },
            { "name": "maybe", "writable": false, "signer": false, "optional": "program_id" }
          ],
          "destination_accounts": [
            { "index": 0, "kind": "dynamic", "name": "glam_state", "writable": true, "signer": false },
            { "index": 1, "kind": "static", "address": "%3$s", "writable": false, "signer": false },
            { "index": 4, "kind": "supplied", "role": "ledger", "writable": true, "signer": false },
            {
              "index": 2, "kind": "supplied", "role": "routes", "writable": false, "signer": false,
              "derivation": {
                "program": "%2$s",
                "seeds": [
                  { "kind": "const", "value": [114, 111, 117, 116, 101, 115] },
                  { "kind": "account", "index": 0 },
                  { "kind": "account", "index": 1 },
                  { "kind": "account", "index": 3 },
                  { "kind": "account", "index": 5 },
                  { "kind": "arg", "path": "params.protocol" }
                ]
              }
            },
            { "index": 3, "kind": "source", "source": 0, "writable": false, "signer": false },
            { "index": 5, "kind": "source", "source": 1, "writable": false, "signer": false, "sentinel": true }
          ],
          "supplied_accounts": [
            { "role": "asset_oracle", "of": [0] },
            { "role": "sol_usd_oracle", "optional": true }
          ]
        }]
      }
      """;

  private static InstructionMapper placedMapper() {
    return InstructionMapper.createMapper(List.of(MappingDocumentParser.parse(
        PLACED_DOCUMENT.formatted(PROGRAM.toBase58(), PROXY.toBase58(), FIXED.toBase58()), "route"
    )));
  }

  /// `route` with the mint, the optional at position 1, and an account beyond the list.
  private static Instruction routeInstruction(final PublicKey maybe) {
    return Instruction.createInstruction(
        PROGRAM,
        List.of(AccountMeta.createRead(MINT_A), AccountMeta.createRead(maybe), AccountMeta.createWrite(EXTRA)),
        new byte[]{6, 5}
    );
  }

  /// The role `routes` the request names, its derivation resolved with what the mapper placed
  /// at account index 5.
  private static SuppliedAccountsRequest.Role routesRole(final PublicKey atFive) {
    return new SuppliedAccountsRequest.Role("routes", List.of(), false, new SuppliedAccountsRequest.Derivation(PROXY, List.of(
        new SuppliedAccountsRequest.Const(ROUTES_SEED),
        new SuppliedAccountsRequest.Account(STATE),
        new SuppliedAccountsRequest.Account(FIXED),
        new SuppliedAccountsRequest.Account(MINT_A),
        new SuppliedAccountsRequest.Account(atFive),
        new SuppliedAccountsRequest.Arg("params.protocol")
    )));
  }

  /// The supplier's answer for an account at an account index takes that account index, with
  /// the handler's writable flag and unsigned; the rest of the answer follows the declared
  /// accounts, read-only and unsigned, and the accounts beyond the list follow it.
  @Test
  void aSuppliedAccountAtItsAccountIndexIsPlacedThere() {
    final var requests = new java.util.ArrayList<SuppliedAccountsRequest>();
    final var context = new MappingContext(STATE, VAULT, SIGNER, null, request -> {
      requests.add(request);
      return List.of(ROUTES, LEDGER, PRICE_A, SOL_USD);
    });
    final var mapped = assertInstanceOf(MapResult.Mapped.class, placedMapper().map(routeInstruction(MAYBE), context));
    assertEquals(List.of(
        AccountMeta.createWrite(STATE),
        AccountMeta.createRead(FIXED),
        AccountMeta.createRead(ROUTES),
        AccountMeta.createRead(MINT_A),
        AccountMeta.createWrite(LEDGER),
        AccountMeta.createRead(MAYBE),
        AccountMeta.createRead(PRICE_A),
        AccountMeta.createRead(SOL_USD),
        AccountMeta.createWrite(EXTRA)
    ), mapped.instruction().accounts());
    assertArrayEquals(new byte[]{8, 8, 5}, mapped.instruction().data());
    assertEquals("proxy_route", mapped.handler());
    assertEquals(1, requests.size(), "one request for the entry");
  }

  /// The request names the accounts at an account index first, in account-index order, each
  /// with no `of` addresses, required, and its derivation resolved to what the mapper placed:
  /// the constant's bytes, the context's state, the fixed address, the forwarded accounts, the
  /// argument's path unread; then the supplied accounts the entry lists, with none.
  @Test
  void theRequestNamesTheAccountsAtAnAccountIndexFirstWithTheirDerivationsResolved() {
    final var requests = new java.util.ArrayList<SuppliedAccountsRequest>();
    final var instruction = routeInstruction(MAYBE);
    final var context = new MappingContext(STATE, VAULT, SIGNER, null, request -> {
      requests.add(request);
      return List.of(ROUTES, LEDGER, PRICE_A);
    });
    assertInstanceOf(MapResult.Mapped.class, placedMapper().map(instruction, context));
    final var request = requests.getFirst();
    assertEquals(PROXY, request.proxyProgram());
    assertEquals(PROGRAM, request.program());
    assertEquals("route", request.source());
    assertEquals("proxy_route", request.handler());
    assertSame(instruction, request.instruction());
    assertEquals(List.of(
        routesRole(MAYBE),
        new SuppliedAccountsRequest.Role("ledger", List.of(), false),
        new SuppliedAccountsRequest.Role("asset_oracle", List.of(MINT_A), false),
        new SuppliedAccountsRequest.Role("sol_usd_oracle", List.of(), true)
    ), request.roles());
    assertNull(request.roles().get(1).derivation());
    assertNull(request.roles().get(2).derivation());
  }

  /// Where a sentinel rewrote an absent optional to the proxy program, a seed naming its
  /// account index resolves to the proxy program, what the mapper placed there.
  @Test
  void aSeedResolvesToTheProxyProgramWhereASentinelRewroteTheAccount() {
    final var requests = new java.util.ArrayList<SuppliedAccountsRequest>();
    final var context = new MappingContext(STATE, VAULT, SIGNER, null, request -> {
      requests.add(request);
      return List.of(ROUTES, LEDGER, PRICE_A);
    });
    final var mapped = assertInstanceOf(MapResult.Mapped.class, placedMapper().map(routeInstruction(PROGRAM), context));
    assertEquals(AccountMeta.createRead(PROXY), mapped.instruction().accounts().get(5));
    assertEquals(routesRole(PROXY), requests.getFirst().roles().getFirst());
  }

  /// The accounts at an account index are required and named first in the count, the optional
  /// tail of the listed ones may still be left out, and a null anywhere in the answer is
  /// refused at its place in the answer.
  @Test
  void theAccountsAtAnAccountIndexAreRequiredAndNamedFirst() {
    final var mapper = placedMapper();
    final var count = "route takes 3 to 4 supplied accounts (routes, ledger, asset_oracle, sol_usd_oracle?); the context supplied ";
    for (final var answer : List.of(List.<PublicKey>of(), List.of(ROUTES, LEDGER), List.of(ROUTES, LEDGER, PRICE_A, SOL_USD, THING))) {
      final var refused = assertInstanceOf(MapResult.Unsupported.class,
          mapper.map(routeInstruction(MAYBE), new MappingContext(STATE, VAULT, SIGNER, null, request -> answer)));
      assertEquals(UnsupportedReason.SUPPLIED_ACCOUNTS, refused.reason());
      assertEquals(count + answer.size(), refused.message());
    }
    final var three = assertInstanceOf(MapResult.Mapped.class,
        mapper.map(routeInstruction(MAYBE), new MappingContext(STATE, VAULT, SIGNER, null, request -> List.of(ROUTES, LEDGER, PRICE_A))));
    assertEquals(List.of(
        AccountMeta.createWrite(STATE),
        AccountMeta.createRead(FIXED),
        AccountMeta.createRead(ROUTES),
        AccountMeta.createRead(MINT_A),
        AccountMeta.createWrite(LEDGER),
        AccountMeta.createRead(MAYBE),
        AccountMeta.createRead(PRICE_A),
        AccountMeta.createWrite(EXTRA)
    ), three.instruction().accounts());
    for (int at = 0; at < 3; at++) {
      final var answer = new java.util.ArrayList<>(List.of(ROUTES, LEDGER, PRICE_A));
      answer.set(at, null);
      final var refused = assertInstanceOf(MapResult.Unsupported.class,
          mapper.map(routeInstruction(MAYBE), new MappingContext(STATE, VAULT, SIGNER, null, request -> answer)));
      assertEquals(UnsupportedReason.SUPPLIED_ACCOUNTS, refused.reason());
      assertEquals("the context supplied a null account at " + at + " for route", refused.message());
    }
  }

  /// An entry whose only supplied account is at an account index asks the supplier too, once:
  /// no supplier, a null answer or a throwing one refuse it for the context, the count names
  /// the one account in the singular, an account index without a derivation is asked for with
  /// none, and a refusal at a position or an account index comes before the supplier is asked.
  @Test
  void anEntryWhoseOnlySuppliedAccountIsAtAnAccountIndexAsksTheSupplier() {
    final var json = """
        {
          "schema_version": 1, "environment": "test",
          "program_id": "%s", "proxy_program_id": "%s",
          "instructions": [{
            "name": "one", "discriminator": [1], "disposition": "map",
            "handler": { "name": "proxy_one", "discriminator": [9] },
            "source_accounts": [{ "name": "thing", "writable": false, "signer": false }],
            "destination_accounts": [
              { "index": 0, "kind": "source", "source": 0, "writable": false, "signer": false },
              { "index": 1, "kind": "supplied", "role": "routes", "writable": false, "signer": false }
            ],
            "remaining_accounts": { "kind": "none" }
          }]
        }
        """.formatted(PROGRAM.toBase58(), PROXY.toBase58());
    final var mapper = InstructionMapper.createMapper(List.of(MappingDocumentParser.parse(json, "one")));
    final var instruction = Instruction.createInstruction(PROGRAM, List.of(AccountMeta.createRead(THING)), new byte[]{1});
    final var none = assertInstanceOf(MapResult.Unsupported.class, mapper.map(instruction, CONTEXT));
    assertEquals(UnsupportedReason.CONTEXT, none.reason());
    assertEquals("the context supplies no accounts for one", none.message());
    final var unknown = assertInstanceOf(MapResult.Unsupported.class,
        mapper.map(instruction, new MappingContext(STATE, VAULT, SIGNER, null, request -> null)));
    assertEquals(UnsupportedReason.CONTEXT, unknown.reason());
    assertEquals("the context supplies no accounts for one", unknown.message());
    final var failed = assertInstanceOf(MapResult.Unsupported.class, mapper.map(instruction, new MappingContext(STATE, VAULT, SIGNER, null, request -> {
      throw new IllegalStateException("boom");
    })));
    assertEquals(UnsupportedReason.CONTEXT, failed.reason());
    assertEquals("the context's supplied accounts failed for one: boom", failed.message());
    final var empty = assertInstanceOf(MapResult.Unsupported.class,
        mapper.map(instruction, new MappingContext(STATE, VAULT, SIGNER, null, request -> List.of())));
    assertEquals(UnsupportedReason.SUPPLIED_ACCOUNTS, empty.reason());
    assertEquals("one takes 1 supplied account (routes); the context supplied 0", empty.message());
    final var requests = new java.util.ArrayList<SuppliedAccountsRequest>();
    final var served = assertInstanceOf(MapResult.Mapped.class, mapper.map(instruction, new MappingContext(STATE, VAULT, SIGNER, null, request -> {
      requests.add(request);
      return List.of(ROUTES);
    })));
    assertEquals(List.of(AccountMeta.createRead(THING), AccountMeta.createRead(ROUTES)), served.instruction().accounts());
    assertEquals(List.of(new SuppliedAccountsRequest.Role("routes", List.of(), false)), requests.getFirst().roles());
    final var signing = Instruction.createInstruction(PROGRAM, List.of(AccountMeta.createReadOnlySigner(THING)), new byte[]{1});
    final var refused = assertInstanceOf(MapResult.Unsupported.class, mapper.map(signing, new MappingContext(STATE, VAULT, SIGNER, null, request -> {
      requests.add(request);
      return List.of(ROUTES);
    })));
    assertEquals(UnsupportedReason.ACCOUNT_PRIVILEGE, refused.reason());
    assertEquals(1, requests.size(), "a refusal at a position comes before the supplier is asked");
    final var beyond = Instruction.createInstruction(PROGRAM, List.of(AccountMeta.createRead(THING), AccountMeta.createRead(EXTRA)), new byte[]{1});
    final var past = assertInstanceOf(MapResult.Unsupported.class,
        mapper.map(beyond, new MappingContext(STATE, VAULT, SIGNER, null, request -> List.of(ROUTES))));
    assertEquals(UnsupportedReason.REMAINING_ACCOUNTS, past.reason());
  }

  /// A client that leaves out the omittable tail shortens the list after the account index,
  /// never before it: the supplied account keeps its account index with the handler's
  /// writable flag, the forwarded account after it keeps its own, nothing stands where the
  /// tail was, and the request names the account once, required, with no `of` addresses and
  /// its derivation resolved to what the mapper placed. A client that passes the tail finds
  /// it after them, with the same request.
  @Test
  void aSuppliedAccountAtAnAccountIndexKeepsItWhenTheOmittableTailIsAbsent() {
    final var json = """
        {
          "schema_version": 1, "environment": "test",
          "program_id": "%1$s", "proxy_program_id": "%2$s",
          "instructions": [{
            "name": "deposit", "discriminator": [7], "disposition": "map",
            "handler": { "name": "proxy_deposit", "discriminator": [9, 9] },
            "source_accounts": [
              { "name": "mint", "writable": false, "signer": false },
              { "name": "referrer", "writable": false, "signer": false, "optional": "omitted" }
            ],
            "destination_accounts": [
              { "index": 0, "kind": "dynamic", "name": "glam_state", "writable": true, "signer": false },
              {
                "index": 1, "kind": "supplied", "role": "routes", "writable": true, "signer": false,
                "derivation": {
                  "program": "%2$s",
                  "seeds": [
                    { "kind": "const", "value": [114, 111, 117, 116, 101, 115] },
                    { "kind": "account", "index": 2 }
                  ]
                }
              },
              { "index": 2, "kind": "source", "source": 0, "writable": false, "signer": false },
              { "index": 3, "kind": "source", "source": 1, "writable": false, "signer": false }
            ]
          }]
        }
        """.formatted(PROGRAM.toBase58(), PROXY.toBase58());
    final var mapper = InstructionMapper.createMapper(List.of(MappingDocumentParser.parse(json, "deposit")));
    final var requests = new java.util.ArrayList<SuppliedAccountsRequest>();
    final var context = new MappingContext(STATE, VAULT, SIGNER, null, request -> {
      requests.add(request);
      return List.of(ROUTES);
    });
    final var roles = List.of(new SuppliedAccountsRequest.Role("routes", List.of(), false, new SuppliedAccountsRequest.Derivation(PROXY, List.of(
        new SuppliedAccountsRequest.Const(ROUTES_SEED),
        new SuppliedAccountsRequest.Account(MINT_A)
    ))));
    final var left = Instruction.createInstruction(PROGRAM, List.of(AccountMeta.createRead(MINT_A)), new byte[]{7, 5});
    final var absent = assertInstanceOf(MapResult.Mapped.class, mapper.map(left, context));
    assertEquals(List.of(
        AccountMeta.createWrite(STATE),
        AccountMeta.createWrite(ROUTES),
        AccountMeta.createRead(MINT_A)
    ), absent.instruction().accounts());
    assertEquals(1, requests.size(), "one request for the entry");
    final var request = requests.getFirst();
    assertSame(left, request.instruction());
    assertEquals("proxy_deposit", request.handler());
    assertEquals(roles, request.roles());
    final var carried = Instruction.createInstruction(PROGRAM, List.of(AccountMeta.createRead(MINT_A), AccountMeta.createRead(THING)), new byte[]{7, 5});
    final var present = assertInstanceOf(MapResult.Mapped.class, mapper.map(carried, context));
    assertEquals(List.of(
        AccountMeta.createWrite(STATE),
        AccountMeta.createWrite(ROUTES),
        AccountMeta.createRead(MINT_A),
        AccountMeta.createRead(THING)
    ), present.instruction().accounts());
    assertEquals(2, requests.size());
    assertEquals(roles, requests.getLast().roles());
  }

  /// The supplier is asked once every account index is placed, so a refusal at an account
  /// index after the one the context supplies comes first and leaves the supplier unasked:
  /// a forwarded account that does not sign where the handler needs it signed, or signs
  /// where the handler takes it unsigned, a GLAM account the context has no address for or
  /// whose lookup fails, and, earlier still, an expectation at the position forwarded to a
  /// later account index. With none of them the same supplier is asked, once.
  @Test
  void aRefusalAtALaterAccountIndexComesBeforeTheSupplierIsAsked() {
    final var json = """
        {
          "schema_version": 1, "environment": "test",
          "program_id": "%s", "proxy_program_id": "%s",
          "instructions": [{
            "name": "deposit", "discriminator": [7], "disposition": "map",
            "handler": { "name": "proxy_deposit", "discriminator": [9, 9] },
            "source_accounts": [
              { "name": "authority", "writable": false, "signer": true },
              { "name": "pool", "writable": false, "signer": false, "expect": "glam_vault" }
            ],
            "destination_accounts": [
              { "index": 0, "kind": "dynamic", "name": "glam_state", "writable": true, "signer": false },
              { "index": 1, "kind": "supplied", "role": "routes", "writable": false, "signer": false },
              { "index": 2, "kind": "source", "source": 0, "writable": false, "signer": true },
              { "index": 3, "kind": "source", "source": 1, "writable": false, "signer": false },
              { "index": 4, "kind": "dynamic", "name": "integration_authority", "writable": false, "signer": false }
            ]
          }]
        }
        """.formatted(PROGRAM.toBase58(), PROXY.toBase58());
    final var mapper = InstructionMapper.createMapper(List.of(MappingDocumentParser.parse(json, "deposit")));
    final var authority = PublicKey.fromBase58Encoded("Authority1111111111111111111111111111111111");
    final var asked = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.function.Function<SuppliedAccountsRequest, List<PublicKey>> supplier = request -> {
      asked.incrementAndGet();
      return List.of(ROUTES);
    };
    final var context = new MappingContext(STATE, VAULT, SIGNER, Map.of(PROXY, THING)::get, supplier);
    final var unsigned = Instruction.createInstruction(PROGRAM, List.of(AccountMeta.createRead(authority), AccountMeta.createRead(VAULT)), new byte[]{7});
    final var mustSign = assertInstanceOf(MapResult.Unsupported.class, mapper.map(unsigned, context));
    assertEquals(UnsupportedReason.ACCOUNT_PRIVILEGE, mustSign.reason());
    assertEquals("deposit account 0 (authority) must sign", mustSign.message());
    final var signing = Instruction.createInstruction(PROGRAM, List.of(AccountMeta.createReadOnlySigner(authority), AccountMeta.createReadOnlySigner(VAULT)), new byte[]{7});
    final var signs = assertInstanceOf(MapResult.Unsupported.class, mapper.map(signing, context));
    assertEquals(UnsupportedReason.ACCOUNT_PRIVILEGE, signs.reason());
    assertEquals("deposit account 1 (pool) signs, but the handler takes it unsigned", signs.message());
    final var sound = Instruction.createInstruction(PROGRAM, List.of(AccountMeta.createReadOnlySigner(authority), AccountMeta.createRead(VAULT)), new byte[]{7});
    final var absent = assertInstanceOf(MapResult.Unsupported.class, mapper.map(sound, new MappingContext(STATE, VAULT, SIGNER, null, supplier)));
    assertEquals(UnsupportedReason.CONTEXT, absent.reason());
    assertEquals("the context supplies no integration_authority for " + PROXY.toBase58(), absent.message());
    final var failing = new MappingContext(STATE, VAULT, SIGNER, proxy -> {
      throw new IllegalStateException("boom");
    }, supplier);
    final var failed = assertInstanceOf(MapResult.Unsupported.class, mapper.map(sound, failing));
    assertEquals(UnsupportedReason.CONTEXT, failed.reason());
    assertEquals("the context's integration authority failed for " + PROXY.toBase58() + ": boom", failed.message());
    final var elsewhere = Instruction.createInstruction(PROGRAM, List.of(AccountMeta.createReadOnlySigner(authority), AccountMeta.createRead(THING)), new byte[]{7});
    final var expected = assertInstanceOf(MapResult.Unsupported.class, mapper.map(elsewhere, context));
    assertEquals(UnsupportedReason.ACCOUNT_EXPECTATION, expected.reason());
    assertEquals("deposit account 1 (pool) must be glam_vault", expected.message());
    assertEquals(0, asked.get(), "a refusal at a later account index leaves the supplier unasked");
    final var mapped = assertInstanceOf(MapResult.Mapped.class, mapper.map(sound, context));
    assertEquals(List.of(
        AccountMeta.createWrite(STATE),
        AccountMeta.createRead(ROUTES),
        AccountMeta.createReadOnlySigner(authority),
        AccountMeta.createRead(VAULT),
        AccountMeta.createRead(THING)
    ), mapped.instruction().accounts());
    assertEquals(1, asked.get(), "with nothing refused, the same supplier is asked once");
  }

  /// A document built from records holds its accounts at an account index to the parser's
  /// rules when a mapper is created over it: one that signs, or a derivation naming an account
  /// index past the list, one the context supplies or one a client may leave out, or a
  /// constant longer than 32 bytes, forms no mapper; one that keeps them does.
  @Test
  void aDocumentBuiltFromRecordsHoldsItsAccountsAtAnAccountIndexToTheRules() {
    final var thing = new SourceAccount("thing", true, false, false, null, null);
    final var trailing = new SourceAccount("trailing", false, false, false, OptionalKind.OMITTED, null);
    final var state = new DestinationAccount.Dynamic(0, DynamicAccountName.GLAM_STATE, false, false);
    final var forwarded = new DestinationAccount.Source(2, 0, true, false, false);
    final java.util.function.Function<Derivation.Seed, DestinationAccount> derivedFrom = seed ->
        new DestinationAccount.Supplied(1, "routes", false, false, new Derivation(PROXY, List.of(seed)));
    final var rows = List.of(
        java.util.Map.entry(
            withSupplied(List.of(thing), List.of(state, new DestinationAccount.Supplied(1, "routes", false, true, null), forwarded)),
            "the supplied account at account index 1 signs; a supplied account never signs"),
        java.util.Map.entry(
            withSupplied(List.of(thing), List.of(state, derivedFrom.apply(new Derivation.Account(3)), forwarded)),
            "the supplied account at account index 1 derives from account index 3, which is out of range of 3"),
        java.util.Map.entry(
            withSupplied(List.of(thing), List.of(state, derivedFrom.apply(new Derivation.Account(1)), forwarded)),
            "the supplied account at account index 1 derives from account index 1, which the context supplies; a mapper resolves no supplied account for another"),
        java.util.Map.entry(
            withSupplied(List.of(thing, trailing), List.of(state, derivedFrom.apply(new Derivation.Account(3)), forwarded,
                new DestinationAccount.Source(3, 1, false, false, false))),
            "the supplied account at account index 1 derives from account index 3, which a client may leave out"),
        java.util.Map.entry(
            withSupplied(List.of(thing), List.of(state, derivedFrom.apply(new Derivation.Const(new byte[33])), forwarded)),
            "the supplied account at account index 1 has a constant seed longer than 32 bytes")
    );
    for (final var row : rows) {
      final var refused = assertThrows(MappingDocumentException.class, () -> InstructionMapper.createMapper(List.of(
          new MappingDocument(1, "test", PROGRAM, PROXY, null, List.of(row.getKey())))), row.getValue());
      assertEquals(PROGRAM.toBase58() + " instructions[0]", refused.at());
      assertEquals(row.getValue(), refused.detail());
    }
    final var sound = withSupplied(List.of(thing), List.of(state, new DestinationAccount.Supplied(1, "routes", true, false, new Derivation(PROXY, List.of(
        new Derivation.Const(new byte[32]), new Derivation.Account(0), new Derivation.Account(2), new Derivation.Arg("params.protocol")
    ))), forwarded));
    final var mapper = InstructionMapper.createMapper(List.of(new MappingDocument(1, "test", PROGRAM, PROXY, null, List.of(sound))));
    final var requests = new java.util.ArrayList<SuppliedAccountsRequest>();
    final var mapped = assertInstanceOf(MapResult.Mapped.class, mapper.map(
        Instruction.createInstruction(PROGRAM, List.of(AccountMeta.createWrite(THING)), new byte[]{7}),
        new MappingContext(STATE, VAULT, SIGNER, null, request -> {
          requests.add(request);
          return List.of(ROUTES);
        })));
    assertEquals(List.of(AccountMeta.createRead(STATE), AccountMeta.createWrite(ROUTES), AccountMeta.createWrite(THING)),
        mapped.instruction().accounts());
    assertEquals(new SuppliedAccountsRequest.Derivation(PROXY, List.of(
        new SuppliedAccountsRequest.Const(new byte[32]), new SuppliedAccountsRequest.Account(STATE),
        new SuppliedAccountsRequest.Account(THING), new SuppliedAccountsRequest.Arg("params.protocol")
    )), requests.getFirst().roles().getFirst().derivation());
  }

  /// The generator's CCTP document (a committed seed of the mappingConfig corpus): a
  /// `deposit_for_burn` maps with the supplier's `bridge_routes` at account index 7, read-only,
  /// asked for alone and with its derivation resolved to "bridge-routes", the vault's state
  /// and the protocol's two bytes; CCTP's accounts follow it one account index later.
  @Test
  void theGeneratedCctpDocumentPlacesBridgeRoutesAtAccountIndexSeven() {
    final var document = MappingDocuments.read(java.nio.file.Path.of("src/test/resources/fuzz/mappingConfig/cctp-production.json"));
    final var mapper = InstructionMapper.createMapper(List.of(document));
    final var transmitter = PublicKey.fromBase58Encoded("CCTPV2Sm4AdWt5296sk4P66VBZ7bEhcARwFaaS9YPbeC");
    final var minter = PublicKey.fromBase58Encoded("CCTPV2vPZJS2u2BBsUoscuikbYjnpFmbFsvVuJdgUMQe");
    final var tokenProgram = PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
    final var systemProgram = PublicKey.fromBase58Encoded("11111111111111111111111111111111");
    final var authority = PublicKey.fromBase58Encoded("Maybe11111111111111111111111111111111111111");
    final var cctp = new PublicKey[17];
    for (int i = 0; i < cctp.length; i++) {
      cctp[i] = IxMapperFuzz.key(40 + i);
    }
    final var accounts = new java.util.ArrayList<AccountMeta>();
    accounts.add(AccountMeta.createReadOnlySigner(VAULT));
    accounts.add(AccountMeta.createWritableSigner(SIGNER));
    for (int i = 2; i <= 10; i++) {
      accounts.add(AccountMeta.createRead(cctp[i]));
    }
    accounts.add(AccountMeta.createWritableSigner(cctp[11]));
    accounts.add(AccountMeta.createRead(transmitter));
    accounts.add(AccountMeta.createRead(minter));
    accounts.add(AccountMeta.createRead(tokenProgram));
    accounts.add(AccountMeta.createRead(systemProgram));
    accounts.add(AccountMeta.createRead(cctp[16]));
    accounts.add(AccountMeta.createRead(minter));
    final var instruction = Instruction.createInstruction(minter, accounts, new byte[]{(byte) 215, 60, 61, 46, 114, 55, (byte) 128, (byte) 176, 1, 2, 3});
    final var requests = new java.util.ArrayList<SuppliedAccountsRequest>();
    final var context = new MappingContext(STATE, VAULT, SIGNER, Map.of(document.proxyProgramId(), authority)::get, request -> {
      requests.add(request);
      return List.of(ROUTES);
    });
    final var mapped = assertInstanceOf(MapResult.Mapped.class, mapper.map(instruction, context));
    assertEquals("cctp_deposit_for_burn", mapped.handler());
    assertEquals(document.proxyProgramId(), mapped.instruction().programId().publicKey());
    assertArrayEquals(new byte[]{(byte) 132, 90, 36, 35, 103, (byte) 197, (byte) 143, 91, 1, 2, 3}, mapped.instruction().data());
    assertEquals(List.of(
        AccountMeta.createWrite(STATE),
        AccountMeta.createWrite(VAULT),
        AccountMeta.createWritableSigner(SIGNER),
        AccountMeta.createRead(authority),
        AccountMeta.createRead(minter),
        AccountMeta.createRead(PublicKey.fromBase58Encoded("GLAMpaME8wdTEzxtiYEAa5yD8fZbxZiz2hNtV58RZiEz")),
        AccountMeta.createRead(systemProgram),
        AccountMeta.createRead(ROUTES),
        AccountMeta.createRead(cctp[2]),
        AccountMeta.createWrite(cctp[3]),
        AccountMeta.createRead(cctp[4]),
        AccountMeta.createWrite(cctp[5]),
        AccountMeta.createRead(cctp[6]),
        AccountMeta.createRead(cctp[7]),
        AccountMeta.createRead(cctp[8]),
        AccountMeta.createWrite(cctp[9]),
        AccountMeta.createWrite(cctp[10]),
        AccountMeta.createWritableSigner(cctp[11]),
        AccountMeta.createRead(transmitter),
        AccountMeta.createRead(minter),
        AccountMeta.createRead(tokenProgram),
        AccountMeta.createRead(cctp[16])
    ), mapped.instruction().accounts());
    assertEquals(1, requests.size());
    assertEquals(List.of(new SuppliedAccountsRequest.Role("bridge_routes", List.of(), false, new SuppliedAccountsRequest.Derivation(
        document.proxyProgramId(),
        List.of(
            new SuppliedAccountsRequest.Const("bridge-routes".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
            new SuppliedAccountsRequest.Account(STATE),
            new SuppliedAccountsRequest.Const(new byte[]{1, 0})
        )
    ))), requests.getFirst().roles());
  }
}
