package uzelok.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PromptCatalogTest {

    @Test
    void loadsBothPromptsFromClasspath() {
        PromptCatalog catalog = new PromptCatalog("v3");

        assertThat(catalog.version("extract")).isEqualTo("v3");
        assertThat(catalog.version("draft")).isEqualTo("v3");
        assertThat(catalog.version("theses")).isEqualTo("v3");
        assertThat(catalog.extract()).contains("дословную транскрипцию", "unclear_spans");
        assertThat(catalog.draft()).contains("taxonomy_mode", "free", "dictionary",
                "topic", "summary", "body_md", "[[Name]]", "file_name",
                "Two_Pointers", "Title_Case");
    }

    @Test
    void keepsSeparateVersionPerStep() {
        PromptCatalog catalog = new PromptCatalog("v2", "v3", "v2");

        assertThat(catalog.version("extract")).isEqualTo("v2");
        assertThat(catalog.version("draft")).isEqualTo("v3");
        assertThat(catalog.version("theses")).isEqualTo("v2");
        assertThat(catalog.extract()).contains("дословную транскрипцию", "unclear_spans");
        assertThat(catalog.draft()).contains("file_name", "Two_Pointers");
    }

    @Test
    void missingVersionFailsAtCreation() {
        assertThatThrownBy(() -> new PromptCatalog("v999"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не найден промпт");
    }
}
