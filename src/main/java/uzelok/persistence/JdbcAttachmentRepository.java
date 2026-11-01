package uzelok.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uzelok.domain.model.SourceAttachment;
import uzelok.domain.port.AttachmentRepository;

@Repository
public class JdbcAttachmentRepository implements AttachmentRepository {

    private static final String INSERT_ATTACHMENT = """
            INSERT INTO source_attachment (source_item_id, tenant_id, position, telegram_file_id,
                                           file_path, mime, byte_size, sha256)
            SELECT :sourceItemId, :tenantId, COALESCE(MAX(position) + 1, 0),
                   :telegramFileId, :filePath, :mime, :byteSize, :sha256
            FROM source_attachment
            WHERE source_item_id = :sourceItemId
            RETURNING id, source_item_id, tenant_id, position, telegram_file_id,
                      file_path, mime, byte_size, sha256, created_at
            """;

    private static final String SELECT_BY_SOURCE_ITEM = """
            SELECT id, source_item_id, tenant_id, position, telegram_file_id,
                   file_path, mime, byte_size, sha256, created_at
            FROM source_attachment
            WHERE source_item_id = :sourceItemId
            ORDER BY position
            """;

    private static final String COUNT_BY_SOURCE_ITEM = """
            SELECT COUNT(*) FROM source_attachment WHERE source_item_id = :sourceItemId
            """;

    private static final RowMapper<SourceAttachment> ROW_MAPPER = (rs, rowNum) -> new SourceAttachment(
            rs.getObject("id", UUID.class),
            rs.getObject("source_item_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getInt("position"),
            rs.getString("telegram_file_id"),
            rs.getString("file_path"),
            rs.getString("mime"),
            rs.getLong("byte_size"),
            rs.getString("sha256"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAttachmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Намеренно без @Transactional: DuplicateKeyException помечает текущую транзакцию rollback-only,
    // из-за чего повтор вставки после гонки за position уже не может завершиться успешно.
    @Override
    public SourceAttachment append(UUID sourceItemId, UUID tenantId, String telegramFileId, String filePath,
                                   String mime, long byteSize, String sha256) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sourceItemId", sourceItemId)
                .addValue("tenantId", tenantId)
                .addValue("telegramFileId", telegramFileId)
                .addValue("filePath", filePath)
                .addValue("mime", mime)
                .addValue("byteSize", byteSize)
                .addValue("sha256", sha256);
        try {
            return jdbc.queryForObject(INSERT_ATTACHMENT, params, ROW_MAPPER);
        } catch (DuplicateKeyException ignored) {
            return jdbc.queryForObject(INSERT_ATTACHMENT, params, ROW_MAPPER);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceAttachment> listBySourceItem(UUID sourceItemId) {
        return jdbc.query(
                SELECT_BY_SOURCE_ITEM,
                new MapSqlParameterSource("sourceItemId", sourceItemId),
                ROW_MAPPER);
    }

    @Override
    @Transactional(readOnly = true)
    public int countBySourceItem(UUID sourceItemId) {
        Integer count = jdbc.queryForObject(
                COUNT_BY_SOURCE_ITEM,
                new MapSqlParameterSource("sourceItemId", sourceItemId),
                Integer.class);
        return count == null ? 0 : count;
    }
}
