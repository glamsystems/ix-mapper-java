package systems.glam.ix.proxy;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.programs.Discriminator;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Transaction/instruction plumbing of the mapper returned by
/// [TransactionMapper#createMapper]: routing to the owning program proxy,
/// pass-through for unknown programs, and lookup-table combination on every
/// mapTransaction* branch. The stub proxy tags the checked and unchecked
/// paths with different data so each is distinguishable from the other and
/// from pass-through.
///
/// Table fixtures: sava's transaction factories drop a lookup table that no
/// transaction account resolves into (and normalize a single-entry meta array
/// to the single-table form), so each fixture table indexes exactly one of
/// the proxied instruction's read accounts — table(1) holds LOOKUP_1,
/// table(2) LOOKUP_2, and so on — keeping every table observable on the
/// mapped result, and a source transaction needs two resolving metas before
/// its own tableAccountMetas() is non-empty.
final class ProgramProxyMapTests {

  private static final PublicKey PROXY_PROGRAM = key(1);
  private static final PublicKey CPI_PROGRAM = key(2);
  private static final PublicKey OTHER_PROGRAM = key(3);
  private static final AccountMeta FEE_PAYER = AccountMeta.createFeePayer(key(4));
  private static final AccountMeta SOURCE_PAYER = AccountMeta.createFeePayer(key(5));

  private static final PublicKey LOOKUP_1 = key(6);
  private static final PublicKey LOOKUP_2 = key(7);
  private static final PublicKey LOOKUP_3 = key(8);
  private static final PublicKey LOOKUP_4 = key(9);

  private static final byte[] CHECKED_DATA = {101};
  private static final byte[] UNCHECKED_DATA = {102};
  private static final byte[] SOURCE_DATA = {9, 9};

  private static PublicKey key(final int marker) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) marker;
    return PublicKey.createPubKey(bytes);
  }

  private record StubProgramProxy(PublicKey cpiProgram) implements ProgramProxy<Void> {

    @Override
    public IxProxy<Void> lookupProxy(final Discriminator discriminator) {
      throw new UnsupportedOperationException();
    }

    @Override
    public IxProxy<Void> lookupProxyOrThrow(final Discriminator discriminator) {
      throw new UnsupportedOperationException();
    }

    @Override
    public IxProxy<Void> lookupProxy(final Instruction instruction) {
      throw new UnsupportedOperationException();
    }

    @Override
    public IxProxy<Void> lookupProxyOrThrow(final Instruction instruction) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Instruction mapInstruction(final AccountMeta feePayer, final Void runtimeAccounts, final Instruction ix) {
      return Instruction.createInstruction(PROXY_PROGRAM, ix.accounts(), CHECKED_DATA);
    }

    @Override
    public Instruction mapInstructionUnchecked(final AccountMeta feePayer, final Void runtimeAccounts, final Instruction ix) {
      return Instruction.createInstruction(PROXY_PROGRAM, ix.accounts(), UNCHECKED_DATA);
    }
  }

  private static TransactionMapper<Void> createMapper() {
    return TransactionMapper.createMapper(PROXY_PROGRAM, Map.of(CPI_PROGRAM, new StubProgramProxy(CPI_PROGRAM)));
  }

  private static Instruction proxiedIx() {
    return Instruction.createInstruction(
        CPI_PROGRAM,
        List.of(
            AccountMeta.createRead(LOOKUP_1),
            AccountMeta.createRead(LOOKUP_2),
            AccountMeta.createRead(LOOKUP_3),
            AccountMeta.createRead(LOOKUP_4)
        ),
        SOURCE_DATA
    );
  }

  private static Instruction unknownIx() {
    return Instruction.createInstruction(OTHER_PROGRAM, List.of(AccountMeta.createRead(key(10))), SOURCE_DATA);
  }

  /// A table whose only entry is one of the proxied instruction's read
  /// accounts, so the numbered table always survives transaction creation.
  private static AddressLookupTable table(final int marker) {
    final var containedKey = switch (marker) {
      case 1 -> LOOKUP_1;
      case 2 -> LOOKUP_2;
      case 3 -> LOOKUP_3;
      case 4 -> LOOKUP_4;
      default -> throw new IllegalStateException("Unsupported table marker: " + marker);
    };
    final byte[] data = new byte[AddressLookupTable.LOOKUP_TABLE_META_SIZE + PublicKey.PUBLIC_KEY_LENGTH];
    System.arraycopy(containedKey.toByteArray(), 0, data, AddressLookupTable.LOOKUP_TABLE_META_SIZE, PublicKey.PUBLIC_KEY_LENGTH);
    return AddressLookupTable.read(key(100 + marker), data);
  }

  private static void assertNoTables(final Transaction transaction) {
    assertNull(transaction.lookupTable());
    final var tables = transaction.tableAccountMetas();
    if (tables != null) {
      assertEquals(0, tables.length);
    }
  }

  @Test
  void exposesTheInvokedProgramAndProxyLookup() {
    final var mapper = createMapper();
    assertEquals(PROXY_PROGRAM, mapper.invokedProxyProgram());
    assertEquals(CPI_PROGRAM, mapper.programProxy(CPI_PROGRAM).cpiProgram());
    assertNull(mapper.programProxy(OTHER_PROGRAM));
  }

  @Test
  void mapInstructionRoutesKnownProgramsAndPassesThroughUnknown() {
    final var mapper = createMapper();
    final var mapped = mapper.mapInstruction(FEE_PAYER, null, proxiedIx());
    assertEquals(PROXY_PROGRAM, mapped.programId().publicKey());
    assertArrayEquals(CHECKED_DATA, mapped.data());

    final var unknown = unknownIx();
    assertSame(unknown, mapper.mapInstruction(FEE_PAYER, null, unknown));
  }

  @Test
  void mapInstructionUncheckedUsesTheUncheckedPath() {
    final var mapper = createMapper();
    final var mapped = mapper.mapInstructionUnchecked(FEE_PAYER, null, proxiedIx());
    assertArrayEquals(UNCHECKED_DATA, mapped.data());

    final var unknown = unknownIx();
    assertSame(unknown, mapper.mapInstructionUnchecked(FEE_PAYER, null, unknown));
  }

  @Test
  void mapInstructionsMapsEachInstructionInOrder() {
    final var mapper = createMapper();
    final var unknown = unknownIx();
    final var mapped = mapper.mapInstructions(FEE_PAYER, null, List.of(proxiedIx(), unknown));
    assertEquals(2, mapped.length);
    assertArrayEquals(CHECKED_DATA, mapped[0].data());
    assertSame(unknown, mapped[1]);
  }

  @Test
  void mapTransactionReplacesTheFeePayerAndMapsInstructions() {
    final var mapper = createMapper();
    final var unknown = unknownIx();
    final var source = Transaction.createTx(SOURCE_PAYER, List.of(proxiedIx(), unknown));

    final var mapped = mapper.mapTransaction(FEE_PAYER, null, source);

    assertEquals(FEE_PAYER.publicKey(), mapped.feePayer().publicKey());
    final var instructions = mapped.instructions();
    assertEquals(2, instructions.size());
    assertArrayEquals(CHECKED_DATA, instructions.get(0).data());
    assertSame(unknown, instructions.get(1));
    assertNoTables(mapped);
  }

  @Test
  void mapTransactionDefaultsToTheTransactionsFeePayer() {
    final var mapper = createMapper();
    final var source = Transaction.createTx(SOURCE_PAYER, List.of(proxiedIx()));
    final var mapped = mapper.mapTransaction(null, source);
    assertEquals(SOURCE_PAYER.publicKey(), mapped.feePayer().publicKey());
  }

  @Test
  void mapTransactionPreservesTheSourceLookupTable() {
    final var mapper = createMapper();
    final var sourceTable = table(1);
    final var source = Transaction.createTx(SOURCE_PAYER, List.of(proxiedIx()), sourceTable);
    assertSame(sourceTable, source.lookupTable());

    final var mapped = mapper.mapTransaction(FEE_PAYER, null, source);
    assertSame(sourceTable, mapped.lookupTable());
  }

  @Test
  void mapTransactionPreservesTheSourceTableMetas() {
    final var mapper = createMapper();
    final var sourceMetas = new LookupTableAccountMeta[]{
        LookupTableAccountMeta.createMeta(table(1)),
        LookupTableAccountMeta.createMeta(table(2))
    };
    final var source = Transaction.createTx(SOURCE_PAYER, List.of(proxiedIx()), sourceMetas);
    assertArrayEquals(sourceMetas, source.tableAccountMetas());

    final var mapped = mapper.mapTransaction(FEE_PAYER, null, source);
    assertArrayEquals(sourceMetas, mapped.tableAccountMetas());
  }

  @Test
  void mapTransactionWithTableNullTableIsAPlainMap() {
    final var mapper = createMapper();
    final var source = Transaction.createTx(SOURCE_PAYER, List.of(proxiedIx()));
    final var mapped = mapper.mapTransactionWithTable(FEE_PAYER, null, source, (AddressLookupTable) null);
    assertArrayEquals(CHECKED_DATA, mapped.instructions().getFirst().data());
    assertNoTables(mapped);
  }

  @Test
  void mapTransactionWithTableNullTableKeepsTheSourceLookupTable() {
    final var mapper = createMapper();
    final var sourceTable = table(1);
    final var source = Transaction.createTx(SOURCE_PAYER, List.of(proxiedIx()), sourceTable);
    final var mapped = mapper.mapTransactionWithTable(FEE_PAYER, null, source, (AddressLookupTable) null);
    assertSame(sourceTable, mapped.lookupTable());
  }

  @Test
  void mapTransactionWithTableAttachesTheTableToAPlainTransaction() {
    final var mapper = createMapper();
    final var added = table(1);
    final var source = Transaction.createTx(SOURCE_PAYER, List.of(proxiedIx()));
    final var mapped = mapper.mapTransactionWithTable(FEE_PAYER, null, source, added);
    assertSame(added, mapped.lookupTable());
    assertArrayEquals(CHECKED_DATA, mapped.instructions().getFirst().data());
  }

  @Test
  void mapTransactionWithTableCombinesWithTheSourceLookupTable() {
    final var mapper = createMapper();
    final var sourceTable = table(1);
    final var added = table(2);
    final var source = Transaction.createTx(SOURCE_PAYER, List.of(proxiedIx()), sourceTable);
    final var mapped = mapper.mapTransactionWithTable(FEE_PAYER, null, source, added);
    final var tables = mapped.tableAccountMetas();
    assertEquals(2, tables.length);
    assertSame(sourceTable, tables[0].lookupTable());
    assertSame(added, tables[1].lookupTable());
  }

  @Test
  void mapTransactionWithTableAppendsToExistingTableMetas() {
    final var mapper = createMapper();
    final var sourceMetas = new LookupTableAccountMeta[]{
        LookupTableAccountMeta.createMeta(table(1)),
        LookupTableAccountMeta.createMeta(table(2))
    };
    final var added = table(3);
    final var source = Transaction.createTx(SOURCE_PAYER, List.of(proxiedIx()), sourceMetas);
    final var mapped = mapper.mapTransactionWithTable(FEE_PAYER, null, source, added);
    final var tables = mapped.tableAccountMetas();
    assertEquals(3, tables.length);
    assertSame(sourceMetas[0], tables[0]);
    assertSame(sourceMetas[1], tables[1]);
    assertSame(added, tables[2].lookupTable());
  }

  @Test
  void mapTransactionWithTableDefaultsToTheTransactionsFeePayer() {
    final var mapper = createMapper();
    final var source = Transaction.createTx(SOURCE_PAYER, List.of(proxiedIx()));
    final var mapped = mapper.mapTransactionWithTable(null, source, table(1));
    assertEquals(SOURCE_PAYER.publicKey(), mapped.feePayer().publicKey());
  }

  @Test
  void mapTransactionWithTablesNullIsAPlainMap() {
    final var mapper = createMapper();
    final var source = Transaction.createTx(SOURCE_PAYER, List.of(proxiedIx()));
    final var mapped = mapper.mapTransactionWithTables(FEE_PAYER, null, source, null);
    assertArrayEquals(CHECKED_DATA, mapped.instructions().getFirst().data());
    assertNoTables(mapped);
  }

  @Test
  void mapTransactionWithTablesSingleTableDelegatesToTheSingleTablePath() {
    final var mapper = createMapper();
    final var added = table(1);
    final var source = Transaction.createTx(SOURCE_PAYER, List.of(proxiedIx()));
    final var addTables = new LookupTableAccountMeta[]{LookupTableAccountMeta.createMeta(added)};
    final var mapped = mapper.mapTransactionWithTables(FEE_PAYER, null, source, addTables);
    assertSame(added, mapped.lookupTable());
  }

  @Test
  void mapTransactionWithTablesAttachesAllTablesToAPlainTransaction() {
    final var mapper = createMapper();
    final var addTables = new LookupTableAccountMeta[]{
        LookupTableAccountMeta.createMeta(table(1)),
        LookupTableAccountMeta.createMeta(table(2))
    };
    final var source = Transaction.createTx(SOURCE_PAYER, List.of(proxiedIx()));
    final var mapped = mapper.mapTransactionWithTables(FEE_PAYER, null, source, addTables);
    assertArrayEquals(addTables, mapped.tableAccountMetas());
    assertArrayEquals(CHECKED_DATA, mapped.instructions().getFirst().data());
  }

  @Test
  void mapTransactionWithTablesPrependsTheSourceLookupTable() {
    final var mapper = createMapper();
    final var sourceTable = table(1);
    final var addTables = new LookupTableAccountMeta[]{
        LookupTableAccountMeta.createMeta(table(2)),
        LookupTableAccountMeta.createMeta(table(3))
    };
    final var source = Transaction.createTx(SOURCE_PAYER, List.of(proxiedIx()), sourceTable);
    final var mapped = mapper.mapTransactionWithTables(FEE_PAYER, null, source, addTables);
    final var tables = mapped.tableAccountMetas();
    assertEquals(3, tables.length);
    assertSame(sourceTable, tables[0].lookupTable());
    assertSame(addTables[0], tables[1]);
    assertSame(addTables[1], tables[2]);
  }

  @Test
  void mapTransactionWithTablesAppendsToExistingTableMetas() {
    final var mapper = createMapper();
    final var sourceMetas = new LookupTableAccountMeta[]{
        LookupTableAccountMeta.createMeta(table(1)),
        LookupTableAccountMeta.createMeta(table(2))
    };
    final var addTables = new LookupTableAccountMeta[]{
        LookupTableAccountMeta.createMeta(table(3)),
        LookupTableAccountMeta.createMeta(table(4))
    };
    final var source = Transaction.createTx(SOURCE_PAYER, List.of(proxiedIx()), sourceMetas);
    final var mapped = mapper.mapTransactionWithTables(FEE_PAYER, null, source, addTables);
    final var tables = mapped.tableAccountMetas();
    assertEquals(4, tables.length);
    assertSame(sourceMetas[0], tables[0]);
    assertSame(sourceMetas[1], tables[1]);
    assertSame(addTables[0], tables[2]);
    assertSame(addTables[1], tables[3]);
  }

  @Test
  void mapTransactionWithTablesDefaultsToTheTransactionsFeePayer() {
    final var mapper = createMapper();
    final var addTables = new LookupTableAccountMeta[]{
        LookupTableAccountMeta.createMeta(table(1)),
        LookupTableAccountMeta.createMeta(table(2))
    };
    final var source = Transaction.createTx(SOURCE_PAYER, List.of(proxiedIx()));
    final var mapped = mapper.mapTransactionWithTables(null, source, addTables);
    assertEquals(SOURCE_PAYER.publicKey(), mapped.feePayer().publicKey());
  }
}
