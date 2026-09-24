package systems.glam.ix.proxy;

import software.sava.core.tx.Instruction;

/// Thrown by [InstructionMapper#mapTransaction] when an instruction of the transaction is
/// refused: a transaction cannot carry a refusal, so the caller who wants one result per
/// instruction maps the instructions instead.
public final class UnsupportedInstructionException extends RuntimeException {

  private final int index;
  private final Instruction instruction;
  private final MapResult.Unsupported result;

  public UnsupportedInstructionException(final int index,
                                         final Instruction instruction,
                                         final MapResult.Unsupported result) {
    super("instruction " + index + " of " + result.program().toBase58() + " is unsupported ("
        + result.reason().jsonName() + "): " + result.message());
    this.index = index;
    this.instruction = instruction;
    this.result = result;
  }

  /// The position of the refused instruction in the transaction.
  public int index() {
    return index;
  }

  public Instruction instruction() {
    return instruction;
  }

  public MapResult.Unsupported result() {
    return result;
  }
}
