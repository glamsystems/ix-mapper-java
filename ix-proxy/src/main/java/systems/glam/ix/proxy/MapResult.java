package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.Instruction;

/// What mapping one instruction produced: the GLAM instruction that carries it, the same
/// instruction when GLAM lets it through unchanged, or the reason it is refused. Every
/// outcome is a result; mapping throws nothing of its own.
public sealed interface MapResult permits MapResult.Mapped, MapResult.Passthrough, MapResult.Unsupported {

  /// The program of the instruction that was mapped.
  PublicKey program();

  /// The name of the document entry the instruction matched, or null when there is none: the
  /// program has no document, no entry matches, or the instruction cannot be read.
  String source();

  /// @param instruction the GLAM instruction, targeting the proxy program
  /// @param handler     the name of the GLAM instruction that carries it
  record Mapped(Instruction instruction,
                PublicKey program,
                String source,
                String handler) implements MapResult {
  }

  /// @param instruction the caller's own instruction object
  /// @param reason      why it rides along unchanged
  record Passthrough(Instruction instruction,
                     PublicKey program,
                     String source,
                     String reason) implements MapResult {
  }

  /// @param reason  what class of rule refused it
  /// @param message which account or rule, in the contract's words, or this mapper's own for
  ///                an instruction it cannot read
  record Unsupported(PublicKey program,
                     String source,
                     UnsupportedReason reason,
                     String message) implements MapResult {
  }
}
