package uzelok.knowledge;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public final class VaultLockRegistry {

    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T withLock(UUID knowledgeBaseId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(knowledgeBaseId, key -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
