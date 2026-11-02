package uzelok.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.SourceItem;
import uzelok.domain.port.SourceItemCreation;
import uzelok.domain.port.SourceItemRepository;

public final class InMemorySourceItemRepository implements SourceItemRepository {

    private final Map<UUID, SourceItem> items = new LinkedHashMap<>();

    @Override
    public synchronized SourceItemCreation ensure(SourceItem candidate) {
        Optional<SourceItem> existing = findExisting(candidate);
        if (existing.isPresent()) {
            return new SourceItemCreation(existing.get(), false);
        }
        SourceItem stored = new SourceItem(
                candidate.id() == null ? UUID.randomUUID() : candidate.id(),
                candidate.tenantId(),
                candidate.knowledgeBaseId(),
                candidate.chatId(),
                candidate.telegramMessageId(),
                candidate.kind(),
                candidate.mediaGroupId(),
                candidate.textBody(),
                candidate.createdAt() == null ? java.time.Instant.now() : candidate.createdAt());
        items.put(stored.id(), stored);
        return new SourceItemCreation(stored, true);
    }

    @Override
    public synchronized Optional<SourceItem> findByChatAndMessage(long chatId, long telegramMessageId) {
        return items.values().stream()
                .filter(item -> item.chatId() == chatId && item.telegramMessageId() == telegramMessageId)
                .findFirst();
    }

    @Override
    public synchronized Optional<SourceItem> findByChatAndMediaGroup(long chatId, String mediaGroupId) {
        return items.values().stream()
                .filter(item -> item.chatId() == chatId)
                .filter(item -> mediaGroupId.equals(item.mediaGroupId()))
                .findFirst();
    }

    @Override
    public synchronized boolean setTextBody(UUID id, String text) {
        SourceItem existing = items.get(id);
        if (existing == null || existing.textBody() != null) {
            return false;
        }
        items.put(id, new SourceItem(
                existing.id(),
                existing.tenantId(),
                existing.knowledgeBaseId(),
                existing.chatId(),
                existing.telegramMessageId(),
                existing.kind(),
                existing.mediaGroupId(),
                text,
                existing.createdAt()));
        return true;
    }

    @Override
    public synchronized Optional<SourceItem> findById(UUID id) {
        return Optional.ofNullable(items.get(id));
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized java.util.List<SourceItem> all() {
        return java.util.List.copyOf(items.values());
    }

    private Optional<SourceItem> findExisting(SourceItem candidate) {
        if (candidate.mediaGroupId() != null && !candidate.mediaGroupId().isBlank()) {
            return items.values().stream()
                    .filter(item -> item.chatId() == candidate.chatId())
                    .filter(item -> candidate.mediaGroupId().equals(item.mediaGroupId()))
                    .findFirst();
        }
        return findByChatAndMessage(candidate.chatId(), candidate.telegramMessageId());
    }
}
