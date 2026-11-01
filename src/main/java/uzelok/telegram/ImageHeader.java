package uzelok.telegram;

import java.util.Optional;

public final class ImageHeader {

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private ImageHeader() {
    }

    public static Optional<ImageSize> read(byte[] bytes, String mime) {
        if (bytes == null || bytes.length < 4 || mime == null) {
            return Optional.empty();
        }
        return switch (mime) {
            case "image/jpeg" -> jpeg(bytes);
            case "image/png" -> png(bytes);
            case "image/webp" -> webp(bytes);
            default -> Optional.empty();
        };
    }

    private static Optional<ImageSize> png(byte[] bytes) {
        if (bytes.length < 24) {
            return Optional.empty();
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                return Optional.empty();
            }
        }
        if (bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') {
            return Optional.empty();
        }
        int width = readIntBigEndian(bytes, 16);
        int height = readIntBigEndian(bytes, 20);
        return dimension(width, height);
    }

    private static Optional<ImageSize> jpeg(byte[] bytes) {
        int index = 2;
        while (index + 3 < bytes.length) {
            if ((bytes[index] & 0xFF) != 0xFF) {
                index++;
                continue;
            }
            int marker = bytes[index + 1] & 0xFF;
            if (marker == 0xFF) {
                index++;
                continue;
            }
            if (marker == 0xD8 || marker == 0xD9 || (marker >= 0xD0 && marker <= 0xD7)) {
                index += 2;
                continue;
            }
            if (index + 3 >= bytes.length) {
                return Optional.empty();
            }
            int length = ((bytes[index + 2] & 0xFF) << 8) | (bytes[index + 3] & 0xFF);
            if (length < 2 || index + 2 + length > bytes.length) {
                return Optional.empty();
            }
            if (isStartOfFrame(marker)) {
                if (length < 7) {
                    return Optional.empty();
                }
                int height = ((bytes[index + 5] & 0xFF) << 8) | (bytes[index + 6] & 0xFF);
                int width = ((bytes[index + 7] & 0xFF) << 8) | (bytes[index + 8] & 0xFF);
                return dimension(width, height);
            }
            if (marker == 0xDA) {
                return Optional.empty();
            }
            index += 2 + length;
        }
        return Optional.empty();
    }

    private static Optional<ImageSize> webp(byte[] bytes) {
        if (bytes.length < 30) {
            return Optional.empty();
        }
        if (bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F'
                || bytes[8] != 'W' || bytes[9] != 'E' || bytes[10] != 'B' || bytes[11] != 'P') {
            return Optional.empty();
        }
        String chunk = new String(bytes, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return switch (chunk) {
            case "VP8X" -> {
                int width = 1 + (bytes[24] & 0xFF) + ((bytes[25] & 0xFF) << 8) + ((bytes[26] & 0xFF) << 16);
                int height = 1 + (bytes[27] & 0xFF) + ((bytes[28] & 0xFF) << 8) + ((bytes[29] & 0xFF) << 16);
                yield dimension(width, height);
            }
            case "VP8L" -> {
                if ((bytes[20] & 0xFF) != 0x2F) {
                    yield Optional.empty();
                }
                int bits = (bytes[21] & 0xFF) | ((bytes[22] & 0xFF) << 8)
                        | ((bytes[23] & 0xFF) << 16) | ((bytes[24] & 0xFF) << 24);
                int width = (bits & 0x3FFF) + 1;
                int height = ((bits >> 14) & 0x3FFF) + 1;
                yield dimension(width, height);
            }
            case "VP8 " -> {
                if ((bytes[23] & 0xFF) != 0x9D || (bytes[24] & 0xFF) != 0x01
                        || (bytes[25] & 0xFF) != 0x2A) {
                    yield Optional.empty();
                }
                int width = (((bytes[26] & 0xFF) | ((bytes[27] & 0xFF) << 8)) & 0x3FFF);
                int height = (((bytes[28] & 0xFF) | ((bytes[29] & 0xFF) << 8)) & 0x3FFF);
                yield dimension(width, height);
            }
            default -> Optional.empty();
        };
    }

    private static boolean isStartOfFrame(int marker) {
        return marker == 0xC0 || marker == 0xC1 || marker == 0xC2 || marker == 0xC3
                || marker == 0xC5 || marker == 0xC6 || marker == 0xC7
                || marker == 0xC9 || marker == 0xCA || marker == 0xCB
                || marker == 0xCD || marker == 0xCE || marker == 0xCF;
    }

    private static Optional<ImageSize> dimension(int width, int height) {
        if (width <= 0 || height <= 0) {
            return Optional.empty();
        }
        return Optional.of(new ImageSize(width, height));
    }

    private static int readIntBigEndian(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }
}
