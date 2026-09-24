package systems.glam.ix.proxy;

import software.sava.core.programs.Discriminator;

import java.util.List;

/// The checks a record of the document model runs on construction, so that a document built
/// from records is as sound as a parsed one, field by field. The messages are this library's,
/// not the contract's: a parsed document never reaches them, since the parser refuses first
/// with the contract's words.
final class Records {

  private Records() {
  }

  static void require(final Object value, final String at, final String field) {
    if (value == null) {
      throw new MappingDocumentException(at, field + " is missing");
    }
  }

  static void requireName(final String value, final String at, final String field) {
    if (value == null || MappingDocumentParser.isBlank(value)) {
      throw new MappingDocumentException(at, field + " is missing or blank");
    }
  }

  /// The discriminator as this library's own copy: a caller's array, or a caller's
  /// implementation of the interface, cannot change it afterwards.
  static Discriminator discriminator(final Discriminator value, final String at, final String what) {
    final byte[] data = value == null ? null : value.data();
    if (data == null || data.length == 0) {
      throw new MappingDocumentException(at, what + " lacks a discriminator");
    }
    return Discriminator.createDiscriminator(data.clone());
  }

  /// Absent or non-blank.
  static void optionalName(final String value, final String at, final String field) {
    if (value != null && MappingDocumentParser.isBlank(value)) {
      throw new MappingDocumentException(at, field + " is blank");
    }
  }

  static void requireIndex(final int value, final String at, final String field) {
    if (value < 0) {
      throw new MappingDocumentException(at, field + " is negative");
    }
  }

  /// A copy the caller cannot change afterwards, with no null element.
  static <T> List<T> copy(final List<T> value, final String at, final String field) {
    require(value, at, field);
    for (final var element : value) {
      if (element == null) {
        throw new MappingDocumentException(at, field + " holds a null element");
      }
    }
    return List.copyOf(value);
  }
}
