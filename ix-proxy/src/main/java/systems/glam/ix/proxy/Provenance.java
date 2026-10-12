package systems.glam.ix.proxy;

/// Where a document came from: the generator commit, the IDL stores by their IDL digest
/// (`glam managed <store> (idl <digest>)`; documents from older generators also name the
/// store revision) and the configuration revision. Every field but the configuration
/// revision may be absent (null); the names are carried, never parsed.
public record Provenance(String generator,
                         String sourceIdl,
                         String proxyIdl,
                         long configRevision) {

  /// Each name absent or non-blank, the revision positive, as the parser admits them.
  public Provenance {
    Records.optionalName(generator, "Provenance", "generator");
    Records.optionalName(sourceIdl, "Provenance", "source_idl");
    Records.optionalName(proxyIdl, "Provenance", "proxy_idl");
    if (configRevision < 1) {
      throw new MappingDocumentException("Provenance", "config_revision is not positive");
    }
  }
}
