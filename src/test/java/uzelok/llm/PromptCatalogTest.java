package uzelok.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PromptCatalogTest {

    @Test
    void loadsBothPromptsFromClasspath() {
        PromptCatalog catalog = new PromptCatalog("v2");

        assertThat(catalog.version()).isEqualTo("v2");
        assertThat(catalog.extract()).contains("дословную транскрипцию", "unclear_spans");
        assertThat(catalog.draft()).contains("taxonomy_mode", "free", "dictionary",
                "topic", "summary", "body_md", "[[Name]]");
    }

    @Test
    void missingVersionFailsAtCreation() {
        assertThatThrownBy(() -> new PromptCatalog("v999"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не найден промпт");
    }
}
