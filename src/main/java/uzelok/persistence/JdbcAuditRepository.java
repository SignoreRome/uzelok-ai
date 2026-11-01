package uzelok.persistence;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import uzelok.domain.model.AuditRecord;
import uzelok.domain.port.AuditRepository;

@Repository
public class JdbcAuditRepository implements AuditRepository {

    private static final String INSERT_AUDIT = """
            INSERT INTO audit_event (tenant_id, chat_id, action, entity_type, entity_id, detail)
            VALUES (:tenantId, :chatId, :action, :entityType, :entityId, CAST(:detail AS jsonb))
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public JdbcAuditRepository(NamedParameterJdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Override
    @Transactional
    public void record(AuditRecord record) {
        String detail = record.detail() == null ? null : jsonMapper.writeValueAsString(record.detail());
        jdbc.update(INSERT_AUDIT, new MapSqlParameterSource()
                .addValue("tenantId", record.tenantId())
                .addValue("chatId", record.chatId())
                .addValue("action", record.action())
                .addValue("entityType", record.entityType())
                .addValue("entityId", record.entityId())
                .addValue("detail", detail));
    }
}
