package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;

import java.util.Arrays;
import java.util.List;

/// How an account the context supplies at an account index derives, as the handler's IDL
/// states it: the program the address derives under and the seeds, in order. The mapper
/// derives nothing: it hands the supplier the program and the seeds, each account seed
/// resolved to the address it placed at that account index
/// ([SuppliedAccountsRequest.Derivation]).
///
/// @param program the program the address derives under
/// @param seeds   the seeds, in order; possibly none
public record Derivation(PublicKey program, List<Derivation.Seed> seeds) {

  public Derivation {
    Records.require(program, "Derivation", "program");
    seeds = Records.copy(seeds, "Derivation", "seeds");
  }

  /// One seed: constant bytes, the address at an account index of the mapped instruction, or
  /// an argument of the instruction data by its path, which only a supplier that reads the
  /// data resolves.
  public sealed interface Seed permits Derivation.Const, Derivation.Account, Derivation.Arg {
  }

  /// Constant bytes. No seed is longer than 32 bytes: the parser refuses a document that
  /// carries one, and [InstructionMapper#createMapper] one built from records. The record
  /// holds its own copy of the bytes and hands out copies; two are equal when their bytes are.
  public record Const(byte[] value) implements Seed {

    public Const {
      Records.require(value, "Derivation.Const", "value");
      value = value.clone();
    }

    @Override
    public byte[] value() {
      return value.clone();
    }

    @Override
    public boolean equals(final Object other) {
      return other instanceof Const that && Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(value);
    }

    /// The bytes as the document spells them, 0 to 255.
    @Override
    public String toString() {
      return "Const[value=" + MappingDocumentParser.format(value) + "]";
    }
  }

  /// @param index an account index of the mapped instruction, of an account the mapper places
  ///              itself: none the context supplies, none a client may leave out
  public record Account(int index) implements Seed {

    public Account {
      Records.requireIndex(index, "Derivation.Account", "index");
    }
  }

  /// @param path the argument of the instruction data, as the IDL names it; the mapper does
  ///             not read it
  public record Arg(String path) implements Seed {

    public Arg {
      Records.requireName(path, "Derivation.Arg", "path");
    }
  }
}
