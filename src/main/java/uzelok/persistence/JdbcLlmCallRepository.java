package uzelok.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uzelok.domain.model.LlmCallRecord;
import uzelok.domain.port.LlmCallRepository;

@Repository
public class JdbcLlmCallRepository implements LlmCallRepository {

    private static final String FIND_SUCCESSFUL_RESPONSE = """
            SELECT response_text
            FROM llm_call
            WHERE request_key = :requestKey
              AND status = 'SUCCESS'
              AND response_text IS NOT NULL
            """;

    private static final String INSERT_CALL = """
            INSERT INTO llm_call (tenant_id, job_id, step, provider, model, prompt_version,
                                  request_key, input_tokens, output_tokens, cost_usd, latency_ms,
                                  status, response_text)
            VALUES (:tenantId, :jobId, :step, :provider, :model, :promptVersion,
                    :requestKey, :inputTokens, :outputTokens, :costUsd, :latencyMs,
                    :status, :responseText)
            ON CONFLICT (request_key) DO UPDATE SET
                status = EXCLUDED.status,
                model = EXCLUDED.model,
                provider = EXCLUDED.provider,
                input_tokens = EXCLUDED.input_tokens,
                output_tokens = EXCLUDED.output_tokens,
                cost_usd = EXCLUDED.cost_usd,
                latency_ms = EXCLUDED.latency_ms,
                response_text = EXCLUDED.response_text
            WHERE llm_call.status <> 'SUCCESS'
            """;

    private static final String SPENT_SINCE = """
            SELECT COALESCE(SUM(cost_usd), 0)
            FROM llm_call
            WHERE tenant_id = :tenantId AND created_at >= :since
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLlmCallRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findSuccessfulResponse(String requestKey) {
        List<String> found = jdbc.queryForList(
                FIND_SUCCESSFUL_RESPONSE,
                new MapSqlParameterSource("requestKey", requestKey),
                String.class);
        return found.stream().findFirst();
    }

    @Override
    @Transactional
    public void record(LlmCallRecord call) {
        jdbc.update(INSERT_CALL, new MapSqlParameterSource()
                .addValue("tenantId", call.tenantId())
                .addValue("jobId", call.jobId())
                .addValue("step", call.step())
                .addValue("provider", call.provider())
                .addValue("model", call.model())
                .addValue("promptVersion", call.promptVersion())
                .addValue("requestKey", call.requestKey())
                .addValue("inputTokens", call.inputTokens())
                .addValue("outputTokens", call.outputTokens())
                .addValue("costUsd", call.costUsd())
                .addValue("latencyMs", call.latencyMs())
                .addValue("status", call.success() ? "SUCCESS" : "FAILED")
                .addValue("responseText", call.responseText()));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal spentSince(UUID tenantId, Instant since) {
        BigDecimal spent = jdbc.queryForObject(
                SPENT_SINCE,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC)),
                BigDecimal.class);
        return spent == null ? BigDecimal.ZERO : spent;
    }
}
