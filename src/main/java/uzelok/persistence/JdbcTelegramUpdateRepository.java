package uzelok.persistence;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uzelok.domain.port.TelegramUpdateRepository;

@Repository
public class JdbcTelegramUpdateRepository implements TelegramUpdateRepository {

    private static final String INSERT_UPDATE = """
            INSERT INTO telegram_update (update_id, chat_id, update_type)
            VALUES (:updateId, :chatId, :updateType)
            ON CONFLICT (update_id) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcTelegramUpdateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public boolean register(int updateId, Long chatId, String updateType) {
        int inserted = jdbc.update(
                INSERT_UPDATE,
                new MapSqlParameterSource()
                        .addValue("updateId", updateId)
                        .addValue("chatId", chatId)
                        .addValue("updateType", updateType));
        return inserted == 1;
    }
}
