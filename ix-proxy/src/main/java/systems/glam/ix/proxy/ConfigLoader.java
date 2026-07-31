package systems.glam.ix.proxy;

import software.sava.core.accounts.meta.AccountMeta;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static java.nio.file.StandardOpenOption.*;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record ConfigLoader(Path configDirectory, Set<ConfigResource> remoteConfigs) {

  private static final System.Logger logger = System.getLogger(ConfigLoader.class.getName());

  public List<ProgramMapConfig> loadLocalConfigs() {
    if (configDirectory == null) {
      throw new IllegalStateException("No local config directory specified.");
    }
    if (!Files.isDirectory(configDirectory)) {
      throw new IllegalStateException(String.format("Local config directory %s does not exist.", configDirectory));
    }

    final var accountMetaCache = new HashMap<AccountMeta, AccountMeta>(256);
    final var indexedAccountMetaCache = new HashMap<IndexedAccountMeta, IndexedAccountMeta>(256);

    try (final var paths = Files.walk(configDirectory)) {
      final var configFiles = paths
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".json"))
          .toList();
      final var configs = new ArrayList<ProgramMapConfig>(configFiles.size());
      for (final var configFile : configFiles) {
        final byte[] configData = Files.readAllBytes(configFile);
        final var ji = JsonIterator.parse(configData);
        final var programMapConfig = ProgramMapConfig.parseConfig(accountMetaCache, indexedAccountMetaCache, ji);
        configs.add(programMapConfig);
      }
      return configs;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public List<ProgramMapConfig> loadRemoteConfigs(final ExecutorService executorService,
                                                  final int numThreads,
                                                  final HttpClient httpClient,
                                                  final boolean cacheFiles,
                                                  final Duration maxDelay,
                                                  final int maxRetries) {
    return loadRemoteConfigs(executorService, numThreads, httpClient, cacheFiles, maxDelay, maxRetries, Thread::sleep);
  }

  /// Retry backoff pause, injectable so tests can drive the retry loop
  /// without real waits.
  @FunctionalInterface
  interface Sleeper {

    void sleep(final long millis) throws InterruptedException;
  }

  List<ProgramMapConfig> loadRemoteConfigs(final ExecutorService executorService,
                                           final int numThreads,
                                           final HttpClient httpClient,
                                           final boolean cacheFiles,
                                           final Duration maxDelay,
                                           final int maxRetries,
                                           final Sleeper sleeper) {
    if (remoteConfigs != null && !remoteConfigs.isEmpty()) {
      if (cacheFiles && configDirectory == null) {
        throw new IllegalStateException("configDirectory must not be null when cacheFiles is true.");
      }
      final var accountMetaCache = new HashMap<AccountMeta, AccountMeta>(256);
      final var indexedAccountMetaCache = new HashMap<IndexedAccountMeta, IndexedAccountMeta>(256);

      final int numRemoteConfigs = remoteConfigs.size();
      final var workQueue = new ArrayBlockingQueue<ConfigResource>(numRemoteConfigs);
      workQueue.addAll(remoteConfigs);
      final long maxDelayMillis = maxDelay.toMillis();
      final var futureResults = IntStream.range(0, numThreads)
          .mapToObj(i -> new Worker(workQueue, httpClient, cacheFiles, configDirectory, maxDelayMillis, maxRetries, sleeper, accountMetaCache, indexedAccountMetaCache))
          .map(worker -> CompletableFuture.supplyAsync(worker, executorService))
          .toList();

      final var results = new ArrayList<ProgramMapConfig>(numRemoteConfigs);
      for (final var futureResult : futureResults) {
        results.addAll(futureResult.join());
      }
      return results;
    } else {
      return List.of();
    }
  }

  record Worker(Queue<ConfigResource> workQueue,
                HttpClient httpClient,
                boolean cacheFiles,
                Path configDirectory,
                long maxDelayMillis,
                int maxRetries,
                Sleeper sleeper,
                Map<AccountMeta, AccountMeta> accountMetaCache,
                Map<IndexedAccountMeta, IndexedAccountMeta> indexedAccountMetaCache) implements Supplier<List<ProgramMapConfig>> {


    @Override
    public List<ProgramMapConfig> get() {
      final var results = new ArrayList<ProgramMapConfig>();
      try {
        for (; ; ) {
          final var configResource = workQueue.poll();
          if (configResource == null) {
            return results;
          }
          final var request = HttpRequest.newBuilder(configResource.uri).GET().build();
          for (long errorCount = 0; ; ) {

            final byte[] responseData;
            try {
              final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
              responseData = response.body();
            } catch (final IOException e) {
              if (++errorCount > maxRetries) {
                throw new UncheckedIOException(e);
              }
              final long delayMillis = Math.min(maxDelayMillis, TimeUnit.SECONDS.toMillis((errorCount << 1) - 1));
              logger.log(System.Logger.Level.WARNING, String.format("""
                      Failed %d time(s) to fetch remote config %s.
                      Retrying in %dms.
                      """, errorCount, configResource, delayMillis
                  )
              );
              sleeper.sleep(delayMillis);
              continue;
            }

            final var ji = JsonIterator.parse(responseData);
            final var programMapConfig = ProgramMapConfig.parseConfig(accountMetaCache, indexedAccountMetaCache, ji);
            results.add(programMapConfig);

            if (cacheFiles) {
              final var configFile = configDirectory.resolve(configResource.fileName);
              try {
                Files.write(configFile, responseData, CREATE, WRITE, TRUNCATE_EXISTING);
              } catch (final IOException e) {
                throw new UncheckedIOException(e);
              }
            }
            break;
          }
        }
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return results;
      }
    }
  }

  public record ConfigResource(URI uri, String fileName) {

  }

  public static ConfigLoader parseConfig(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class ConfigResourceParser implements FieldBufferPredicate {

    private URI uri;
    private String fileName;

    ConfigResourceParser() {
    }

    ConfigResource create() {
      return new ConfigResource(uri, fileName);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("uri", buf, offset, len)) {
        this.uri = URI.create(ji.readString());
      } else if (fieldEquals("file_name", buf, offset, len)) {
        this.fileName = ji.readString();
      } else {
        throw new IllegalStateException("Unknown configuration field " + new String(buf, offset, len));
      }
      return true;
    }
  }

  private static final class Parser implements FieldBufferPredicate {

    private Path configDirectory;
    private Set<ConfigResource> configs;

    Parser() {
    }

    ConfigLoader create() {
      return new ConfigLoader(configDirectory, configs);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("local_directory", buf, offset, len)) {
        this.configDirectory = Path.of(ji.readString());
      } else if (fieldEquals("configs", buf, offset, len)) {
        final var configs = new HashSet<ConfigResource>();
        while (ji.readArray()) {
          final var parser = new ConfigResourceParser();
          ji.testObject(parser);
          configs.add(parser.create());
        }
        this.configs = configs;
      } else {
        throw new IllegalStateException("Unknown configuration field " + new String(buf, offset, len));
      }
      return true;
    }
  }
}
