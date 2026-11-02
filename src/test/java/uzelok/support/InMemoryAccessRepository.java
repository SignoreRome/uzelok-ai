package uzelok.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import uzelok.domain.model.ChatAccess;
import uzelok.domain.port.AccessRepository;

public final class InMemoryAccessRepository implements AccessRepository {

    private final Map<Long, ChatAccess> chats = new LinkedHashMap<>();

    public InMemoryAccessRepository add(ChatAccess access) {
        chats.put(access.telegramUserId(), access);
        return this;
    }

    public InMemoryAccessRepository add(long chatId, ChatAccess access) {
        chats.put(chatId, access);
        return this;
    }

    @Override
    public synchronized Optional<ChatAccess> findByChatId(long chatId) {
        return Optional.ofNullable(chats.get(chatId));
    }
}
