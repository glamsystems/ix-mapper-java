package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

record ProgramProxyMap<A>(PublicKey invokedProxyProgram,
                          Map<PublicKey, ProgramProxy<A>> programProxyMap) implements TransactionMapper<A> {

  @Override
  public ProgramProxy<A> programProxy(final PublicKey programId) {
    return programProxyMap.get(programId);
  }

  @Override
  public Instruction mapInstruction(final AccountMeta feePayer,
                                    final A runtimeAccounts,
                                    final Instruction instruction) {
    final var programProxy = programProxyMap.get(instruction.programId().publicKey());
    return programProxy == null ? instruction : programProxy.mapInstruction(feePayer, runtimeAccounts, instruction);
  }

  @Override
  public Instruction mapInstructionUnchecked(final AccountMeta feePayer,
                                             final A runtimeAccounts,
                                             final Instruction instruction) {
    final var programProxy = programProxyMap.get(instruction.programId().publicKey());
    return programProxy == null ? instruction : programProxy.mapInstructionUnchecked(feePayer, runtimeAccounts, instruction);
  }

  @Override
  public Instruction[] mapInstructions(final AccountMeta feePayer,
                                       final A runtimeAccounts,
                                       final List<Instruction> instructions) {
    final var mappedInstructions = new Instruction[instructions.size()];
    int i = 0;
    for (final var instruction : instructions) {
      mappedInstructions[i++] = mapInstruction(feePayer, runtimeAccounts, instruction);
    }
    return mappedInstructions;
  }

  @Override
  public Transaction mapTransaction(final AccountMeta feePayer,
                                    final A runtimeAccounts,
                                    final Transaction transaction) {
    final var mappedInstructions = Arrays.asList(mapInstructions(feePayer, runtimeAccounts, transaction.instructions()));
    final var table = transaction.lookupTable();
    if (table != null) {
      return Transaction.createTx(feePayer, mappedInstructions, table);
    }
    final var tables = transaction.tableAccountMetas();
    if (tables == null || tables.length == 0) {
      return Transaction.createTx(feePayer, mappedInstructions);
    } else {
      return Transaction.createTx(feePayer, mappedInstructions, tables);
    }
  }

  @Override
  public Transaction mapTransaction(final A runtimeAccounts, final Transaction transaction) {
    return mapTransaction(transaction.feePayer(), runtimeAccounts, transaction);
  }

  @Override
  public Transaction mapTransactionWithTable(final AccountMeta feePayer,
                                             final A runtimeAccounts,
                                             final Transaction transaction,
                                             final AddressLookupTable addTable) {
    if (addTable == null) {
      return mapTransaction(feePayer, runtimeAccounts, transaction);
    }
    final var mappedInstructions = Arrays.asList(mapInstructions(feePayer, runtimeAccounts, transaction.instructions()));
    final var table = transaction.lookupTable();
    if (table != null) {
      final var withTable = new LookupTableAccountMeta[2];
      withTable[0] = LookupTableAccountMeta.createMeta(table);
      withTable[1] = LookupTableAccountMeta.createMeta(addTable);
      return Transaction.createTx(feePayer, mappedInstructions, withTable);
    }
    final var tables = transaction.tableAccountMetas();
    if (tables == null || tables.length == 0) {
      return Transaction.createTx(feePayer, mappedInstructions, addTable);
    } else {
      final var withTable = Arrays.copyOf(tables, tables.length + 1);
      withTable[tables.length] = LookupTableAccountMeta.createMeta(addTable);
      return Transaction.createTx(feePayer, mappedInstructions, withTable);
    }
  }

  @Override
  public Transaction mapTransactionWithTable(final A runtimeAccounts,
                                             final Transaction transaction,
                                             final AddressLookupTable addTable) {
    return mapTransactionWithTable(transaction.feePayer(), runtimeAccounts, transaction, addTable);
  }

  @Override
  public Transaction mapTransactionWithTables(final AccountMeta feePayer,
                                              final A runtimeAccounts,
                                              final Transaction transaction,
                                              final LookupTableAccountMeta[] addTables) {
    if (addTables == null) {
      return mapTransaction(feePayer, runtimeAccounts, transaction);
    }
    final int numNewTables = addTables.length;
    if (numNewTables == 1) {
      return mapTransactionWithTable(feePayer, runtimeAccounts, transaction, addTables[0].lookupTable());
    }

    final var mappedInstructions = Arrays.asList(mapInstructions(feePayer, runtimeAccounts, transaction.instructions()));
    final var table = transaction.lookupTable();
    if (table != null) {
      final var withTable = new LookupTableAccountMeta[1 + numNewTables];
      withTable[0] = LookupTableAccountMeta.createMeta(table);
      System.arraycopy(addTables, 0, withTable, 1, numNewTables);
      return Transaction.createTx(feePayer, mappedInstructions, withTable);
    }
    final var tables = transaction.tableAccountMetas();
    if (tables == null || tables.length == 0) {
      return Transaction.createTx(feePayer, mappedInstructions, addTables);
    } else {
      final var withTables = new LookupTableAccountMeta[tables.length + numNewTables];
      System.arraycopy(tables, 0, withTables, 0, tables.length);
      System.arraycopy(addTables, 0, withTables, tables.length, numNewTables);
      return Transaction.createTx(feePayer, mappedInstructions, withTables);
    }
  }

  @Override
  public Transaction mapTransactionWithTables(final A runtimeAccounts,
                                              final Transaction transaction,
                                              final LookupTableAccountMeta[] addTables) {
    return mapTransactionWithTables(transaction.feePayer(), runtimeAccounts, transaction, addTables);
  }
}
