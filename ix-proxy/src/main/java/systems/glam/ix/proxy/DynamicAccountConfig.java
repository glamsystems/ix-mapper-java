package systems.glam.ix.proxy;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record DynamicAccountConfig(String name,
                                   int index,
                                   boolean writable,
                                   boolean signer) {

  public static DynamicAccountConfig parseConfig(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  public AccountMeta createMeta(final PublicKey publicKey) {
    return AccountMeta.createMeta(publicKey, writable, signer);
  }

  public <A> DynamicAccount<A> createFeePayerAccount() {
    return new IndexedFeePayer<>(index);
  }

  public <A> DynamicAccount<A> createReadCpiProgram() {
    return new IndexedReadOnlyProgram<>(index);
  }

  public <A> DynamicAccount<A> createReadIntegrationAuthority() {
    return new IndexedReadOnlyProgram<>(index);
  }

  private static final class Parser implements FieldBufferPredicate {

    private String name;
    private int index;
    private boolean writable;
    private boolean signer;

    private Parser() {
    }

    private DynamicAccountConfig create() {
      return new DynamicAccountConfig(name, index, writable, signer);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("name", buf, offset, len)) {
        name = ji.readString();
      } else if (fieldEquals("index", buf, offset, len)) {
        index = ji.readInt();
      } else if (fieldEquals("writable", buf, offset, len)) {
        writable = ji.readBoolean();
      } else if (fieldEquals("signer", buf, offset, len)) {
        signer = ji.readBoolean();
      } else {
        throw new IllegalStateException("Unknown ProgramAccountConfig field " + new String(buf, offset, len));
      }
      return true;
    }
  }
}
