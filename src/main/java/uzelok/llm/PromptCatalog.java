package uzelok.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class PromptCatalog {

    private final String version;
    private final String extract;
    private final String draft;

    public PromptCatalog(String version) {
        this.version = version == null || version.isBlank() ? "v1" : version;
        this.extract = load("extract", this.version);
        this.draft = load("draft", this.version);
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
