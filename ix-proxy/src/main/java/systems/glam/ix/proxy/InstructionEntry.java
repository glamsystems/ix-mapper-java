package systems.glam.ix.proxy;

import software.sava.core.programs.Discriminator;

import java.util.List;

/// One instruction of the source program and its disposition: mapped onto a GLAM handler,
/// forwarded unchanged, or refused.
public sealed interface InstructionEntry
    permits InstructionEntry.Mapped, InstructionEntry.Passthrough, InstructionEntry.Unsupported {

  String name();

  Discriminator discriminator();

  record Mapped(String name,
                Discriminator discriminator,
                Handler handler,
                List<SourceAccount> sourceAccounts,
                List<DestinationAccount> destinationAccounts,
                RemainingAccounts remainingAccounts) implements InstructionEntry {

    public Mapped {
      Records.requireName(name, "InstructionEntry.Mapped", "name");
      discriminator = Records.discriminator(discriminator, "InstructionEntry.Mapped", name);
      Records.require(handler, "InstructionEntry.Mapped", "handler");
      sourceAccounts = Records.copy(sourceAccounts, "InstructionEntry.Mapped", "source_accounts");
      destinationAccounts = Records.copy(destinationAccounts, "InstructionEntry.Mapped", "destination_accounts");
      Records.require(remainingAccounts, "InstructionEntry.Mapped", "remaining_accounts");
    }
  }

  /// @param reason why the instruction rides along unchanged
  record Passthrough(String name,
                     Discriminator discriminator,
                     String reason) implements InstructionEntry {

    public Passthrough {
      Records.requireName(name, "InstructionEntry.Passthrough", "name");
      discriminator = Records.discriminator(discriminator, "InstructionEntry.Passthrough", name);
      Records.requireName(reason, "InstructionEntry.Passthrough", "reason");
    }
  }

  /// @param reason why the instruction is refused
  record Unsupported(String name,
                     Discriminator discriminator,
                     String reason) implements InstructionEntry {

    public Unsupported {
      Records.requireName(name, "InstructionEntry.Unsupported", "name");
      discriminator = Records.discriminator(discriminator, "InstructionEntry.Unsupported", name);
      Records.requireName(reason, "InstructionEntry.Unsupported", "reason");
    }
  }
}
