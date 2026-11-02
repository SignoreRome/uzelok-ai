package uzelok.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;
import uzelok.domain.port.JsonCodec;

@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    public JsonMapper jsonMapper() {
        return JsonMapper.builder().build();
    }

    @Bean
    public JsonCodec jsonCodec(JsonMapper jsonMapper) {
        return new JacksonJsonCodec(jsonMapper);
    }
}
