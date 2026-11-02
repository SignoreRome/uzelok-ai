package uzelok.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uzelok.domain.model.ArtifactKind;
import uzelok.domain.model.JobArtifact;
import uzelok.domain.port.ArtifactRepository;

@Repository
public class JdbcArtifactRepository implements ArtifactRepository {

    private static final String UPSERT = """
            INSERT INTO job_artifact (id, job_id, tenant_id, kind, version, sha256,
                                      storage_path, inline_text, model, prompt_version, created_at)
            VALUES (COALESCE(CAST(:id AS uuid), gen_random_uuid()), :jobId, :tenantId, :kind,
                    :version, :sha256, :storagePath, :inlineText, :model, :promptVersion,
                    COALESCE(CAST(:createdAt AS timestamptz), now()))
            ON CONFLICT (job_id, kind, version) DO UPDATE
            SET sha256 = EXCLUDED.sha256,
                storage_path = EXCLUDED.storage_path,
                inline_text = EXCLUDED.inline_text,
                model = EXCLUDED.model,
                prompt_version = EXCLUDED.prompt_version
            RETURNING id, job_id, tenant_id, kind, version, sha256, storage_path, inline_text,
                      model, prompt_version, created_at
            """;

    private static final String FIND_LATEST = """
            SELECT id, job_id, tenant_id, kind, version, sha256, storage_path, inline_text,
                   model, prompt_version, created_at
            FROM job_artifact
            WHERE job_id = :jobId AND kind = :kind
            ORDER BY version DESC
            LIMIT 1
            """;

    private static final RowMapper<JobArtifact> ROW_MAPPER = (rs, rowNum) -> new JobArtifact(
            rs.getObject("id", UUID.class),
            rs.getObject("job_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            ArtifactKind.valueOf(rs.getString("kind")),
            rs.getInt("version"),
            rs.getString("sha256"),
            rs.getString("storage_path"),
            rs.getString("inline_text"),
            rs.getString("model"),
            rs.getString("prompt_version"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcArtifactRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public JobArtifact save(JobArtifact artifact) {
        return jdbc.queryForObject(UPSERT, new MapSqlParameterSource()
                .addValue("id", artifact.id())
                .addValue("jobId", artifact.jobId())
                .addValue("tenantId", artifact.tenantId())
                .addValue("kind", artifact.kind().name())
                .addValue("version", artifact.version())
                .addValue("sha256", artifact.sha256())
                .addValue("storagePath", artifact.storagePath())
                .addValue("inlineText", artifact.inlineText())
                .addValue("model", artifact.model())
                .addValue("promptVersion", artifact.promptVersion())
                .addValue("createdAt", artifact.createdAt() == null
                        ? null
                        : OffsetDateTime.ofInstant(artifact.createdAt(), ZoneOffset.UTC)),
                ROW_MAPPER);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JobArtifact> findLatest(UUID jobId, ArtifactKind kind) {
        List<JobArtifact> found = jdbc.query(
                FIND_LATEST,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("kind", kind.name()),
                ROW_MAPPER);
        return found.stream().findFirst();
    }
}
