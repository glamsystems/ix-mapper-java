package systems.glam.ix.proxy;

import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.programs.Discriminator;
import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.ContextFieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.util.*;
import java.util.function.Function;

import static systems.comodal.jsoniter.JsonIterator.fieldEqualsIgnoreCase;

/// `programIdPlaceholderIndices` names the source-account slots whose account may carry the
/// source program id as an Anchor optional-account `None` sentinel; the mapper rewrites the
/// sentinel to the proxy program id so the proxy's own optional resolution reads `None`
/// (its handler re-synthesizes the source sentinel for the CPI). Flags are preserved and a
/// real account in the slot passes through untouched.
public record IxMapConfig(ProxyType proxyType,
                          String cpiIxName,
                          Discriminator cpiDiscriminator,
                          String proxyIxName,
                          Discriminator proxyDiscriminator,
                          List<DynamicAccountConfig> dynamicAccounts,
                          List<IndexedAccountMeta> staticAccounts,
                          int[] indexMap,
                          int[] programIdPlaceholderIndices) {

  private enum ProxyType {
    PAYER
  }

  public static IxMapConfig parseConfig(final Map<AccountMeta, AccountMeta> accountMetaCache,
                                        final Map<IndexedAccountMeta, IndexedAccountMeta> indexedAccountMetaCache,
                                        final JsonIterator ji) {
    return ji.testObject(new Parser(accountMetaCache, indexedAccountMetaCache), FIELDS, FIELD_PARSER).create();
  }

  public <A> IxProxy<A> createProxy(final AccountMeta invokedProxyProgram,
                                    final Function<DynamicAccountConfig, DynamicAccount<A>> accountMetaFactory) {
    if (proxyDiscriminator == null) {
      if (!staticAccounts.isEmpty()) {
        throw new IllegalStateException("Static accounts are not supported for IxMapConfig without a proxy discriminator.");
      }
      if (programIdPlaceholderIndices.length != 0) {
        throw new IllegalStateException("Program id placeholder indices are not supported for IxMapConfig without a proxy discriminator.");
      }
      final int numDynamicAccounts = dynamicAccounts.size();
      if (numDynamicAccounts == 0) {
        if (indexMap.length != 0) {
          throw new IllegalStateException("Index map is not supported for IxMapConfig without a proxy discriminator and no dynamic accounts.");
        }
        return new IdentityIxProxy<>(cpiDiscriminator);
      } else if (numDynamicAccounts == 1) {
        final var dynamicAccount = dynamicAccounts.getFirst();
        if (!dynamicAccount.writable() || !dynamicAccount.signer()) {
          throw new IllegalStateException("Invalid configuration: Dynamic fee payer account must be writable and signer.");
        }
        final long numRemoved = Arrays.stream(indexMap).filter(i -> i < 0).count();
        if (numRemoved != 1) {
          throw new IllegalStateException("Invalid configuration: Index map must remove exactly one payer account.");
        }
        if (indexMap[dynamicAccount.index()] >= 0) {
          throw new IllegalStateException("Invalid configuration: Payer is not removed in the index map.");
        }
        if (proxyType != null && proxyType != ProxyType.PAYER) {
          throw new IllegalStateException("Invalid configuration: Inferred proxy type was 'PAYER' but was " + proxyType);
        }
        return new PayerIxProxy<>(cpiDiscriminator, dynamicAccount.index());
      } else {
        throw new IllegalStateException("Invalid configuration: Only one or none dynamic accounts is supported for IxMapConfig without a proxy discriminator.");
      }
    } else {
      final boolean[] checkIndexes = new boolean[dynamicAccounts.size() + staticAccounts.size() + indexMap.length];
      for (final var account : dynamicAccounts) {
        final int index = account.index();
        if (checkIndexes[index]) {
          throw new IllegalStateException(String.format(
              "Duplicate index %d in dynamic accounts. CPI IX: %s, Proxy IX: %s",
              index, cpiIxName, proxyIxName
          ));
        } else {
          checkIndexes[index] = true;
        }
      }
      for (final var account : staticAccounts) {
        final int index = account.index();
        if (checkIndexes[index]) {
          throw new IllegalStateException(String.format(
              "Duplicate index %d in static accounts. CPI IX: %s, Proxy IX: %s",
              index, cpiIxName, proxyIxName
          ));
        } else {
          checkIndexes[index] = true;
        }
      }
      for (final var index : indexMap) {
        if (index >= 0) {
          if (checkIndexes[index]) {
            throw new IllegalStateException(String.format(
                "Duplicate index %d in index map. CPI IX: %s, Proxy IX: %s",
                index, cpiIxName, proxyIxName
            ));
          } else {
            checkIndexes[index] = true;
          }
        }
      }
      final boolean[] placeholderSlots = new boolean[indexMap.length];
      for (final var index : programIdPlaceholderIndices) {
        if (index < 0 || index >= indexMap.length) {
          throw new IllegalStateException(String.format(
              "Program id placeholder index %d is outside the index map. CPI IX: %s, Proxy IX: %s",
              index, cpiIxName, proxyIxName
          ));
        }
        if (indexMap[index] < 0) {
          throw new IllegalStateException(String.format(
              "Program id placeholder index %d maps to a dropped account. CPI IX: %s, Proxy IX: %s",
              index, cpiIxName, proxyIxName
          ));
        }
        if (placeholderSlots[index]) {
          throw new IllegalStateException(String.format(
              "Duplicate program id placeholder index %d. CPI IX: %s, Proxy IX: %s",
              index, cpiIxName, proxyIxName
          ));
        } else {
          placeholderSlots[index] = true;
        }
      }
      return IxProxy.createProxy(
          invokedProxyProgram,
          cpiDiscriminator,
          proxyDiscriminator,
          dynamicAccounts.stream().map(accountMetaFactory).toList(),
          staticAccounts,
          indexMap,
          programIdPlaceholderIndices
      );
    }
  }

  // Small value set: the linear ignore-case chain beats a matcher at this
  // size and keeps span access for the unknown-value error.
  private static final CharBufferFunction<ProxyType> PROXY_TYPE_PARSER = (buf, offset, len) -> {
    if (fieldEqualsIgnoreCase("payer", buf, offset, len)) {
      return ProxyType.PAYER;
    }
    throw new IllegalStateException("Unknown IxMapConfig proxy type " + new String(buf, offset, len));
  };

  private static final FieldMatcher FIELDS = FieldMatcher.of(
      "type",
      "src_ix_name",
      "src_discriminator",
      "dst_ix_name",
      "dst_discriminator",
      "dynamic_accounts",
      "static_accounts",
      "index_map",
      "program_id_placeholder_indices"
  );

  private static final ContextFieldIndexPredicate<Parser> FIELD_PARSER = (parser, fieldIndex, ji) -> {
    switch (fieldIndex) {
      case 0 -> parser.type = ji.applyChars(PROXY_TYPE_PARSER);
      case 1 -> parser.cpiIxName = ji.readString();
      case 2 -> parser.cpiDiscriminator = Discriminator.createDiscriminator(ji.readByteArray(8));
      case 3 -> parser.proxyIxName = ji.readString();
      case 4 -> parser.proxyDiscriminator = Discriminator.createDiscriminator(ji.readByteArray(8));
      case 5 -> {
        final var dynamicAccounts = ji.readList(DynamicAccountConfig::parseConfig);
        parser.dynamicAccounts = dynamicAccounts.isEmpty() ? Parser.NO_DYNAMIC_ACCOUNTS : List.copyOf(dynamicAccounts);
      }
      case 6 -> {
        final var staticAccounts = ji.readList(sub ->
            IndexedAccountMeta.parseConfig(parser.accountMetaCache, parser.indexedAccountMetaCache, sub));
        parser.staticAccounts = staticAccounts.isEmpty() ? Parser.NO_STATIC_ACCOUNTS : List.copyOf(staticAccounts);
      }
      case 7 -> parser.indexMap = ji.readIntArray();
      case 8 -> parser.programIdPlaceholderIndices = ji.readIntArray();
      default -> throw new IllegalStateException("Unknown IxMapConfig field at " + ji.currentBuffer());
    }
    return true;
  };

  private static final class Parser {

    private static final List<DynamicAccountConfig> NO_DYNAMIC_ACCOUNTS = List.of();
    private static final List<IndexedAccountMeta> NO_STATIC_ACCOUNTS = List.of();
    private static final int[] NO_INDEXES = new int[0];

    private final Map<AccountMeta, AccountMeta> accountMetaCache;
    private final Map<IndexedAccountMeta, IndexedAccountMeta> indexedAccountMetaCache;

    private ProxyType type;
    private String cpiIxName;
    private Discriminator cpiDiscriminator;
    private String proxyIxName;
    private Discriminator proxyDiscriminator;
    private List<DynamicAccountConfig> dynamicAccounts;
    private List<IndexedAccountMeta> staticAccounts;
    private int[] indexMap;
    private int[] programIdPlaceholderIndices;

    private Parser(final Map<AccountMeta, AccountMeta> accountMetaCache,
                   final Map<IndexedAccountMeta, IndexedAccountMeta> indexedAccountMetaCache) {
      this.accountMetaCache = accountMetaCache;
      this.indexedAccountMetaCache = indexedAccountMetaCache;
    }

    private IxMapConfig create() {
      return new IxMapConfig(
          type,
          cpiIxName,
          cpiDiscriminator,
          proxyIxName,
          proxyDiscriminator,
          dynamicAccounts == null ? NO_DYNAMIC_ACCOUNTS : dynamicAccounts,
          staticAccounts == null ? NO_STATIC_ACCOUNTS : staticAccounts,
          indexMap == null ? NO_INDEXES : indexMap,
          programIdPlaceholderIndices == null ? NO_INDEXES : programIdPlaceholderIndices
      );
    }
  }
}
