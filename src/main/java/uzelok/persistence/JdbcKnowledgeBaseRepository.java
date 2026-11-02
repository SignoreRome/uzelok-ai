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
            SELECT id, tenant_id, kind, root_path, taxonomy_relpath, inbox_relpath
            FROM knowledge_base
            WHERE tenant_id = :tenantId AND active
            """;

    private static final String SELECT_BY_ROOT_PATH = """
            SELECT id, tenant_id, kind, root_path, taxonomy_relpath, inbox_relpath
            FROM knowledge_base
            WHERE root_path = :rootPath AND active
            """;

    private static final String UPSERT = """
            INSERT INTO knowledge_base (id, tenant_id, kind, root_path, taxonomy_relpath,
                                        inbox_relpath)
            VALUES (:id, :tenantId, :kind, :rootPath, :taxonomyRelPath, :inboxRelPath)
            ON CONFLICT (tenant_id) DO UPDATE
            SET active = true
            RETURNING id, tenant_id, kind, root_path, taxonomy_relpath, inbox_relpath
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

    @Override
    @Transactional(readOnly = true)
    public Optional<KnowledgeBase> findByRootPath(String rootPath) {
        List<KnowledgeBase> found = jdbc.query(
                SELECT_BY_ROOT_PATH,
                new MapSqlParameterSource("rootPath", rootPath),
                ROW_MAPPER);
        return found.stream().findFirst();
    }

    @Override
    @Transactional
    public KnowledgeBase save(UUID tenantId, KnowledgeBase base) {
        UUID id = base.id() == null ? UUID.randomUUID() : base.id();
        return jdbc.queryForObject(UPSERT, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("tenantId", tenantId)
                .addValue("kind", base.kind().name())
                .addValue("rootPath", base.rootPath())
                .addValue("taxonomyRelPath", base.taxonomyRelPath())
                .addValue("inboxRelPath", base.inboxRelPath()), ROW_MAPPER);
    }
}
