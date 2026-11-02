package uzelok.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.port.KnowledgeBaseRepository;

@Repository
public class JdbcKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private static final String SELECT_ACTIVE = """
            SELECT id, kind, root_path, taxonomy_relpath, inbox_relpath
            FROM knowledge_base
            WHERE tenant_id = :tenantId AND active
            """;

    private static final RowMapper<KnowledgeBase> ROW_MAPPER = (rs, rowNum) -> new KnowledgeBase(
            rs.getObject("id", UUID.class),
            KnowledgeBaseKind.valueOf(rs.getString("kind")),
            rs.getString("root_path"),
            rs.getString("taxonomy_relpath"),
            rs.getString("inbox_relpath"));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcKnowledgeBaseRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<KnowledgeBase> findByTenantId(UUID tenantId) {
        List<KnowledgeBase> found = jdbc.query(
                SELECT_ACTIVE,
                new MapSqlParameterSource("tenantId", tenantId),
                ROW_MAPPER);
        return found.stream().findFirst();
    }
}
