package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;

/// The account a mapper must find at a source position: a GLAM account by name, or an
/// address. The document spells both as one string; [#jsonValue()] is that spelling.
public sealed interface Expectation permits Expectation.Dynamic, Expectation.Address {

  String jsonValue();

  record Dynamic(DynamicAccountName name) implements Expectation {

    public Dynamic {
      Records.require(name, "Expectation.Dynamic", "name");
    }

    @Override
    public String jsonValue() {
      return name.jsonName();
    }
  }

  record Address(PublicKey address) implements Expectation {

    public Address {
      Records.require(address, "Expectation.Address", "address");
    }

    @Override
    public String jsonValue() {
      return address.toBase58();
    }
  }
}
