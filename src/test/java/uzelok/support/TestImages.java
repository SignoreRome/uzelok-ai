package uzelok.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class TestImages {

    private TestImages() {
    }

    public static byte[] jpeg(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        out.write(new byte[] {
                (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
                'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00,
                0x00, 0x01, 0x00, 0x01, 0x00, 0x00
        }, 0, 18);
        out.write(0xFF);
        out.write(0xC0);
        out.write(0x00);
        out.write(0x11);
        out.write(0x08);
        out.write((height >> 8) & 0xFF);
        out.write(height & 0xFF);
        out.write((width >> 8) & 0xFF);
        out.write(width & 0xFF);
        out.write(0x03);
        out.write(new byte[] {
                0x01, 0x22, 0x00,
                0x02, 0x11, 0x01,
                0x03, 0x11, 0x01
        }, 0, 9);
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    public static byte[] png(int width, int height) {
        byte[] result = new byte[33];
        result[0] = (byte) 0x89;
        result[1] = 'P';
        result[2] = 'N';
        result[3] = 'G';
        result[4] = 0x0D;
        result[5] = 0x0A;
        result[6] = 0x1A;
        result[7] = 0x0A;
        writeIntBigEndian(result, 8, 13);
        result[12] = 'I';
        result[13] = 'H';
        result[14] = 'D';
        result[15] = 'R';
        writeIntBigEndian(result, 16, width);
        writeIntBigEndian(result, 20, height);
        result[24] = 8;
        result[25] = 2;
        result[26] = 0;
        result[27] = 0;
        result[28] = 0;
        return result;
    }

    public static byte[] webp(int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[] {'R', 'I', 'F', 'F'});
        buffer.putInt(22);
        buffer.put(new byte[] {'W', 'E', 'B', 'P'});
        buffer.put(new byte[] {'V', 'P', '8', 'X'});
        buffer.putInt(10);
        buffer.put(new byte[] {0, 0, 0, 0});
        putUInt24(buffer, width - 1);
        putUInt24(buffer, height - 1);
        return buffer.array();
    }

    public static byte[] pdf(String content) {
        return ("%PDF-1.7\n" + content + "\n%%EOF").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    public static byte[] text(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeIntBigEndian(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static void putUInt24(ByteBuffer buffer, int value) {
        buffer.put((byte) (value & 0xFF));
        buffer.put((byte) ((value >> 8) & 0xFF));
        buffer.put((byte) ((value >> 16) & 0xFF));
    }
}
