package uzelok.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LlmRequestKeyTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void sameInputsProduceSameKey() {
        String first = LlmRequestKey.of("EXTRACT", TENANT, "input-hash", "model-a", "v1", 1, "");
        String second = LlmRequestKey.of("EXTRACT", TENANT, "input-hash", "model-a", "v1", 1, "");

        assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void keyDependsOnModel() {
        assertThat(LlmRequestKey.of("EXTRACT", TENANT, "h", "model-a", "v1", 0, ""))
                .isNotEqualTo(LlmRequestKey.of("EXTRACT", TENANT, "h", "model-b", "v1", 0, ""));
    }

    @Test
    void keyDependsOnDraftVersion() {
        assertThat(LlmRequestKey.of("DRAFT", TENANT, "h", "model", "v1", 1, "free"))
                .isNotEqualTo(LlmRequestKey.of("DRAFT", TENANT, "h", "model", "v1", 2, "free"));
    }

    @Test
    void keyDependsOnInputHash() {
        assertThat(LlmRequestKey.of("EXTRACT", TENANT, "hash-1", "model", "v1", 0, ""))
                .isNotEqualTo(LlmRequestKey.of("EXTRACT", TENANT, "hash-2", "model", "v1", 0, ""));
    }

    @Test
    void keyDependsOnTenantSoCacheCannotLeakBetweenUsers() {
        assertThat(LlmRequestKey.of("DRAFT", TENANT, "h", "model", "v1", 1, "free"))
                .isNotEqualTo(LlmRequestKey.of("DRAFT", OTHER_TENANT, "h", "model", "v1", 1, "free"));
    }

    @Test
    void keyDependsOnTaxonomyMode() {
        assertThat(LlmRequestKey.of("DRAFT", TENANT, "h", "model", "v2", 1, "free"))
                .isNotEqualTo(LlmRequestKey.of("DRAFT", TENANT, "h", "model", "v2", 1, "dictionary"));
    }
}
