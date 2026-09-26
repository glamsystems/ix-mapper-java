package systems.glam.ix.proxy;

import java.util.List;

/// One account the caller supplies to a mapped instruction, inserted after the seats and
/// before the accounts beyond the list, read-only and unsigned: a GLAM-side account the
/// handler reads from its remaining accounts that a native instruction never carries (a
/// price oracle, a strategy's market). The mapper interprets no role; it hands the role and
/// the addresses at the `of` positions to the context's supplier and inserts what comes back.
///
/// @param role     what the account is, in the caller's vocabulary; never blank
/// @param of       source positions whose addresses the supplier receives with the role (the
///                 mints a price oracle is for), each inside the entry's source accounts
/// @param optional the supplier may leave it out; optional accounts trail the required ones
public record SuppliedAccount(String role,
                              List<Integer> of,
                              boolean optional) {

  public SuppliedAccount {
    Records.requireName(role, "SuppliedAccount", "role");
    of = Records.copy(of, "SuppliedAccount", "of");
    for (final var position : of) {
      Records.requireIndex(position, "SuppliedAccount", "of");
    }
  }
}
