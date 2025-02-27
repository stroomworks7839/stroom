package stroom.db.util;

import org.jooq.Converter;

import java.io.Serial;
import java.util.UUID;

public class UUIDBinaryConverter implements Converter<byte[], UUID> {

    /**
     * Generated UID
     */
    @Serial
    private static final long serialVersionUID = -4543995777404574519L;

    @Override
    public UUID from(byte[] data) {
        if (data == null) {
            return null;
        }

        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (data[i] & 0xff);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (data[i] & 0xff);
        }

        return new UUID(msb, lsb);
    }

    @Override
    public byte[] to(UUID data) {
        if (data == null) {
            return null;
        }

        byte[] result = new byte[16];
        long msb = data.getMostSignificantBits();
        long lsb = data.getLeastSignificantBits();

        for (int i = 7; i >= 0; i--) {
            result[i] = (byte) (msb & 0xFF);
            msb >>= 8;
        }
        for (int i = 15; i >= 8; i--) {
            result[i] = (byte) (lsb & 0xFF);
            lsb >>= 8;
        }

        return result;
    }

    @Override
    public Class<byte[]> fromType() {
        return byte[].class;
    }

    @Override
    public Class<UUID> toType() {
        return UUID.class;
    }
}
