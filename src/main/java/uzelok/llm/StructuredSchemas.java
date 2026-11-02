package uzelok.llm;

public final class StructuredSchemas {

    public static final String EXTRACT = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["transcript", "theses", "unclear_spans", "confidence"],
              "properties": {
                "transcript": {"type": "string"},
                "theses": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["text", "confidence"],
                    "properties": {
                      "text": {"type": "string"},
                      "confidence": {"type": "number", "minimum": 0, "maximum": 1}
                    }
                  }
                },
                "unclear_spans": {
                  "type": "array",
                  "items": {"type": "string"}
                },
                "confidence": {"type": "number", "minimum": 0, "maximum": 1}
              }
            }
            """;

    public static final String DRAFT = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["title", "type", "topic", "summary", "body_md", "confidence"],
              "properties": {
                "title": {"type": "string"},
                "type": {"type": "string", "enum": ["concept", "problem", "source_note"]},
                "topic": {"type": "string"},
                "summary": {"type": "string", "maxLength": 150},
                "body_md": {"type": "string"},
                "confidence": {"type": "number", "minimum": 0, "maximum": 1}
              }
            }
            """;

    public StructuredSchemas() {
    }

    public static String extract() {
        return EXTRACT;
    }

    public static String draft() {
        return DRAFT;
    }
}
