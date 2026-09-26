package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;

import java.util.List;
import java.util.Objects;

/// What the mapper asks the context's supplier for, once per mapped instruction whose entry
/// lists supplied accounts: the roles, in the order the accounts are inserted, each with the
/// addresses found at its `of` positions, and the instruction itself for a supplier that reads
/// its data. The supplier answers with the accounts in the same order, the required ones
/// first; it may leave out a trailing run of optional ones. A runtime value, not part of the
/// document model: a missing argument is a `NullPointerException`, a blank name an
/// `IllegalArgumentException`, blank as the parser reads it (JavaScript's `trim`), so a name
/// the parser admitted is never refused here.
///
/// @param proxyProgram the GLAM program the mapped instruction targets
/// @param program      the source program
/// @param source       the entry's name, the source instruction
/// @param handler      the GLAM instruction that carries it
/// @param roles        the supplied accounts the entry lists, with their `of` addresses
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

  /// @param role     the entry's role label
  /// @param of       the addresses at the entry's `of` positions, in the entry's order
  /// @param optional the supplier may leave this account out
  public record Role(String role,
                     List<PublicKey> of,
                     boolean optional) {

    public Role {
      if (MappingDocumentParser.isBlank(Objects.requireNonNull(role, "role"))) {
        throw new IllegalArgumentException("role is blank");
      }
      of = List.copyOf(Objects.requireNonNull(of, "of"));
    }
  }
}
