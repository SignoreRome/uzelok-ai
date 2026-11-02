package uzelok.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class LlmRequestKey {

    private LlmRequestKey() {
    }

    public static String of(String step, String inputHash, String model, String promptVersion,
                            int draftVersion) {
        String source = step + "|" + inputHash + "|" + model + "|" + promptVersion + "|"
                + draftVersion;
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
