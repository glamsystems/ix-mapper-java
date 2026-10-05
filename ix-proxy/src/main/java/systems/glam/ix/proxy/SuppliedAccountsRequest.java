package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// What the mapper asks the context's supplier for, once per mapped instruction whose entry
/// lists supplied accounts or supplies one at an account index: the roles, those at an
/// account index first, in account-index order, each with its derivation resolved, then the
/// ones the entry lists, in the order the accounts are inserted, each with the addresses
/// found at its `of` positions; and the instruction itself for a supplier that reads its
/// data. The supplier answers with the accounts in the same order, the required ones first;
/// it may leave out a trailing run of optional ones. A runtime value, not part of the
/// document model: a missing argument is a `NullPointerException`, a blank name an
/// `IllegalArgumentException`, blank as the parser reads it (JavaScript's `trim`), so a name
/// the parser admitted is never refused here.
///
/// @param proxyProgram the GLAM program the mapped instruction targets
/// @param program      the source program
/// @param source       the entry's name, the source instruction
/// @param handler      the GLAM instruction that carries it
/// @param roles        the accounts the context supplies at an account index, then the
///                     supplied accounts the entry lists, with their `of` addresses
/// @param instruction  the source instruction as the caller passed it; its data is the span
///                     `data()[offset(), offset() + len())`, a parsed transaction's instruction
///                     being a span of the shared buffer
public record SuppliedAccountsRequest(PublicKey proxyProgram,
                                      PublicKey program,
                                      String source,
                                      String handler,
                                      List<Role> roles,
                                      Instruction instruction) {

  public SuppliedAccountsRequest {
    Objects.requireNonNull(proxyProgram, "proxyProgram");
    Objects.requireNonNull(program, "program");
    if (MappingDocumentParser.isBlank(Objects.requireNonNull(source, "source"))) {
      throw new IllegalArgumentException("source is blank");
    }
    if (MappingDocumentParser.isBlank(Objects.requireNonNull(handler, "handler"))) {
      throw new IllegalArgumentException("handler is blank");
    }
    roles = List.copyOf(Objects.requireNonNull(roles, "roles"));
    Objects.requireNonNull(instruction, "instruction");
  }

  /// @param role       the entry's role label
  /// @param of         the addresses at the entry's `of` positions, in the entry's order; none
  ///                   for an account at an account index
  /// @param optional   the supplier may leave this account out; never one at an account index
  /// @param derivation how an account at an account index derives, resolved; null when the
  ///                   document states none, and for an account the entry lists
  public record Role(String role,
                     List<PublicKey> of,
                     boolean optional,
                     Derivation derivation) {

    /// A role without a derivation, as an account the entry lists carries it.
    public Role(final String role, final List<PublicKey> of, final boolean optional) {
      this(role, of, optional, null);
    }

    public Role {
      if (MappingDocumentParser.isBlank(Objects.requireNonNull(role, "role"))) {
        throw new IllegalArgumentException("role is blank");
      }
      of = List.copyOf(Objects.requireNonNull(of, "of"));
    }
  }

  /// How an account at an account index derives, as the handler's IDL states it, resolved:
  /// the program the address derives under and the seeds, in order, each account seed
  /// replaced by the address the mapper placed at its account index.
  public record Derivation(PublicKey program, List<Seed> seeds) {

    public Derivation {
      Objects.requireNonNull(program, "program");
      seeds = List.copyOf(Objects.requireNonNull(seeds, "seeds"));
    }
  }

  /// One seed of a resolved [Derivation]: constant bytes, an address, or an argument of the
  /// instruction data by its path, which the mapper leaves to a supplier that reads the data.
  public sealed interface Seed permits Const, Account, Arg {
  }

  /// Constant bytes. The record holds its own copy and hands out copies; two are equal when
  /// their bytes are.
  public record Const(byte[] value) implements Seed {

    public Const {
      value = Objects.requireNonNull(value, "value").clone();
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

  /// @param address the address the mapper placed at the account index the seed names
  public record Account(PublicKey address) implements Seed {

    public Account {
      Objects.requireNonNull(address, "address");
    }
  }

  /// @param path the argument of the instruction data, as the IDL names it, unresolved
  public record Arg(String path) implements Seed {

    public Arg {
      if (MappingDocumentParser.isBlank(Objects.requireNonNull(path, "path"))) {
        throw new IllegalArgumentException("path is blank");
      }
    }
  }
}
