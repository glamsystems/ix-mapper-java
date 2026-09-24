package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/// A mapper over documents of one environment, each of which passed the parser's checks
/// ([MappingDocumentParser], or the same checks at creation for a document built from
/// records). An instruction of a program with no document passes through: the program is not
/// one GLAM proxies. An instruction of a documented program follows its entry, and one no
/// entry matches is refused. An instruction that cannot be read (a data span outside its
/// buffer, an account a transaction left unresolved) is refused whatever its program.
/// Mapping throws nothing of its own; every outcome is a [MapResult]. (An `Error` from the
/// caller's integration-authority lookup propagates.)
public interface InstructionMapper {

  /// Throws [MappingDocumentException] for a document that does not pass the checks a parsed
  /// one did, or a set that forms no mapper: none, two environments, or two documents for one
  /// program.
  static InstructionMapper createMapper(final Collection<MappingDocument> documents) {
    return DocumentMapper.create(documents);
  }

  String environment();

  List<MappingDocument> documents();

  /// The document of a program, or null when the mapper holds none for it.
  MappingDocument documentOf(final PublicKey program);

  /// Maps one instruction; every outcome is a result.
  MapResult map(final Instruction instruction, final MappingContext context);

  /// Maps each instruction; one result per instruction, in order.
  default List<MapResult> map(final List<Instruction> instructions, final MappingContext context) {
    final var results = new ArrayList<MapResult>(instructions.size());
    for (final var instruction : instructions) {
      results.add(map(instruction, context));
    }
    return results;
  }

  /// The transaction with every mapped instruction replaced in place, as sava's
  /// `replaceInstruction` rebuilds it: over the same fee payer, the same recent blockhash and
  /// the lookup-table objects the transaction holds (a v0 transaction that holds none
  /// rebuilds as legacy), and for a v1 transaction with its version and its priority fee,
  /// compute-unit, heap and data-size settings. Every instruction is mapped before any is
  /// replaced, since a rebuild carries the whole list. Throws
  /// [UnsupportedInstructionException] at the first refusal, an unreadable instruction
  /// included: a transaction cannot carry one, so a caller who wants every result maps the
  /// instructions.
  ///
  /// The rebuild itself is sava's. A transaction the mapped instructions cannot form throws
  /// sava's own `IllegalStateException` or `IllegalArgumentException`, for example a v1
  /// transaction pushed past 64 accounts by the seats a mapping adds, a heap size sava's
  /// strict v1 builder refuses, or an instruction whose program is the fee payer. Each
  /// mapped instruction is replaced in its own rebuild, and sava checks every intermediate
  /// transaction, in which a source program and its proxy are both referenced until the
  /// last instruction of that program is replaced: a v1 transaction whose fully mapped form
  /// is at or near a limit can be refused on the way there. When anything was mapped, the
  /// result carries no signatures; when nothing was, it is the caller's own transaction
  /// object, signatures and all.
  default Transaction mapTransaction(final Transaction transaction, final MappingContext context) {
    final var instructions = transaction.instructions();
    final var results = map(instructions, context);
    for (int i = 0; i < results.size(); i++) {
      if (results.get(i) instanceof MapResult.Unsupported result) {
        throw new UnsupportedInstructionException(i, instructions.get(i), result);
      }
    }
    var mapped = transaction;
    for (int i = 0; i < results.size(); i++) {
      if (results.get(i) instanceof MapResult.Mapped result) {
        mapped = mapped.replaceInstruction(i, result.instruction());
      }
    }
    return mapped;
  }
}
