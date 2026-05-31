package systems.glam.ix.proxy;

import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.programs.Discriminator;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.*;
import java.util.function.Function;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record IxMapConfig(ProxyType proxyType,
                          String cpiIxName,
                          Discriminator cpiDiscriminator,
                          String proxyIxName,
                          Discriminator proxyDiscriminator,
                          List<DynamicAccountConfig> dynamicAccounts,
                          List<IndexedAccountMeta> staticAccounts,
                          int[] indexMap) {

  private enum ProxyType {
    PAYER
  }

  public static IxMapConfig parseConfig(final Map<AccountMeta, AccountMeta> accountMetaCache,
                                        final Map<IndexedAccountMeta, IndexedAccountMeta> indexedAccountMetaCache,
                                        final JsonIterator ji) {
    final var parser = new Parser(accountMetaCache, indexedAccountMetaCache);
    ji.testObject(parser);
    return parser.create();
  }

  public <A> IxProxy<A> createProxy(final AccountMeta invokedProxyProgram,
                                    final Function<DynamicAccountConfig, DynamicAccount<A>> accountMetaFactory) {
    if (proxyDiscriminator == null) {
      if (!staticAccounts.isEmpty()) {
        throw new IllegalStateException("Static accounts are not supported for IxMapConfig without a proxy discriminator.");
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
      return IxProxy.createProxy(
          invokedProxyProgram,
          cpiDiscriminator,
          proxyDiscriminator,
          dynamicAccounts.stream().map(accountMetaFactory).toList(),
          staticAccounts,
          indexMap
      );
    }
  }

  private static final class Parser implements FieldBufferPredicate {

    private static final List<DynamicAccountConfig> NO_DYNAMIC_ACCOUNTS = List.of();
    private static final List<IndexedAccountMeta> NO_STATIC_ACCOUNTS = List.of();
    private static final int[] NO_INDEX_MAP = new int[0];

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
          indexMap == null ? NO_INDEX_MAP : indexMap
      );
    }

    private static Discriminator parseDiscriminator(final JsonIterator ji) {
      int i = 0;
      final int mark = ji.mark();
      while (ji.readArray()) {
        ji.skip();
        ++i;
      }
      final byte[] discriminator = new byte[i];
      ji.reset(mark);
      for (i = 0; ji.readArray(); ++i) {
        discriminator[i] = (byte) ji.readInt();
      }
      return Discriminator.createDiscriminator(discriminator);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("type", buf, offset, len)) {
        type = ProxyType.valueOf(ji.readString().toUpperCase(Locale.ENGLISH));
      } else if (fieldEquals("src_ix_name", buf, offset, len)) {
        cpiIxName = ji.readString();
      } else if (fieldEquals("src_discriminator", buf, offset, len)) {
        cpiDiscriminator = parseDiscriminator(ji);
      } else if (fieldEquals("dst_ix_name", buf, offset, len)) {
        proxyIxName = ji.readString();
      } else if (fieldEquals("dst_discriminator", buf, offset, len)) {
        proxyDiscriminator = parseDiscriminator(ji);
      } else if (fieldEquals("dynamic_accounts", buf, offset, len)) {
        final var dynamicAccounts = new ArrayList<DynamicAccountConfig>();
        while (ji.readArray()) {
          dynamicAccounts.add(DynamicAccountConfig.parseConfig(ji));
        }
        this.dynamicAccounts = dynamicAccounts.isEmpty() ? NO_DYNAMIC_ACCOUNTS : List.copyOf(dynamicAccounts);
      } else if (fieldEquals("static_accounts", buf, offset, len)) {
        final var staticAccounts = new ArrayList<IndexedAccountMeta>();
        while (ji.readArray()) {
          staticAccounts.add(IndexedAccountMeta.parseConfig(accountMetaCache, indexedAccountMetaCache, ji));
        }
        this.staticAccounts = staticAccounts.isEmpty() ? NO_STATIC_ACCOUNTS : List.copyOf(staticAccounts);
      } else if (fieldEquals("index_map", buf, offset, len)) {
        int i = 0;
        final int mark = ji.mark();
        for (; ji.readArray(); ++i) {
          ji.skip();
        }
        if (i > 0) {
          ji.reset(mark);
          final int[] indexMap = new int[i];
          for (i = 0; ji.readArray(); ++i) {
            indexMap[i] = ji.readInt();
          }
          this.indexMap = indexMap;
        } else {
          this.indexMap = NO_INDEX_MAP;
        }
      } else if (fieldEquals("program_id_placeholder_indices", buf, offset, len)) {
        ji.skip();
      } else {
        throw new IllegalStateException("Unknown IxMapConfig field " + new String(buf, offset, len));
      }
      return true;
    }
  }
}
