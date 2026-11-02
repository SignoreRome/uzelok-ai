package uzelok.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uzelok.config.UzelokProperties;
import uzelok.domain.exception.LlmCallFailed;
import uzelok.domain.exception.LlmContractViolation;
import uzelok.domain.exception.QuotaExceeded;
import uzelok.domain.model.Extraction;
import uzelok.domain.model.LlmCallRecord;
import uzelok.domain.model.LlmContext;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.NoteType;
import uzelok.domain.model.Taxonomy;
import uzelok.domain.model.Thesis;
import uzelok.domain.port.LlmCallRepository;
import uzelok.domain.port.LlmGateway;

public final class OpenAiCompatibleLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmGateway.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);
    private static final int SUMMARY_LIMIT = 150;

    private final HttpLlmClient client;
    private final LlmRouter router;
    private final PromptCatalog prompts;
    private final StructuredSchemas schemas;
    private final LlmCallRepository calls;
    private final JsonMapper mapper;
    private final UzelokProperties.Llm properties;
    private final Clock clock;
    private final Set<String> missingPricing = ConcurrentHashMap.newKeySet();

    public OpenAiCompatibleLlmGateway(
            HttpLlmClient client,
            LlmRouter router,
            PromptCatalog prompts,
            StructuredSchemas schemas,
            LlmCallRepository calls,
            JsonMapper mapper,
            UzelokProperties.Llm properties,
            Clock clock) {
        this.client = client;
        this.router = router;
        this.prompts = prompts;
        this.schemas = schemas;
        this.calls = calls;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Extraction extract(LlmContext context, List<Path> images, String text) {
        String step = step(context);
        LlmRouter.Route route = router.route("extract");
        Optional<String> cached = findCached(route, step, context);
        if (cached.isPresent()) {
            return parseExtraction(cached.get());
        }
        checkBudget(context.tenantId());
        if (images.isEmpty() && text != null && !text.isBlank()) {
            // Текстовый материал уже расшифрован: транскрибировать нечего, нужны только тезисы.
            // Промпт распознавания на таком входе даёт пустой transcript и валит контракт.
            return call(route, context, step, prompts.theses(), text, List.of(), "theses",
                    StructuredSchemas.THESES,
                    content -> parseTheses(text, content));
        }
        return call(route, context, step, prompts.extract(),
                text == null ? "" : text, images, "extract", StructuredSchemas.EXTRACT,
                this::parseExtraction);
    }

    @Override
    public NoteDraft draft(LlmContext context, Extraction extraction, Taxonomy taxonomy,
                           Set<String> existingNames) {
        String step = step(context);
        LlmRouter.Route route = router.route("draft");
        Optional<String> cached = findCached(route, step, context);
        if (cached.isPresent()) {
            return parseDraft(cached.get());
        }
        checkBudget(context.tenantId());
        String userPrompt = draftPrompt(extraction, taxonomy, existingNames);
        return call(route, context, step, prompts.draft(), userPrompt, List.of(),
                "draft", StructuredSchemas.DRAFT, this::parseDraft);
    }

    private Optional<String> findCached(LlmRouter.Route route, String step, LlmContext context) {
        for (String model : route.models()) {
            Optional<String> cached = calls.findSuccessfulResponse(requestKey(step, context, model));
            if (cached.isPresent()) {
                return cached;
            }
        }
        return Optional.empty();
    }

    private <T> T call(LlmRouter.Route route, LlmContext context, String step,
                       String systemPrompt, String userPrompt, List<Path> images,
                       String schemaName, String jsonSchema, Function<String, T> parser) {
        LlmCallFailed lastFailure = null;
        String lastModel = route.models().getFirst();
        String lastRequestKey = requestKey(step, context, lastModel);
        for (String model : route.models()) {
            lastModel = model;
            String requestKey = requestKey(step, context, model);
            lastRequestKey = requestKey;
            HttpLlmClient.LlmHttpResponse response;
            try {
                response = client.complete(route.baseUrl(), route.apiKey(), model, systemPrompt,
                        userPrompt, images, schemaName, jsonSchema,
                        "uzelok-" + context.jobId());
            } catch (LlmCallFailed failure) {
                lastFailure = failure;
                continue;
            }

            T parsed;
            try {
                parsed = parser.apply(response.content());
            } catch (LlmContractViolation violation) {
                recordFailure(context, step, route, model, requestKey, response.inputTokens(),
                        response.outputTokens(), response.latencyMs(), response.content());
                throw violation;
            }
            BigDecimal cost = cost(route.provider(), model, response.inputTokens(),
                    response.outputTokens());
            calls.record(new LlmCallRecord(
                    context.tenantId(),
                    context.jobId(),
                    step,
                    route.provider(),
                    model,
                    prompts.version(),
                    requestKey,
                    response.inputTokens(),
                    response.outputTokens(),
                    cost,
                    response.latencyMs(),
                    true,
                    response.content()));
            log.info("llm шаг={} job_id={} model={} tokens_in={} tokens_out={} cost_usd={} ms={}",
                    step, context.jobId(), model, response.inputTokens(), response.outputTokens(),
                    cost, response.latencyMs());
            return parsed;
        }

        recordFailure(context, step, route, lastModel, lastRequestKey, 0, 0, 0, null);
        throw lastFailure == null ? new LlmCallFailed("LLM не вернул ответ") : lastFailure;
    }

    /**
     * {@code responseText} сохраняем и при провале контракта: без сырого ответа модель невозможно
     * обвинить или оправдать, а в лог его писать нельзя (там материал пользователя).
     */
    private void recordFailure(LlmContext context, String step, LlmRouter.Route route, String model,
                               String requestKey, int inputTokens, int outputTokens, int latencyMs,
                               String responseText) {
        calls.record(new LlmCallRecord(
                context.tenantId(),
                context.jobId(),
                step,
                route.provider(),
                model,
                prompts.version(),
                requestKey,
                inputTokens,
                outputTokens,
                BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP),
                latencyMs,
                false,
                responseText));
    }

    private void checkBudget(java.util.UUID tenantId) {
        Instant since = Instant.now(clock).atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        BigDecimal spent = calls.spentSince(tenantId, since);
        if (spent.compareTo(properties.dailyBudgetUsd()) >= 0) {
            throw new QuotaExceeded("дневной бюджет LLM исчерпан");
        }
    }

    private BigDecimal cost(String provider, String model, int inputTokens, int outputTokens) {
        String key = provider + "/" + model;
        UzelokProperties.Pricing pricing = properties.pricing().get(key);
        if (pricing == null) {
            if (missingPricing.add(key)) {
                log.warn("нет цены LLM для {}; стоимость не рассчитана", key);
            }
            return null;
        }
        BigDecimal input = BigDecimal.valueOf(inputTokens).divide(MILLION, 12, RoundingMode.HALF_UP)
                .multiply(pricing.inputPerMillion() == null
                        ? BigDecimal.ZERO : pricing.inputPerMillion());
        BigDecimal output = BigDecimal.valueOf(outputTokens).divide(MILLION, 12, RoundingMode.HALF_UP)
                .multiply(pricing.outputPerMillion() == null
                        ? BigDecimal.ZERO : pricing.outputPerMillion());
        return input.add(output).setScale(6, RoundingMode.HALF_UP);
    }

    private String requestKey(String step, LlmContext context, String model) {
        return LlmRequestKey.of(step, context.inputHash(), model, prompts.version(),
                context.draftVersion());
    }

    private static String step(LlmContext context) {
        String value = context.step() == null || context.step().isBlank()
                ? "EXTRACT"
                : context.step().toUpperCase(Locale.ROOT);
        return value;
    }

    private String draftPrompt(Extraction extraction, Taxonomy taxonomy,
                               Set<String> existingNames) {
        ObjectNode root = mapper.createObjectNode();
        root.put("transcript", extraction.transcript());
        ArrayNode theses = root.putArray("theses");
        for (Thesis thesis : extraction.theses()) {
            ObjectNode node = theses.addObject();
            node.put("text", thesis.text());
            node.put("confidence", thesis.confidence());
        }
        ArrayNode unclear = root.putArray("unclear_spans");
        extraction.unclearSpans().forEach(unclear::add);

        ArrayNode domains = root.putArray("taxonomy");
        for (Taxonomy.Domain domain : taxonomy.domains()) {
            ObjectNode domainNode = domains.addObject();
            domainNode.put("domain", domain.id());
            ArrayNode topics = domainNode.putArray("topics");
            for (Taxonomy.Topic topic : domain.topics()) {
                ObjectNode topicNode = topics.addObject();
                topicNode.put("topic", topic.id());
                ArrayNode keywords = topicNode.putArray("keywords");
                topic.aliases().forEach(keywords::add);
            }
        }

        ArrayNode names = root.putArray("existing_names");
        existingNames.stream().sorted().forEach(names::add);
        root.put("confidence", extraction.confidence());
        return mapper.writeValueAsString(root);
    }

    private Extraction parseTheses(String sourceText, String content) {
        JsonNode root = readObject(content);
        double confidence = requiredConfidence(root, "confidence");
        JsonNode thesesNode = root.path("theses");
        if (!thesesNode.isArray()) {
            throw new LlmContractViolation("поле theses должно быть массивом");
        }
        List<Thesis> theses = new ArrayList<>();
        for (JsonNode node : thesesNode) {
            theses.add(new Thesis(requiredText(node, "text"), requiredConfidence(node, "confidence")));
        }
        if (theses.isEmpty()) {
            throw new LlmContractViolation("поле theses не должно быть пустым");
        }
        return new Extraction(sourceText, theses, List.of(), confidence);
    }

    private Extraction parseExtraction(String content) {
        JsonNode root = readObject(content);
        String transcript = requiredText(root, "transcript");
        double confidence = requiredConfidence(root, "confidence");

        JsonNode thesesNode = root.path("theses");
        if (!thesesNode.isArray()) {
            throw new LlmContractViolation("поле theses должно быть массивом");
        }
        List<Thesis> theses = new ArrayList<>();
        for (JsonNode node : thesesNode) {
            if (!node.isObject()) {
                throw new LlmContractViolation("тезис должен быть объектом");
            }
            theses.add(new Thesis(requiredText(node, "text"), requiredConfidence(node, "confidence")));
        }
        if (theses.isEmpty()) {
            throw new LlmContractViolation("поле theses не должно быть пустым");
        }

        JsonNode unclearNode = root.path("unclear_spans");
        if (!unclearNode.isArray()) {
            throw new LlmContractViolation("поле unclear_spans должно быть массивом");
        }
        List<String> unclear = new ArrayList<>();
        for (JsonNode node : unclearNode) {
            if (!node.isTextual()) {
                throw new LlmContractViolation("фрагмент unclear_spans должен быть строкой");
            }
            unclear.add(node.asString());
        }
        return new Extraction(transcript, theses, unclear, confidence);
    }

    private NoteDraft parseDraft(String content) {
        JsonNode root = readObject(content);
        String title = requiredText(root, "title");
        String typeValue = requiredText(root, "type");
        NoteType type;
        try {
            type = NoteType.valueOf(typeValue);
        } catch (IllegalArgumentException exception) {
            throw new LlmContractViolation("неизвестный тип заметки: " + typeValue);
        }
        String topic = requiredText(root, "topic");
        return new NoteDraft(
                title,
                type,
                topic,
                summary(requiredText(root, "summary")),
                requiredText(root, "body_md"),
                requiredConfidence(root, "confidence"));
    }

    private static String summary(String raw) {
        String oneLine = raw.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("[ \t]+", " ").trim();
        if (oneLine.length() <= SUMMARY_LIMIT) {
            return oneLine;
        }
        int cut = SUMMARY_LIMIT - 1;
        int boundary = oneLine.lastIndexOf(' ', cut);
        if (boundary > 0) {
            cut = boundary;
        }
        return oneLine.substring(0, cut).stripTrailing() + "…";
    }

    private JsonNode readObject(String content) {
        if (content == null || content.isBlank()) {
            throw new LlmContractViolation("ответ LLM пуст");
        }
        JsonNode root;
        try {
            root = mapper.readTree(content);
        } catch (JacksonException exception) {
            throw new LlmContractViolation("ответ LLM не является JSON", exception);
        }
        if (!root.isObject()) {
            throw new LlmContractViolation("ответ LLM должен быть JSON-объектом");
        }
        return root;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asString().isBlank()) {
            throw new LlmContractViolation("поле " + field + " не заполнено");
        }
        return value.asString();
    }

    private static double requiredConfidence(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            throw new LlmContractViolation("поле " + field + " должно быть числом");
        }
        double confidence = value.asDouble();
        if (confidence < 0.0 || confidence > 1.0) {
            throw new LlmContractViolation("поле " + field + " вне диапазона 0..1");
        }
        return confidence;
    }
}
