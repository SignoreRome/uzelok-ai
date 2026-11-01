package uzelok.domain.port;

import java.util.Optional;
import uzelok.domain.model.ChatAccess;

public interface AccessRepository {

    Optional<ChatAccess> findByChatId(long chatId);
}
