package systems.glam.ix.proxy;

import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.programs.Discriminator;
import software.sava.core.tx.Instruction;

import java.util.List;

public interface IxProxy<A> {

  static <A> IxProxy<A> createProxy(final AccountMeta invokedProxyProgram,
                                    final Discriminator cpiDiscriminator,
                                    final Discriminator proxyDiscriminator,
                                    final List<DynamicAccount<A>> dynamicAccounts,
                                    final List<IndexedAccountMeta> staticAccounts,
                                    final int[] indexes) {
    return createProxy(
        invokedProxyProgram,
        cpiDiscriminator, proxyDiscriminator,
        dynamicAccounts, staticAccounts,
        indexes, new int[0]
    );
  }

  /// `programIdPlaceholderIndices` marks the source-account slots that may carry the source
  /// program id as an Anchor optional-account `None` sentinel; when they do, the mapped
  /// account is rewritten to the proxy program id (flags preserved) so the proxy's own
  /// optional resolution reads `None`.
  static <A> IxProxy<A> createProxy(final AccountMeta invokedProxyProgram,
                                    final Discriminator cpiDiscriminator,
                                    final Discriminator proxyDiscriminator,
                                    final List<DynamicAccount<A>> dynamicAccounts,
                                    final List<IndexedAccountMeta> staticAccounts,
                                    final int[] indexes,
                                    final int[] programIdPlaceholderIndices) {
    int numRemoved = 0;
    for (final int index : indexes) {
      if (index < 0) {
        ++numRemoved;
      }
    }
    final boolean[] placeholderSlots = new boolean[indexes.length];
    for (final int index : programIdPlaceholderIndices) {
      placeholderSlots[index] = true;
    }
    return new IxProxyRecord<>(
        invokedProxyProgram,
        cpiDiscriminator,
        proxyDiscriminator,
        dynamicAccounts,
        staticAccounts,
        indexes,
        placeholderSlots,
        dynamicAccounts.size() + staticAccounts.size() + (indexes.length - numRemoved)
    );
  }

  Instruction mapInstruction(final AccountMeta readCpiProgram,
                             final AccountMeta feePayer,
                             final A runtimeAccounts,
                             final Instruction instruction);

  /// Does not validate the expected program id or discriminators from the given instruction.
  Instruction mapInstructionUnchecked(final AccountMeta readCpiProgram,
                                      final AccountMeta feePayer,
                                      final A runtimeAccounts,
                                      final Instruction instruction);

  Discriminator cpiDiscriminator();

  boolean matchesCpiDiscriminator(final byte[] instructionData,
                                  final int offset,
                                  final int length);

  Discriminator proxyDiscriminator();
}
