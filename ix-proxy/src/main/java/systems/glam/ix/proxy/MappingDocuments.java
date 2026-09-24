package systems.glam.ix.proxy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/// Reading documents from files: one JSON document per file, named for its program.
public final class MappingDocuments {

  private MappingDocuments() {
  }

  /// Parses one file; the file name labels a refusal.
  public static MappingDocument read(final Path file) {
    final byte[] json;
    try {
      json = Files.readAllBytes(file);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return MappingDocumentParser.parse(json, file.getFileName().toString());
  }

  /// Every `*.json` file directly under the directory, in file-name order. Throws
  /// [MappingDocumentException] at the first file that does not admit.
  public static List<MappingDocument> readDirectory(final Path directory) {
    if (!Files.isDirectory(directory)) {
      throw new IllegalArgumentException("not a directory: " + directory);
    }
    // keyed by file name, so the documents come out in file-name order whatever order the
    // directory lists them in
    final var files = new TreeMap<Path, Path>();
    try (final var paths = Files.list(directory)) {
      paths.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".json"))
          .forEach(path -> files.put(path.getFileName(), path));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    final var documents = new ArrayList<MappingDocument>(files.size());
    for (final var file : files.values()) {
      documents.add(read(file));
    }
    return documents;
  }
}
