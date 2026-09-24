package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Instruction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// The mapping rules over admitted documents, one instruction at a time, refusing with the
/// contract's messages; the refusal of an instruction it cannot read is this mapper's own.
final class DocumentMapper implements InstructionMapper {

  private final String environment;
  private final List<MappingDocument> documents;
  private final Map<PublicKey, MappingDocument> byProgram;

  private DocumentMapper(final String environment,
                         final List<MappingDocument> documents,
                         final Map<PublicKey, MappingDocument> byProgram) {
    this.environment = environment;
    this.documents = documents;
    this.byProgram = byProgram;
  }

  static DocumentMapper create(final Collection<MappingDocument> documents) {
    if (documents.isEmpty()) {
      throw new MappingDocumentException("mapper", "at least one mapping document is required");
    }
    for (final var document : documents) {
      if (document == null) {
        throw new MappingDocumentException("mapper", "a document is null");
      }
    }
    final var list = List.copyOf(documents);
    for (final var document : list) {
      MappingDocumentParser.checkDocument(document);
    }
    final var environment = list.getFirst().environment();
    final var byProgram = HashMap.<PublicKey, MappingDocument>newHashMap(list.size());
    for (final var document : list) {
      if (!document.environment().equals(environment)) {
        throw new MappingDocumentException(
            document.programId().toBase58(),
            "declares environment " + document.environment() + "; the mapper's is " + environment
        );
      }
      if (byProgram.putIfAbsent(document.programId(), document) != null) {
        throw new MappingDocumentException(document.programId().toBase58(), "two documents for one program");
      }
    }
    return new DocumentMapper(environment, list, byProgram);
  }

  @Override
  public String environment() {
    return environment;
  }

  @Override
  public List<MappingDocument> documents() {
    return documents;
  }

  @Override
  public MappingDocument documentOf(final PublicKey program) {
    return byProgram.get(program);
  }

  @Override
  public MapResult map(final Instruction instruction, final MappingContext context) {
    final var program = instruction.programId().publicKey();
    // an instruction that cannot be read is refused whatever its program: a transaction that
    // carries it cannot be rebuilt around it either
    final var data = instruction.data();
    final int offset = instruction.offset();
    final int len = instruction.len();
    if (offset < 0 || len < 0 || offset > data.length - len) {
      return new MapResult.Unsupported(program, null, UnsupportedReason.UNREADABLE_INSTRUCTION,
          "the instruction's data span (offset " + offset + ", length " + len + ") lies outside its buffer of " + data.length + " bytes");
    }
    final var accounts = instruction.accounts();
    for (int i = 0; i < accounts.size(); i++) {
      if (accounts.get(i) == null) {
        return new MapResult.Unsupported(program, null, UnsupportedReason.UNREADABLE_INSTRUCTION,
            "account " + i + " is unresolved; a lookup-table account must be loaded before mapping");
      }
    }
    final var document = byProgram.get(program);
    if (document == null) {
      return new MapResult.Passthrough(instruction, program, null, "the program has no mapping document");
    }
    final var entry = document.entryFor(data, offset, len);
    return switch (entry) {
      case null -> new MapResult.Unsupported(
          program, null, UnsupportedReason.UNKNOWN_INSTRUCTION,
          "no instruction of " + program.toBase58() + " matches the data"
      );
      case InstructionEntry.Passthrough passthrough ->
          new MapResult.Passthrough(instruction, program, passthrough.name(), passthrough.reason());
      case InstructionEntry.Unsupported unsupported -> new MapResult.Unsupported(
          program, unsupported.name(), UnsupportedReason.REFUSED_INSTRUCTION, unsupported.reason()
      );
      case InstructionEntry.Mapped mapped -> mapEntry(document, mapped, instruction, context);
    };
  }

  /// The address of a GLAM account from the context: the key, null when the context supplies
  /// none, or the exception a caller's integration-authority lookup threw.
  private sealed interface Resolved permits Resolved.Address, Resolved.Absent, Resolved.Failed {

    record Address(PublicKey address) implements Resolved {
    }

    record Absent() implements Resolved {
    }

    record Failed(String message) implements Resolved {
    }
  }

  private static Resolved resolve(final DynamicAccountName name,
                                  final MappingDocument document,
                                  final MappingContext context) {
    final PublicKey address;
    switch (name) {
      case GLAM_STATE -> address = context.glamState();
      case GLAM_VAULT -> address = context.glamVault();
      case GLAM_SIGNER -> address = context.glamSigner();
      case INTEGRATION_AUTHORITY -> {
        final var lookup = context.integrationAuthority();
        if (lookup == null) {
          return new Resolved.Absent();
        }
        try {
          address = lookup.apply(document.proxyProgramId());
        } catch (final Exception e) {
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
          final var message = e.getMessage();
          return new Resolved.Failed(message == null ? e.toString() : message);
        }
      }
      default -> throw new IllegalStateException("unreachable: " + name);
    }
    return address == null ? new Resolved.Absent() : new Resolved.Address(address);
  }

  private static MapResult mapEntry(final MappingDocument document,
                                    final InstructionEntry.Mapped entry,
                                    final Instruction instruction,
                                    final MappingContext context) {
    final var program = instruction.programId().publicKey();
    final var source = entry.name();
    final var proxy = document.proxyProgramId().toBase58();
    final var positions = entry.sourceAccounts();
    final var accounts = instruction.accounts();
    final int provided = accounts.size();
    final int listed = positions.size();

    for (int i = provided; i < listed; i++) {
      final var position = positions.get(i);
      if (position.optional() != OptionalKind.OMITTED) {
        return refuse(program, source, UnsupportedReason.ACCOUNT_COUNT,
            source + " needs account " + i + " (" + position.name() + "); the instruction carries " + provided);
      }
    }

    for (int i = 0, n = Math.min(provided, listed); i < n; i++) {
      final var position = positions.get(i);
      final var expect = position.expect();
      if (expect == null) {
        continue;
      }
      final PublicKey expected;
      switch (expect) {
        case Expectation.Address address -> expected = address.address();
        case Expectation.Dynamic dynamic -> {
          switch (resolve(dynamic.name(), document, context)) {
            case Resolved.Address resolved -> expected = resolved.address();
            case Resolved.Absent _ -> {
              return refuse(program, source, UnsupportedReason.CONTEXT,
                  "the context supplies no " + expect.jsonValue() + " for " + proxy);
            }
            case Resolved.Failed failed -> {
              return refuse(program, source, UnsupportedReason.CONTEXT,
                  "the context's integration authority failed for " + proxy + ": " + failed.message());
            }
          }
        }
      }
      if (!accounts.get(i).publicKey().equals(expected)) {
        return refuse(program, source, UnsupportedReason.ACCOUNT_EXPECTATION,
            source + " account " + i + " (" + position.name() + ") must be " + expect.jsonValue());
      }
    }

    final var seats = new ArrayList<>(entry.destinationAccounts());
    seats.sort(Comparator.comparingInt(DestinationAccount::index));
    final var mapped = new ArrayList<AccountMeta>(seats.size() + provided);
    for (final var seat : seats) {
      // the document orders omittable seats after every other and in source order, so an
      // absent one is followed only by absent ones and no seat shifts
      final boolean absent = seat instanceof DestinationAccount.Source forwarded && forwarded.source() >= provided;
      switch (seat) {
        case DestinationAccount.Dynamic dynamic -> {
          switch (resolve(dynamic.name(), document, context)) {
            case Resolved.Address resolved ->
                mapped.add(AccountMeta.createMeta(resolved.address(), seat.writable(), seat.signer()));
            case Resolved.Absent _ -> {
              return refuse(program, source, UnsupportedReason.CONTEXT,
                  "the context supplies no " + dynamic.name().jsonName() + " for " + proxy);
            }
            case Resolved.Failed failed -> {
              return refuse(program, source, UnsupportedReason.CONTEXT,
                  "the context's integration authority failed for " + proxy + ": " + failed.message());
            }
          }
        }
        case DestinationAccount.Static fixed ->
            mapped.add(AccountMeta.createMeta(fixed.address(), seat.writable(), seat.signer()));
        case DestinationAccount.Source forwarded -> {
          if (absent) {
            // an omittable optional the client left out
            continue;
          }
          final var account = accounts.get(forwarded.source());
          final var position = positions.get(forwarded.source());
          if (forwarded.sentinel() && account.publicKey().equals(program)) {
            // an absent optional of the handler's own: the proxy program's id, read-only and
            // unsigned, as Anchor clients pass one, whatever the seat declares for a present
            // account; the invoked program is never writable and never signs
            mapped.add(AccountMeta.createRead(document.proxyProgramId()));
            continue;
          }
          if (!position.dynamicSigner() && account.signer() != seat.signer()) {
            return refuse(program, source, UnsupportedReason.ACCOUNT_PRIVILEGE, seat.signer()
                ? source + " account " + forwarded.source() + " (" + position.name() + ") must sign"
                : source + " account " + forwarded.source() + " (" + position.name() + ") signs, but the handler takes it unsigned");
          }
          mapped.add(AccountMeta.createMeta(account.publicKey(), seat.writable(), seat.signer()));
        }
      }
    }

    if (provided > listed) {
      if (entry.remainingAccounts() == RemainingAccounts.NONE) {
        return refuse(program, source, UnsupportedReason.REMAINING_ACCOUNTS,
            source + " takes no accounts beyond its " + listed + "; the instruction carries " + provided);
      }
      for (int i = listed; i < provided; i++) {
        final var account = accounts.get(i);
        mapped.add(AccountMeta.createMeta(account.publicKey(), account.write(), account.signer()));
      }
    }

    final var handler = entry.handler();
    final int sourceDiscriminatorLength = entry.discriminator().length();
    final int payloadLength = instruction.len() - sourceDiscriminatorLength;
    final byte[] data = new byte[handler.discriminator().length() + payloadLength];
    handler.discriminator().write(data, 0);
    System.arraycopy(
        instruction.data(), instruction.offset() + sourceDiscriminatorLength,
        data, handler.discriminator().length(), payloadLength
    );
    return new MapResult.Mapped(
        Instruction.createInstruction(AccountMeta.createInvoked(document.proxyProgramId()), mapped, data),
        program,
        source,
        handler.name()
    );
  }

  private static MapResult.Unsupported refuse(final PublicKey program,
                                              final String source,
                                              final UnsupportedReason reason,
                                              final String message) {
    return new MapResult.Unsupported(program, source, reason, message);
  }
}
