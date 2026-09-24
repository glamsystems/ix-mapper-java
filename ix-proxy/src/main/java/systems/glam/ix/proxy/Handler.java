package systems.glam.ix.proxy;

import software.sava.core.programs.Discriminator;

/// The GLAM instruction that carries a mapped instruction out, and its discriminator.
public record Handler(String name, Discriminator discriminator) {

  public Handler {
    Records.requireName(name, "Handler", "name");
    discriminator = Records.discriminator(discriminator, "Handler", "the handler");
  }
}
