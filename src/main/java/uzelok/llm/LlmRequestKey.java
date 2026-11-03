package uzelok.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class LlmRequestKey {

    private LlmRequestKey() {
    }

    /**
     * Ключ идемпотентности вызова. Тенант входит в ключ: кэш общий на таблицу, и без него ответ
     * одного пользователя отдавался бы другому при совпадении материала. Вариант промпта
     * ({@code free}/{@code dictionary}) тоже входит: одинаковый материал в разных режимах
     * таксономии даёт разные черновики.
     */
    public static String of(String step, UUID tenantId, String inputHash, String model,
                            String promptVersion, int draftVersion, String variant) {
        String source = step + "|" + tenantId + "|" + inputHash + "|" + model + "|"
                + promptVersion + "|" + draftVersion + "|" + (variant == null ? "" : variant);
        return sha256(source);
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
