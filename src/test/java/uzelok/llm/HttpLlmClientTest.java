package uzelok.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import uzelok.domain.exception.LlmCallFailed;
import uzelok.domain.exception.LlmContractViolation;
import uzelok.support.TestImages;

class HttpLlmClientTest {

    private static final String API_KEY = "super-secret-test-key";
    private static final String SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false}";

    @TempDir
    Path tempDir;

    private HttpServer server;
    private HttpLlmClient client;
    private String baseUrl;
    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile String lastRequest;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequest = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        client = new HttpLlmClient(JsonMapper.builder().build(), 5);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void parsesContentAndTokenUsage() {
        respond(200, success("{\"transcript\":\"ok\"}", 12, 7));

        HttpLlmClient.LlmHttpResponse response = client.complete(baseUrl, API_KEY, "model",
                "system", "material", List.of(), "extract", SCHEMA);

        assertThat(response.content()).isEqualTo("{\"transcript\":\"ok\"}");
        assertThat(response.inputTokens()).isEqualTo(12);
        assertThat(response.outputTokens()).isEqualTo(7);
        assertThat(response.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void serverErrorBecomesLlmCallFailedWithoutKey() {
        respond(500, "x".repeat(700));

        assertThatThrownBy(() -> client.complete(baseUrl, API_KEY, "model",
                "system", "material", List.of(), "extract", SCHEMA))
                .isInstanceOfSatisfying(LlmCallFailed.class, failure -> {
                    assertThat(failure.getMessage()).contains("500");
                    assertThat(failure.getMessage()).doesNotContain(API_KEY);
                    assertThat(failure.getMessage().length()).isLessThan(600);
                });
    }

    @Test
    void tooManyRequestsBecomesLlmCallFailed() {
        respond(429, "rate limited");

        assertThatThrownBy(() -> client.complete(baseUrl, API_KEY, "model",
                "system", "material", List.of(), "extract", SCHEMA))
                .isInstanceOf(LlmCallFailed.class)
                .hasMessageContaining("429");
    }

    @Test
    void nonJsonResponseBecomesContractViolation() {
        respond(200, "not-a-json");

        assertThatThrownBy(() -> client.complete(baseUrl, API_KEY, "model",
                "system", "material", List.of(), "extract", SCHEMA))
                .isInstanceOf(LlmContractViolation.class);
    }

    @Test
    void oversizedResponseBecomesLlmCallFailed() {
        respond(200, "x".repeat(4 * 1024 * 1024 + 1));

        assertThatThrownBy(() -> client.complete(baseUrl, API_KEY, "model",
                "system", "material", List.of(), "extract", SCHEMA))
                .isInstanceOf(LlmCallFailed.class)
                .hasMessageContaining("4 МБ");
    }

    @Test
    void imageIsSentAsDataUrl() throws IOException {
        respond(200, success("{}", 1, 1));
        Path image = tempDir.resolve("photo.jpg");
        Files.write(image, TestImages.jpeg(20, 10));

        client.complete(baseUrl, API_KEY, "model", "system", "material", List.of(image),
                "extract", SCHEMA);

        assertThat(lastRequest).contains("data:image/jpeg;base64,");
        assertThat(lastRequest).contains("\"max_tokens\":8192");
        assertThat(lastRequest).contains("\"strict\":true");
        assertThat(lastRequest).contains("\"name\":\"extract\"");
    }

    private void respond(int status, String body) {
        responseStatus = status;
        responseBody = body;
    }

    private static String success(String content, int promptTokens, int completionTokens) {
        return """
                {
                  "choices": [{"message": {"content": %s}}],
                  "usage": {"prompt_tokens": %d, "completion_tokens": %d}
                }
                """.formatted(JsonMapper.builder().build().writeValueAsString(content),
                promptTokens, completionTokens);
    }
}
