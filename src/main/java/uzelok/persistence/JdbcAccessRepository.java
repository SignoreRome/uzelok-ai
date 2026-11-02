package uzelok.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uzelok.domain.model.ChatAccess;
import uzelok.domain.port.AccessRepository;

@Repository
public class JdbcAccessRepository implements AccessRepository {

    private static final String SELECT_BY_CHAT = """
            SELECT ta.tenant_id, ta.user_id, au.telegram_user_id, ta.allowed
            FROM telegram_account ta
            JOIN app_user au ON au.id = ta.user_id
            WHERE ta.chat_id = :chatId
            """;

    private static final RowMapper<ChatAccess> ROW_MAPPER = (rs, rowNum) -> new ChatAccess(
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getLong("telegram_user_id"),
            rs.getBoolean("allowed"));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAccessRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ChatAccess> findByChatId(long chatId) {
        List<ChatAccess> found = jdbc.query(
                SELECT_BY_CHAT,
                new MapSqlParameterSource("chatId", chatId),
                ROW_MAPPER);
        return found.stream().findFirst();
    }
}
