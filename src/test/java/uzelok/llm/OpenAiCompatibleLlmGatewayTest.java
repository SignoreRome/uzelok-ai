package uzelok.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uzelok.config.UzelokProperties;
import uzelok.domain.exception.LlmContractViolation;
import uzelok.domain.exception.QuotaExceeded;
import uzelok.domain.model.Extraction;
import uzelok.domain.model.LlmCallRecord;
import uzelok.domain.model.LlmContext;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.Taxonomy;
import uzelok.support.InMemoryLlmCallRepository;

class OpenAiCompatibleLlmGatewayTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final InMemoryLlmCallRepository calls = new InMemoryLlmCallRepository();
    private final AtomicInteger serverCalls = new AtomicInteger();
    private final Instant now = Instant.parse("2026-05-01T12:00:00Z");

    private HttpServer server;
    private UzelokProperties.Llm properties;
    private OpenAiCompatibleLlmGateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            int call = serverCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            int status = call == 1 && fallbackMode ? 500 : 200;
            String body = status == 500
                    ? "temporary failure"
                    : success(extractionJson("распознанный текст"), 12, 7);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        properties = new UzelokProperties.Llm(
                Map.of("provider", new UzelokProperties.Provider(baseUrl, "key")),
                Map.of(
                        "extract", new UzelokProperties.Step("provider", "main", "fallback"),
                        "draft", new UzelokProperties.Step("provider", "draft", null)),
                Map.of(
                        "provider/main", new UzelokProperties.Pricing(
                                new BigDecimal("1.00"), new BigDecimal("2.00")),
                        "provider/fallback", new UzelokProperties.Pricing(
                                new BigDecimal("3.00"), new BigDecimal("4.00")),
                        "provider/draft", new UzelokProperties.Pricing(
                                new BigDecimal("0.10"), new BigDecimal("0.20"))),
                "v1",
                new BigDecimal("2.00"),
                10);
        gateway = new OpenAiCompatibleLlmGateway(
                new HttpLlmClient(mapper, 5),
                new LlmRouter(properties),
                new PromptCatalog("v1"),
                new StructuredSchemas(),
                calls,
                mapper,
                properties,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private boolean fallbackMode;

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void successfulCallIsRecordedWithCost() {
        Extraction extraction = gateway.extract(context("hash-1", 0), List.of(), "материал");

        assertThat(extraction.transcript()).isEqualTo("распознанный текст");
        assertThat(calls.successes()).hasSize(1);
        LlmCallRecord call = calls.successes().getFirst();
        assertThat(call.provider()).isEqualTo("provider");
        assertThat(call.model()).isEqualTo("main");
        assertThat(call.step()).isEqualTo("EXTRACT");
        assertThat(call.inputTokens()).isEqualTo(12);
        assertThat(call.outputTokens()).isEqualTo(7);
        assertThat(call.costUsd()).isEqualByComparingTo(new BigDecimal("0.000026"));
        assertThat(call.responseText()).contains("распознанный текст");
    }

    @Test
    void repeatedCallWithSameKeyDoesNotReachNetwork() {
        LlmContext context = context("same-hash", 0);
        gateway.extract(context, List.of(), "материал");
        gateway.extract(context, List.of(), "материал");

        assertThat(serverCalls).hasValue(1);
        assertThat(calls.size()).isEqualTo(1);
    }

    @Test
    void fallbackModelIsUsedAfterPrimaryFailure() {
        fallbackMode = true;

        Extraction extraction = gateway.extract(context("fallback-hash", 0), List.of(), "материал");

        assertThat(extraction.transcript()).isEqualTo("распознанный текст");
        assertThat(serverCalls).hasValue(2);
        assertThat(calls.successes()).hasSize(1);
        assertThat(calls.successes().getFirst().model()).isEqualTo("fallback");
    }

    @Test
    void exhaustedBudgetStopsBeforeNetwork() {
        calls.recordAt(new LlmCallRecord(tenantId, jobId, "EXTRACT", "provider", "main", "v1",
                "old-key", 1, 1, new BigDecimal("2.00"), 1, true, "{}"), now.minusSeconds(60));

        assertThatThrownBy(() -> gateway.extract(context("budget-hash", 0), List.of(), "материал"))
                .isInstanceOf(QuotaExceeded.class);
        assertThat(serverCalls).hasValue(0);
    }

    @Test
    void invalidModelResponseBecomesContractViolationAndFailedCall() throws IOException {
        server.stop(0);
        server = startServer(success("{\"transcript\":\"\",\"theses\":[],"
                + "\"unclear_spans\":[],\"confidence\":0.5}", 3, 4));
        gateway = gatewayWith(properties);

        assertThatThrownBy(() -> gateway.extract(context("invalid-hash", 0), List.of(), "материал"))
                .isInstanceOf(LlmContractViolation.class);

        assertThat(calls.all()).hasSize(1);
        assertThat(calls.all().getFirst().success()).isFalse();
        assertThat(calls.all().getFirst().costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void cachedFallbackResponseIsReusedWithoutNetwork() {
        String hash = "cached-fallback-hash";
        String requestKey = LlmRequestKey.of("EXTRACT", hash, "fallback", "v1", 0);
        calls.record(new LlmCallRecord(tenantId, jobId, "EXTRACT", "provider", "fallback", "v1",
                requestKey, 12, 7, new BigDecimal("0.000100"), 1, true,
                extractionJson("из кэша fallback")));

        Extraction extraction = gateway.extract(context(hash, 0), List.of(), "материал");

        assertThat(extraction.transcript()).isEqualTo("из кэша fallback");
        assertThat(serverCalls).hasValue(0);
    }

    @Test
    void cachedResponseIsReturnedBeforeBudgetCheck() {
        String hash = "cached-budget-hash";
        String requestKey = LlmRequestKey.of("EXTRACT", hash, "main", "v1", 0);
        calls.recordAt(new LlmCallRecord(tenantId, jobId, "EXTRACT", "provider", "main", "v1",
                requestKey, 12, 7, new BigDecimal("2.00"), 1, true,
                extractionJson("уже оплаченный ответ")), now.minusSeconds(60));

        Extraction extraction = gateway.extract(context(hash, 0), List.of(), "материал");

        assertThat(extraction.transcript()).isEqualTo("уже оплаченный ответ");
        assertThat(serverCalls).hasValue(0);
    }

    @Test
    void emptyThesesBecomeContractViolation() throws IOException {
        server.stop(0);
        server = startServer(success("{\"transcript\":\"текст\",\"theses\":[],"
                + "\"unclear_spans\":[],\"confidence\":0.5}", 3, 4));
        gateway = gatewayWith(properties);

        assertThatThrownBy(() -> gateway.extract(context("empty-theses", 0), List.of(), "материал"))
                .isInstanceOf(LlmContractViolation.class)
                .hasMessageContaining("theses");
    }

    @Test
    void draftTruncatesSummaryToRendererLimit() throws IOException {
        server.stop(0);
        String longSummary = "слово ".repeat(40).trim();
        String draftJson = """
                {"title":"Заметка","type":"concept","topic":"two_pointers",
                 "summary":"%s","body_md":"## Тело","confidence":0.8}
                """.formatted(longSummary);
        server = startServer(success(draftJson, 5, 6));
        gateway = gatewayWith(properties);

        NoteDraft draft = gateway.draft(
                new LlmContext(tenantId, jobId, "DRAFT", "long-summary", 1),
                extraction(), taxonomy(), Set.of());

        assertThat(draft.summary()).hasSizeLessThanOrEqualTo(150).endsWith("…");
    }

    @Test
    void draftWithEmptyTopicBecomesContractViolation() throws IOException {
        server.stop(0);
        String draftJson = """
                {"title":"Заметка","type":"concept","topic":"",
                 "summary":"кратко","body_md":"## Тело","confidence":0.8}
                """;
        server = startServer(success(draftJson, 5, 6));
        gateway = gatewayWith(properties);

        assertThatThrownBy(() -> gateway.draft(
                new LlmContext(tenantId, jobId, "DRAFT", "empty-topic", 1),
                extraction(), taxonomy(), Set.of()))
                .isInstanceOf(LlmContractViolation.class)
                .hasMessageContaining("topic");
    }

    @Test
    void draftParsesTypedDraft() throws IOException {
        server.stop(0);
        String draftJson = """
                {"title":"Заметка","type":"concept","topic":"two_pointers",
                 "summary":"кратко","body_md":"## Тело","confidence":0.8}
                """;
        server = startServer(success(draftJson, 5, 6));
        gateway = gatewayWith(properties);

        NoteDraft draft = gateway.draft(
                new LlmContext(tenantId, jobId, "DRAFT", "draft-hash", 1),
                extraction(), taxonomy(), Set.of());

        assertThat(draft.title()).isEqualTo("Заметка");
        assertThat(draft.topic()).isEqualTo("two_pointers");
        assertThat(calls.successes()).hasSize(1);
        assertThat(calls.successes().getFirst().step()).isEqualTo("DRAFT");
    }

    private OpenAiCompatibleLlmGateway gatewayWith(UzelokProperties.Llm configured) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new OpenAiCompatibleLlmGateway(
                new HttpLlmClient(mapper, 5),
                new LlmRouter(configured),
                new PromptCatalog("v1"),
                new StructuredSchemas(),
                calls,
                mapper,
                configured,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private HttpServer startServer(String body) throws IOException {
        HttpServer replacement = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        replacement.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        replacement.start();
        properties = new UzelokProperties.Llm(
                Map.of("provider", new UzelokProperties.Provider(
                        "http://127.0.0.1:" + replacement.getAddress().getPort(), "key")),
                properties.steps(),
                properties.pricing(),
                properties.promptVersion(),
                properties.dailyBudgetUsd(),
                properties.timeoutSeconds());
        server = replacement;
        return replacement;
    }

    private LlmContext context(String inputHash, int draftVersion) {
        return new LlmContext(tenantId, jobId, "EXTRACT", inputHash, draftVersion);
    }

    private static Extraction extraction() {
        return new Extraction("транскрипция", List.of(), List.of(), 0.9);
    }

    private static Taxonomy taxonomy() {
        return new Taxonomy(2, List.of(new Taxonomy.Domain("Algorithms", List.of(),
                List.of(new Taxonomy.Topic("Graphs", List.of())))));
    }

    private static String extractionJson(String transcript) {
        return """
                {"transcript":"%s","theses":[{"text":"тезис","confidence":0.9}],
                 "unclear_spans":[],"confidence":0.9}
                """.formatted(transcript);
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
