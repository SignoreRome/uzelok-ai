package uzelok.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class PromptCatalog {

    private final String extractVersion;
    private final String draftVersion;
    private final String thesesVersion;
    private final String extract;
    private final String draft;
    private final String theses;

    public PromptCatalog(String version) {
        this(version, version, version);
    }

    public PromptCatalog(String extractVersion, String draftVersion, String thesesVersion) {
        this.extractVersion = normalize(extractVersion);
        this.draftVersion = normalize(draftVersion);
        this.thesesVersion = normalize(thesesVersion);
        this.extract = load("extract", this.extractVersion);
        this.draft = load("draft", this.draftVersion);
        this.theses = load("theses", this.thesesVersion);
    }

    public String version(String step) {
        return switch (step == null ? "" : step.toLowerCase(Locale.ROOT)) {
            case "extract" -> extractVersion;
            case "draft" -> draftVersion;
            case "theses" -> thesesVersion;
            default -> throw new IllegalArgumentException("неизвестный шаг промпта: " + step);
        };
    }

    public String extract() {
        return extract;
    }

    public String draft() {
        return draft;
    }

    /** Промпт для текстового материала: транскрипция не нужна, нужны только тезисы. */
    public String theses() {
        return theses;
    }

    private static String normalize(String version) {
        return version == null || version.isBlank() ? "v1" : version;
    }

    private static String load(String name, String version) {
        String resource = "prompts/" + name + "-" + version + ".md";
        try (InputStream input = PromptCatalog.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("не найден промпт " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("не удалось прочитать промпт " + resource, exception);
        }
    }
}
