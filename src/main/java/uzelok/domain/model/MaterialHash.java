package uzelok.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Содержимое материала: sha256 вложений в порядке position плюс нормализованный текст.
 */
public final class MaterialHash {

    private MaterialHash() {
    }

    public static String of(List<SourceAttachment> attachments, String text) {
        List<String> hashes = attachments == null ? List.of() : attachments.stream()
                .sorted(Comparator.comparingInt(SourceAttachment::position))
                .map(SourceAttachment::sha256)
                .toList();
        return ofHashes(hashes, text);
    }

    public static String ofHashes(List<String> attachmentHashes, String text) {
        StringBuilder material = new StringBuilder();
        if (attachmentHashes != null) {
            for (String hash : attachmentHashes) {
                material.append(hash == null ? "" : hash);
            }
        }
        material.append(normalizeText(text));
        return sha256(material.toString());
    }

    public static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        return unified.replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 недоступен", exception);
        }
    }
}
