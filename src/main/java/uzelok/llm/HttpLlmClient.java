package uzelok.llm;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.ProxySelector;
import java.net.URI;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
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
    private static final String USER_AGENT = "uzelok/0.1";

    public record LlmHttpResponse(
            String content,
            int inputTokens,
            int outputTokens,
            int latencyMs,
            int cacheReadTokens,
            int cacheWriteTokens,
            int reasoningTokens) {
    }

    private final OkHttpClient client;
    private final JsonMapper mapper;

    public HttpLlmClient(JsonMapper mapper, int timeoutSeconds) {
        this(new OkHttpClient(), mapper, timeoutSeconds);
    }

    /**
     * Транспорт — OkHttp, а не {@code java.net.http}: JDK отказывается отправлять Basic-креды
     * в CONNECT-туннеле (jdk.http.auth.tunneling.disabledSchemes), и прокси отвечает 407.
     * Прокси и Proxy-Authorization задаются на клиенте, см. LlmConfig.
     */
    public HttpLlmClient(OkHttpClient client, JsonMapper mapper, int timeoutSeconds) {
        this.client = client;
        this.mapper = mapper;
    }

    public LlmHttpResponse complete(String baseUrl, String apiKey, String model, String systemPrompt,
                                    String userPrompt, List<Path> images, String schemaName,
                                    String jsonSchema) {
        return complete(baseUrl, apiKey, model, systemPrompt, userPrompt, images, schemaName,
                jsonSchema, null);
    }

    /**
     * {@code sessionId} уходит в {@code x-opencode-session}: opencode-go требует стабильный id
     * на диалог (без него 400 MissingSessionID), он же даёт кэш промпта при повторных шагах.
     */
    public LlmHttpResponse complete(String baseUrl, String apiKey, String model, String systemPrompt,
                                    String userPrompt, List<Path> images, String schemaName,
                                    String jsonSchema, String sessionId) {
        String body = requestBody(model, systemPrompt, userPrompt, images, schemaName, jsonSchema);
        Request request;
        try {
            Request.Builder builder = new Request.Builder()
                    .url(endpoint(baseUrl).toString())
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/json");
            if (sessionId != null && !sessionId.isBlank()) {
                builder.header("x-opencode-session", sessionId);
            }
            request = builder
                    .post(RequestBody.create(body, MediaType.get("application/json; charset=utf-8")))
                    .build();
        } catch (IllegalArgumentException exception) {
            throw new LlmCallFailed("некорректный адрес LLM");
        }

        long started = System.nanoTime();
        try (Response response = client.newCall(request).execute()) {
            int latencyMs = (int) ((System.nanoTime() - started) / 1_000_000L);
            ResponseBody responseBody = response.body();
            String text;
            try (InputStream input = responseBody == null
                    ? InputStream.nullInputStream() : responseBody.byteStream()) {
                if (response.isSuccessful()) {
                    byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (bytes.length > MAX_RESPONSE_BYTES) {
                        throw new LlmCallFailed("ответ LLM больше 4 МБ");
                    }
                    text = new String(bytes, StandardCharsets.UTF_8);
                } else {
                    text = new String(input.readNBytes(ERROR_BODY_LIMIT), StandardCharsets.UTF_8);
                }
            }
            if (!response.isSuccessful()) {
                throw new LlmCallFailed("LLM вернул HTTP " + response.code() + ": " + truncate(text));
            }
            return parse(text, latencyMs);
        } catch (IOException exception) {
            throw new LlmCallFailed("не удалось вызвать LLM: сеть недоступна", exception);
        }
    }

    private LlmHttpResponse parse(String responseBody, int latencyMs) {
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
        JsonNode promptDetails = usage.path("prompt_tokens_details");
        JsonNode completionDetails = usage.path("completion_tokens_details");
        return new LlmHttpResponse(
                contentNode.asString(),
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                latencyMs,
                promptDetails.path("cached_tokens").asInt(0),
                promptDetails.path("cache_write_tokens").asInt(0),
                completionDetails.path("reasoning_tokens").asInt(0));
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
