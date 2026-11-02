package uzelok.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uzelok.domain.model.SourceItem;
import uzelok.domain.model.SourceKind;
import uzelok.domain.port.SourceItemCreation;
import uzelok.domain.port.SourceItemRepository;

@Repository
public class JdbcSourceItemRepository implements SourceItemRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, knowledge_base_id, chat_id, telegram_message_id,
                   kind, media_group_id, text_body, created_at
            FROM source_item
            """;

    private static final String SELECT_BY_MESSAGE = SELECT_COLUMNS
            + " WHERE chat_id = :chatId AND telegram_message_id = :telegramMessageId";

    private static final String SELECT_BY_MEDIA_GROUP = SELECT_COLUMNS
            + " WHERE chat_id = :chatId AND media_group_id = :mediaGroupId";

    private static final String INSERT_SOURCE_ITEM = """
            INSERT INTO source_item (id, tenant_id, knowledge_base_id, chat_id, telegram_message_id,
                                     kind, media_group_id, text_body, created_at)
            VALUES (:id, :tenantId, :knowledgeBaseId, :chatId, :telegramMessageId,
                    :kind, :mediaGroupId, :textBody, :createdAt)
            ON CONFLICT DO NOTHING
            """;

    private static final String UPDATE_TEXT_BODY = """
            UPDATE source_item
            SET text_body = :text
            WHERE id = :id AND text_body IS NULL
            """;

    private static final RowMapper<SourceItem> ROW_MAPPER = (rs, rowNum) -> new SourceItem(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("knowledge_base_id", UUID.class),
            rs.getLong("chat_id"),
            rs.getLong("telegram_message_id"),
            SourceKind.valueOf(rs.getString("kind")),
            rs.getString("media_group_id"),
            rs.getString("text_body"),
            toInstant(rs.getObject("created_at", OffsetDateTime.class)));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSourceItemRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public SourceItemCreation ensure(SourceItem candidate) {
        Optional<SourceItem> existing = findExisting(candidate.chatId(), candidate.telegramMessageId(),
                candidate.mediaGroupId());
        if (existing.isPresent()) {
            return new SourceItemCreation(existing.get(), false);
        }

        UUID id = candidate.id() == null ? UUID.randomUUID() : candidate.id();
        Instant createdAt = candidate.createdAt() == null ? Instant.now() : candidate.createdAt();
        int inserted = jdbc.update(INSERT_SOURCE_ITEM, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("tenantId", candidate.tenantId())
                .addValue("knowledgeBaseId", candidate.knowledgeBaseId())
                .addValue("chatId", candidate.chatId())
                .addValue("telegramMessageId", candidate.telegramMessageId())
                .addValue("kind", candidate.kind().name())
                .addValue("mediaGroupId", blankToNull(candidate.mediaGroupId()))
                .addValue("textBody", candidate.textBody())
                .addValue("createdAt", toOffsetDateTime(createdAt)));

        SourceItem item = findExisting(candidate.chatId(), candidate.telegramMessageId(),
                        candidate.mediaGroupId())
                .orElseThrow(() -> new IllegalStateException(
                        "не удалось прочитать source_item после вставки"));
        return new SourceItemCreation(item, inserted == 1);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SourceItem> findById(UUID id) {
        List<SourceItem> found = jdbc.query(
                SELECT_COLUMNS + " WHERE id = :id",
                new MapSqlParameterSource("id", id),
                ROW_MAPPER);
        return found.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SourceItem> findByChatAndMessage(long chatId, long telegramMessageId) {
        List<SourceItem> found = jdbc.query(
                SELECT_BY_MESSAGE,
                new MapSqlParameterSource()
                        .addValue("chatId", chatId)
                        .addValue("telegramMessageId", telegramMessageId),
                ROW_MAPPER);
        return found.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SourceItem> findByChatAndMediaGroup(long chatId, String mediaGroupId) {
        List<SourceItem> found = jdbc.query(
                SELECT_BY_MEDIA_GROUP,
                new MapSqlParameterSource()
                        .addValue("chatId", chatId)
                        .addValue("mediaGroupId", mediaGroupId),
                ROW_MAPPER);
        return found.stream().findFirst();
    }

    @Override
    @Transactional
    public boolean setTextBody(UUID id, String text) {
        int updated = jdbc.update(
                UPDATE_TEXT_BODY,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("text", text));
        return updated == 1;
    }

    private Optional<SourceItem> findExisting(long chatId, long telegramMessageId, String mediaGroupId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("chatId", chatId);
        String sql;
        if (mediaGroupId != null && !mediaGroupId.isBlank()) {
            sql = SELECT_BY_MEDIA_GROUP;
            params.addValue("mediaGroupId", mediaGroupId);
        } else {
            sql = SELECT_BY_MESSAGE;
            params.addValue("telegramMessageId", telegramMessageId);
        }
        List<SourceItem> found = jdbc.query(sql, params, ROW_MAPPER);
        return found.stream().findFirst();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime toOffsetDateTime(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
