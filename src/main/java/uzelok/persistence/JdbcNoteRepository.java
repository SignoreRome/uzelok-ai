package uzelok.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uzelok.domain.model.NoteRecord;
import uzelok.domain.port.NoteRepository;

@Repository
public class JdbcNoteRepository implements NoteRepository {

    private static final String UPSERT_NOTE = """
            INSERT INTO note (id, tenant_id, knowledge_base_id, job_id, type, title, domain,
                              topic, file_name, rel_path, content_hash)
            VALUES (:id, :tenantId, :knowledgeBaseId, :jobId, :type, :title, :domain,
                    :topic, :fileName, :relPath, :contentHash)
            ON CONFLICT (knowledge_base_id, file_name) DO UPDATE
            SET content_hash = EXCLUDED.content_hash,
                rel_path = EXCLUDED.rel_path,
                updated_at = now()
            RETURNING id
            """;

    private static final String APPEND_VERSION = """
            INSERT INTO note_version (note_id, tenant_id, version_no, markdown, content_hash)
            SELECT :noteId, :tenantId, COALESCE(MAX(version_no), 0) + 1, :markdown, :contentHash
            FROM note_version
            WHERE note_id = :noteId
            """;

    private static final String COUNT_BY_TENANT = """
            SELECT COUNT(*) FROM note WHERE tenant_id = :tenantId
            """;

    private static final String LATEST_VERSION_HASH = """
            SELECT content_hash FROM note_version
            WHERE note_id = :noteId
            ORDER BY version_no DESC
            LIMIT 1
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcNoteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public UUID upsert(NoteRecord note) {
        return jdbc.queryForObject(UPSERT_NOTE, new MapSqlParameterSource()
                .addValue("id", note.noteId())
                .addValue("tenantId", note.tenantId())
                .addValue("knowledgeBaseId", note.knowledgeBaseId())
                .addValue("jobId", note.jobId())
                .addValue("type", note.type() == null ? null : note.type().name())
                .addValue("title", note.title())
                .addValue("domain", note.domain())
                .addValue("topic", note.topic())
                .addValue("fileName", note.fileName())
                .addValue("relPath", note.relPath())
                .addValue("contentHash", note.contentHash()), UUID.class);
    }

    @Override
    @Transactional
    public void appendVersion(UUID noteId, UUID tenantId, String markdown, String contentHash) {
        jdbc.update(APPEND_VERSION, new MapSqlParameterSource()
                .addValue("noteId", noteId)
                .addValue("tenantId", tenantId)
                .addValue("markdown", markdown)
                .addValue("contentHash", contentHash));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> latestVersionHash(UUID noteId) {
        List<String> hashes = jdbc.query(
                LATEST_VERSION_HASH,
                new MapSqlParameterSource("noteId", noteId),
                (rs, rowNum) -> rs.getString("content_hash"));
        return hashes.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByTenant(UUID tenantId) {
        Long count = jdbc.queryForObject(
                COUNT_BY_TENANT,
                new MapSqlParameterSource("tenantId", tenantId),
                Long.class);
        return count == null ? 0 : count;
    }
}
