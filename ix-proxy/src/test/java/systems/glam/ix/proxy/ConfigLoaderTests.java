package systems.glam.ix.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.sava.core.accounts.PublicKey;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.*;

/// [ConfigLoader] configuration parsing, the local-directory load path, and
/// the remote worker's fetch/retry loop — driven without sockets or real
/// waits via [StubHttpClient] and the [ConfigLoader.Sleeper] seam.
final class ConfigLoaderTests {

  private static PublicKey key(final int marker) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[0] = (byte) marker;
    return PublicKey.createPubKey(bytes);
  }

  private static String mappingConfig(final PublicKey programId, final int discriminator) {
    return """
        {"program_id": "%s", "instructions": [{"src_discriminator": [%d]}]}"""
        .formatted(programId.toBase58(), discriminator);
  }

  @Test
  void parsesTheLoaderConfiguration() {
    final var loader = ConfigLoader.parseConfig(JsonIterator.parse("""
        {
          "local_directory": "glam/mapping-configs",
          "configs": [
            {"uri": "https://example.com/a.json", "file_name": "a.json"},
            {"uri": "https://example.com/b.json", "file_name": "b.json"}
          ]
        }"""));

    assertEquals(Path.of("glam/mapping-configs"), loader.configDirectory());
    assertEquals(2, loader.remoteConfigs().size());
    assertTrue(loader.remoteConfigs().contains(
        new ConfigLoader.ConfigResource(URI.create("https://example.com/a.json"), "a.json")));
    assertTrue(loader.remoteConfigs().contains(
        new ConfigLoader.ConfigResource(URI.create("https://example.com/b.json"), "b.json")));
  }

  @Test
  void unknownConfigurationFieldsThrow() {
    assertThrows(IllegalStateException.class, () -> ConfigLoader.parseConfig(JsonIterator.parse("""
        {"bogus": 1}""")));
    assertThrows(IllegalStateException.class, () -> ConfigLoader.parseConfig(JsonIterator.parse("""
        {"configs": [{"uri": "https://example.com/a.json", "bogus": 1}]}""")));
  }

  @Test
  void loadsEveryJsonFileInTheLocalDirectory(@TempDir final Path configDirectory) throws Exception {
    Files.writeString(configDirectory.resolve("a.json"), mappingConfig(key(70), 1));
    Files.writeString(configDirectory.resolve("b.json"), mappingConfig(key(71), 2));
    Files.writeString(configDirectory.resolve("notes.txt"), "not a config");
    // a directory whose name matches the *.json filter must still be skipped
    Files.createDirectory(configDirectory.resolve("nested.json"));

    final var loader = new ConfigLoader(configDirectory, null);
    final var configs = loader.loadLocalConfigs();

    assertEquals(2, configs.size());
    assertEquals(2, configs.stream()
        .map(config -> config.programs().iterator().next().publicKey())
        .distinct()
        .count());
  }

  @Test
  void aMissingLocalDirectoryThrows(@TempDir final Path tempDir) {
    final var loader = new ConfigLoader(tempDir.resolve("does-not-exist"), null);
    assertThrows(IllegalStateException.class, loader::loadLocalConfigs);
  }

  @Test
  void aNullLocalDirectoryThrows() {
    final var loader = new ConfigLoader(null, null);
    assertThrows(IllegalStateException.class, loader::loadLocalConfigs);
  }

  @Test
  void noRemoteConfigsLoadsNothing() {
    assertTrue(new ConfigLoader(null, null)
        .loadRemoteConfigs(null, 1, null, false, java.time.Duration.ofSeconds(1), 1).isEmpty());
    assertTrue(new ConfigLoader(null, java.util.Set.of())
        .loadRemoteConfigs(null, 1, null, false, java.time.Duration.ofSeconds(1), 1).isEmpty());
  }

  @Test
  void cachingRemoteFilesRequiresAConfigDirectory() {
    final var remote = java.util.Set.of(
        new ConfigLoader.ConfigResource(URI.create("https://example.com/a.json"), "a.json"));
    final var loader = new ConfigLoader(null, remote);
    assertThrows(IllegalStateException.class, () ->
        loader.loadRemoteConfigs(null, 1, null, true, java.time.Duration.ofSeconds(1), 1));
  }

  // The two tests below pin the cacheFiles guard's pass-through directions
  // without any network: a null executor fails fast in supplyAsync with an
  // NPE, which is only reachable if the guard correctly did not throw.

  @Test
  void notCachingRemoteConfigsProceedsWithoutAConfigDirectory() {
    final var remote = java.util.Set.of(
        new ConfigLoader.ConfigResource(URI.create("https://example.com/a.json"), "a.json"));
    final var loader = new ConfigLoader(null, remote);
    assertThrows(NullPointerException.class, () ->
        loader.loadRemoteConfigs(null, 1, null, false, java.time.Duration.ofSeconds(1), 1));
  }

  @Test
  void cachingRemoteConfigsProceedsWithAConfigDirectory(@TempDir final Path configDirectory) {
    final var remote = java.util.Set.of(
        new ConfigLoader.ConfigResource(URI.create("https://example.com/a.json"), "a.json"));
    final var loader = new ConfigLoader(configDirectory, remote);
    assertThrows(NullPointerException.class, () ->
        loader.loadRemoteConfigs(null, 1, null, true, java.time.Duration.ofSeconds(1), 1));
  }

  /// Scripted [HttpClient]: serves each URI's canned bytes without a socket,
  /// after throwing [IOException] for the first `failuresBeforeSuccess`
  /// requests; a URI with no canned body always throws [IOException]. Nested
  /// in the test class so the mutation suite's `*Test*` exclusion covers it.
  private static final class StubHttpClient extends HttpClient {

    private final Map<URI, byte[]> bodies;
    private int remainingFailures;

    private StubHttpClient(final Map<URI, byte[]> bodies) {
      this(bodies, 0);
    }

    private StubHttpClient(final Map<URI, byte[]> bodies, final int failuresBeforeSuccess) {
      this.bodies = bodies;
      this.remainingFailures = failuresBeforeSuccess;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(final HttpRequest request,
                                    final HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
      if (remainingFailures > 0) {
        --remainingFailures;
        throw new IOException("Scripted failure for " + request.uri());
      }
      final byte[] body = bodies.get(request.uri());
      if (body == null) {
        throw new IOException("No canned body for " + request.uri());
      }
      return new HttpResponse<>() {
        @Override
        public int statusCode() {
          return 200;
        }

        @Override
        public HttpRequest request() {
          return request;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
          return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
          return HttpHeaders.of(Map.of(), (_, _) -> true);
        }

        @Override
        public T body() {
          return (T) body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
          return Optional.empty();
        }

        @Override
        public URI uri() {
          return request.uri();
        }

        @Override
        public Version version() {
          return Version.HTTP_1_1;
        }
      };
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                            final HttpResponse.BodyHandler<T> responseBodyHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                            final HttpResponse.BodyHandler<T> responseBodyHandler,
                                                            final HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      return null;
    }

    @Override
    public SSLParameters sslParameters() {
      return null;
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }
  }

  @Test
  void loadsAndCachesRemoteConfigs(@TempDir final Path configDirectory) throws Exception {
    final var uriA = URI.create("https://example.com/a.json");
    final var uriB = URI.create("https://example.com/b.json");
    final var remote = java.util.Set.of(
        new ConfigLoader.ConfigResource(uriA, "a.json"),
        new ConfigLoader.ConfigResource(uriB, "b.json"));
    final byte[] bodyA = mappingConfig(key(72), 3).getBytes(StandardCharsets.UTF_8);
    final byte[] bodyB = mappingConfig(key(73), 4).getBytes(StandardCharsets.UTF_8);
    final var httpClient = new StubHttpClient(Map.of(uriA, bodyA, uriB, bodyB));

    final var loader = new ConfigLoader(configDirectory, remote);
    try (final var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      final var configs = loader.loadRemoteConfigs(executor, 1, httpClient, true, Duration.ofSeconds(1), 0);
      assertEquals(2, configs.size());
      assertEquals(2, configs.stream()
          .map(config -> config.programs().iterator().next().publicKey())
          .distinct()
          .count());
    }

    assertArrayEquals(bodyA, Files.readAllBytes(configDirectory.resolve("a.json")));
    assertArrayEquals(bodyB, Files.readAllBytes(configDirectory.resolve("b.json")));
  }

  @Test
  void remoteConfigsAreNotCachedUnlessRequested(@TempDir final Path configDirectory) throws Exception {
    final var uri = URI.create("https://example.com/a.json");
    final var remote = java.util.Set.of(new ConfigLoader.ConfigResource(uri, "a.json"));
    final var httpClient = new StubHttpClient(Map.of(uri, mappingConfig(key(74), 5).getBytes(StandardCharsets.UTF_8)));

    final var loader = new ConfigLoader(configDirectory, remote);
    try (final var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      final var configs = loader.loadRemoteConfigs(executor, 1, httpClient, false, Duration.ofSeconds(1), 0);
      assertEquals(1, configs.size());
    }
    assertFalse(Files.exists(configDirectory.resolve("a.json")));
  }

  // ----- the Worker retry loop, run directly on the test thread -----

  private static ConfigLoader.Worker worker(final Queue<ConfigLoader.ConfigResource> workQueue,
                                            final HttpClient httpClient,
                                            final int maxRetries,
                                            final long maxDelayMillis,
                                            final ConfigLoader.Sleeper sleeper) {
    return new ConfigLoader.Worker(
        workQueue, httpClient, false, null, maxDelayMillis, maxRetries, sleeper, new HashMap<>(), new HashMap<>());
  }

  @Test
  void retriesBackOffOnTheOddSecondsScheduleCappedAtMaxDelay() throws Exception {
    final var uri = URI.create("https://example.com/a.json");
    final var httpClient = new StubHttpClient(
        Map.of(uri, mappingConfig(key(75), 6).getBytes(StandardCharsets.UTF_8)), 2);
    final var queue = new ArrayDeque<>(List.of(new ConfigLoader.ConfigResource(uri, "a.json")));
    final var delays = new ArrayList<Long>();

    final var results = worker(queue, httpClient, 2, 2_500, delays::add).get();

    assertEquals(1, results.size());
    assertEquals(key(75), results.getFirst().programs().iterator().next().publicKey());
    // (2n - 1) seconds per attempt — 1s then 3s — with the second capped at 2.5s
    assertEquals(List.of(1_000L, 2_500L), delays);
  }

  @Test
  void exhaustedRetriesRethrowTheFetchFailure() {
    final var uri = URI.create("https://example.com/a.json");
    final var queue = new ArrayDeque<>(List.of(new ConfigLoader.ConfigResource(uri, "a.json")));
    final var delays = new ArrayList<Long>();

    final var exhausted = worker(queue, new StubHttpClient(Map.of()), 2, 10_000, delays::add);
    assertThrows(UncheckedIOException.class, exhausted::get);
    // maxRetries sleeps happen; the failure after the last retry rethrows
    assertEquals(List.of(1_000L, 3_000L), delays);
  }

  @Test
  void interruptionDuringBackoffKeepsPartialResultsAndTheInterruptFlag() throws Exception {
    final var goodUri = URI.create("https://example.com/a.json");
    final var badUri = URI.create("https://example.com/b.json");
    final var httpClient = new StubHttpClient(
        Map.of(goodUri, mappingConfig(key(76), 7).getBytes(StandardCharsets.UTF_8)));
    final var queue = new ArrayDeque<>(List.of(
        new ConfigLoader.ConfigResource(goodUri, "a.json"),
        new ConfigLoader.ConfigResource(badUri, "b.json")));

    final var results = worker(queue, httpClient, 5, 10_000, millis -> {
      throw new InterruptedException();
    }).get();

    // interrupted() both asserts the worker restored the flag and clears it
    // so it cannot leak into later tests
    assertTrue(Thread.interrupted());
    assertEquals(1, results.size());
    assertEquals(key(76), results.getFirst().programs().iterator().next().publicKey());
  }

  @Test
  void remoteWorkersRunOnTheExecutor() throws Exception {
    final var remote = java.util.Set.of(
        new ConfigLoader.ConfigResource(URI.create("https://example.com/a.json"), "a.json"));
    final var loader = new ConfigLoader(null, remote);
    try (final var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      // the worker runs and fails fast on the null HttpClient before any
      // request could be formed, proving a real worker reached the executor
      assertThrows(java.util.concurrent.CompletionException.class, () ->
          loader.loadRemoteConfigs(executor, 1, null, false, java.time.Duration.ofSeconds(1), 1));
    }
  }
}
