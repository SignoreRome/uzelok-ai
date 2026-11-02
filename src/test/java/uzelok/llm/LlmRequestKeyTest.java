package uzelok.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmRequestKeyTest {

    @Test
    void sameInputsProduceSameKey() {
        String first = LlmRequestKey.of("EXTRACT", "input-hash", "model-a", "v1", 1);
        String second = LlmRequestKey.of("EXTRACT", "input-hash", "model-a", "v1", 1);

        assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void keyDependsOnModel() {
        assertThat(LlmRequestKey.of("EXTRACT", "h", "model-a", "v1", 0))
                .isNotEqualTo(LlmRequestKey.of("EXTRACT", "h", "model-b", "v1", 0));
    }

    @Test
    void keyDependsOnDraftVersion() {
        assertThat(LlmRequestKey.of("DRAFT", "h", "model", "v1", 1))
                .isNotEqualTo(LlmRequestKey.of("DRAFT", "h", "model", "v1", 2));
    }

    @Test
    void keyDependsOnInputHash() {
        assertThat(LlmRequestKey.of("EXTRACT", "hash-1", "model", "v1", 0))
                .isNotEqualTo(LlmRequestKey.of("EXTRACT", "hash-2", "model", "v1", 0));
    }
}
