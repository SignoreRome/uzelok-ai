package uzelok.config;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import uzelok.domain.port.JsonCodec;

public final class JacksonJsonCodec implements JsonCodec {

    private final JsonMapper mapper;

    public JacksonJsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    @Override
    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("не удалось разобрать JSON артефакта", exception);
        }
    }
}
