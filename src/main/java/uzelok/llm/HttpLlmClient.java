package uzelok.llm;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uzelok.domain.exception.LlmCallFailed;
import uzelok.domain.exception.LlmContractViolation;

public final class HttpLlmClient {

    private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int ERROR_BODY_LIMIT = 500;
    private static final int MAX_TOKENS = 8192;

    public record LlmHttpResponse(String content, int inputTokens, int outputTokens, int latencyMs) {
    }

    private final HttpClient client;
    private final JsonMapper mapper;
    private final Duration requestTimeout;

    public HttpLlmClient(JsonMapper mapper, int timeoutSeconds) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), mapper,
                timeoutSeconds);
    }

    public HttpLlmClient(HttpClient client, JsonMapper mapper, int timeoutSeconds) {
        this.client = client;
        this.mapper = mapper;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
    }

    public LlmHttpResponse complete(String baseUrl, String apiKey, String model, String systemPrompt,
                                    String userPrompt, List<Path> images, String schemaName,
                                    String jsonSchema) {
        String body = requestBody(model, systemPrompt, userPrompt, images, schemaName, jsonSchema);
        HttpRequest request = HttpRequest.newBuilder(endpoint(baseUrl))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long started = System.nanoTime();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException exception) {
            throw new LlmCallFailed("не удалось вызвать LLM: сеть недоступна", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmCallFailed("вызов LLM прерван", exception);
        }
        int latencyMs = (int) ((System.nanoTime() - started) / 1_000_000L);

        String responseBody;
        try (InputStream input = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                responseBody = new String(input.readNBytes(ERROR_BODY_LIMIT),
                        StandardCharsets.UTF_8);
            } else {
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new LlmCallFailed("ответ LLM больше 4 МБ");
                }
                responseBody = new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new LlmCallFailed("не удалось прочитать ответ LLM", exception);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new LlmCallFailed("LLM вернул HTTP " + response.statusCode() + ": "
                    + truncate(responseBody));
        }

        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (JacksonException exception) {
            throw new LlmContractViolation("ответ LLM не является JSON", exception);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LlmContractViolation("в ответе LLM нет choices");
        }
        JsonNode contentNode = choices.get(0).path("message").path("content");
        if (!contentNode.isTextual() || contentNode.asString().isBlank()) {
            throw new LlmContractViolation("в ответе LLM нет содержимого");
        }
        JsonNode usage = root.path("usage");
        return new LlmHttpResponse(
                contentNode.asString(),
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                latencyMs);
    }

    private String requestBody(String model, String systemPrompt, String userPrompt,
                               List<Path> images, String schemaName, String jsonSchema) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", MAX_TOKENS);

        ArrayNode messages = root.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrompt);

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", userPrompt == null || userPrompt.isBlank()
                ? "Распознай текст на приложенных изображениях." : userPrompt);
        if (images != null) {
            for (Path image : images) {
                ObjectNode item = content.addObject();
                item.put("type", "image_url");
                item.putObject("image_url").put("url", dataUrl(image));
            }
        }

        ObjectNode format = root.putObject("response_format");
        format.put("type", "json_schema");
        ObjectNode jsonSchemaNode = format.putObject("json_schema");
        jsonSchemaNode.put("name", schemaName);
        jsonSchemaNode.put("strict", true);
        try {
            jsonSchemaNode.set("schema", mapper.readTree(jsonSchema));
        } catch (JacksonException exception) {
            throw new IllegalStateException("некорректная JSON-схема " + schemaName, exception);
        }
        return mapper.writeValueAsString(root);
    }

    private static String dataUrl(Path image) {
        byte[] bytes;
        try {
            if (Files.size(image) > MAX_IMAGE_BYTES) {
                throw new LlmCallFailed("изображение больше 15 МБ");
            }
            bytes = Files.readAllBytes(image);
        } catch (IOException exception) {
            throw new LlmCallFailed("не удалось прочитать изображение", exception);
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new LlmCallFailed("изображение больше 15 МБ");
        }
        String mime = detectMime(bytes);
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static String detectMime(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G' && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A
                && bytes[7] == 0x0A) {
            return "image/png";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return "image/webp";
        }
        throw new LlmCallFailed("неподдерживаемый формат изображения");
    }

    private static URI endpoint(String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(base + "/chat/completions");
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= ERROR_BODY_LIMIT ? value : value.substring(0, ERROR_BODY_LIMIT);
    }
}
