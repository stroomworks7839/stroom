package stroom.query.language.functions.ref;

import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValSerialiser;

import java.util.ArrayList;
import java.util.List;

public class ValListReference implements ValueReference<List<Val>> {

    private final int index;
    private final String name;

    ValListReference(final int index, final String name) {
        this.index = index;
        this.name = name;
    }

    @Override
    public List<Val> get(final StoredValues storedValues) {
        final Object o = storedValues.get(index);
        if (o == null) {
            return new ArrayList<>();
        }
        return (List<Val>) o;
    }

    @Override
    public void set(final StoredValues storedValues, final List<Val> value) {
        storedValues.set(index, value);
    }

    @Override
    public void read(final StoredValues storedValues, final DataReader reader) {
        final int length = reader.readInt();
        final List<Val> list = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            list.add(ValSerialiser.read(reader));
        }
        set(storedValues, list);
    }

    @Override
    public void write(final StoredValues storedValues, final DataWriter writer) {
        final List<Val> list = get(storedValues);
        writer.writeInt(list.size());
        for (final Val val : list) {
            ValSerialiser.write(writer, val);
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
