package uzelok.telegram;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import uzelok.config.UzelokProperties;
import uzelok.domain.model.SourceItem;
import uzelok.domain.model.SourceKind;
import uzelok.domain.port.AttachmentRepository;
import uzelok.domain.port.TelegramGateway;

public final class AttachmentReceiver {

    public record StoredAttachment(Path path, String mime, long byteSize, String sha256) {
    }

    private final UzelokProperties.Telegram props;
    private final UzelokProperties.Storage storage;
    private final AttachmentRepository repository;
    private final TelegramGateway gateway;
    private final Messages messages;

    public AttachmentReceiver(UzelokProperties.Telegram props, UzelokProperties.Storage storage,
                              AttachmentRepository repository, TelegramGateway gateway, Messages messages) {
        this.props = props;
        this.storage = storage;
        this.repository = repository;
        this.gateway = gateway;
        this.messages = messages;
    }

    public StoredAttachment receive(SourceItem item, String fileId, String declaredMime, long declaredSize,
                                    String fileName) {
        boolean document = item.kind() == SourceKind.DOCUMENT;
        long limit = document ? props.maxDocumentBytes() : props.maxPhotoBytes();
        if (declaredSize > limit) {
            throw new AttachmentRejected(sizeMessage(document, declaredSize, limit));
        }

        byte[] bytes = gateway.download(fileId, limit);
        String mime;
        if (document) {
            if (!isTextUtf8(bytes)) {
                throw new AttachmentRejected(messages.text("attachment.bad_text"));
            }
            mime = textMime(fileName);
        } else {
            mime = detectImageMime(bytes)
                    .orElseThrow(() -> new AttachmentRejected(messages.text("photo.bad_format")));
        }

        if (bytes.length > limit) {
            throw new AttachmentRejected(sizeMessage(document, bytes.length, limit));
        }

        if (!document) {
            ImageSize size = ImageHeader.read(bytes, mime)
                    .orElseThrow(() -> new AttachmentRejected("не удалось определить размер изображения"));
            long pixels = (long) size.width() * size.height();
            if (pixels > props.maxImagePixels()) {
                throw new AttachmentRejected(messages.text("photo.bad_pixels",
                        String.valueOf(pixels), String.valueOf(props.maxImagePixels())));
            }
        }

        String sha256 = sha256(bytes);
        Path path = targetPath(item, sha256, extension(mime));
        writeIfAbsent(path, bytes);
        StoredAttachment stored = new StoredAttachment(path, mime, bytes.length, sha256);
        repository.append(item.id(), item.tenantId(), fileId, path.toString(), mime, bytes.length, sha256);
        return stored;
    }

    private Path targetPath(SourceItem item, String sha256, String extension) {
        Instant createdAt = item.createdAt() == null ? Instant.now() : item.createdAt();
        LocalDate date = LocalDate.ofInstant(createdAt, ZoneOffset.UTC);
        return Path.of(
                storage.artifactsDir(),
                item.tenantId().toString(),
                Integer.toString(date.getYear()),
                String.format(Locale.ROOT, "%02d", date.getMonthValue()),
                sha256 + "." + extension);
    }

    private static void writeIfAbsent(Path path, byte[] bytes) {
        try {
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) {
                return;
            }
            Path tmp = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
            Files.write(tmp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException exception) {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("не удалось сохранить вложение", exception);
        }
    }

    private static Optional<String> detectImageMime(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
            return Optional.of("image/png");
        }
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    private static boolean isTextUtf8(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private static String textMime(String fileName) {
        if (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".md")) {
            return "text/markdown";
        }
        return "text/plain";
    }

    private static String extension(String mime) {
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "text/markdown" -> "md";
            case "text/plain" -> "txt";
            default -> "bin";
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 недоступен", exception);
        }
    }

    private String sizeMessage(boolean document, long actual, long limit) {
        String key = document ? "document.too_big" : "photo.too_big";
        return messages.text(key, String.valueOf(actual), String.valueOf(limit));
    }
}
