package uzelok.support;

import java.util.LinkedHashMap;
import java.util.Map;
import uzelok.domain.port.TelegramUpdateRepository;

public final class InMemoryTelegramUpdateRepository implements TelegramUpdateRepository {

    private final Map<Integer, String> updates = new LinkedHashMap<>();

    @Override
    public synchronized boolean register(int updateId, Long chatId, String updateType) {
        if (updates.containsKey(updateId)) {
            return false;
        }
        updates.put(updateId, updateType);
        return true;
    }

    public synchronized int size() {
        return updates.size();
    }

    public synchronized String typeOf(int updateId) {
        return updates.get(updateId);
    }
}
