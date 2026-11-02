package uzelok.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class PromptCatalog {

    private final String version;
    private final String extract;
    private final String draft;
    private final String theses;

    public PromptCatalog(String version) {
        this.version = version == null || version.isBlank() ? "v1" : version;
        this.extract = load("extract", this.version);
        this.draft = load("draft", this.version);
        this.theses = load("theses", this.version);
    }

    public String version() {
        return version;
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
