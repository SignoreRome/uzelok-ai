package uzelok.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StructuredSchemasTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void extractSchemaIsStrictJsonWithRequiredFields() {
        JsonNode schema = mapper.readTree(StructuredSchemas.EXTRACT);

        assertThat(schema.path("type").asString()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("required").isArray()).isTrue();
        assertThat(schema.path("required").toString())
                .contains("transcript", "theses", "unclear_spans", "confidence");
    }

    @Test
    void draftSchemaIsStrictJsonWithRequiredFields() {
        JsonNode schema = mapper.readTree(StructuredSchemas.DRAFT);

        assertThat(schema.path("type").asString()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("required").toString())
                .contains("title", "type", "topic", "summary", "body_md", "confidence");
        assertThat(schema.path("properties").path("confidence").path("minimum").asDouble())
                .isEqualTo(0.0);
        assertThat(schema.path("properties").path("confidence").path("maximum").asDouble())
                .isEqualTo(1.0);
    }
}
