package uzelok.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.NoteRecord;
import uzelok.domain.port.NoteRepository;

public final class InMemoryNoteRepository implements NoteRepository {

    public record Version(UUID noteId, int number, String markdown, String contentHash) {
    }

    private final Map<String, NoteRecord> notes = new LinkedHashMap<>();
    private final List<Version> versions = new ArrayList<>();

    @Override
    public synchronized UUID upsert(NoteRecord note) {
        String key = note.knowledgeBaseId() + "/" + note.fileName();
        NoteRecord existing = notes.get(key);
        if (existing != null) {
            NoteRecord updated = new NoteRecord(existing.noteId(), note.tenantId(),
                    note.knowledgeBaseId(), note.jobId(), note.type(), note.title(), note.domain(),
                    note.topic(), note.fileName(), note.relPath(), note.contentHash());
            notes.put(key, updated);
            return existing.noteId();
        }
        UUID id = note.noteId() == null ? UUID.randomUUID() : note.noteId();
        notes.put(key, new NoteRecord(id, note.tenantId(), note.knowledgeBaseId(), note.jobId(),
                note.type(), note.title(), note.domain(), note.topic(), note.fileName(),
                note.relPath(), note.contentHash()));
        return id;
    }

    @Override
    public synchronized void appendVersion(UUID noteId, UUID tenantId, String markdown,
                                           String contentHash) {
        int number = (int) versions.stream()
                .filter(version -> version.noteId().equals(noteId))
                .count() + 1;
        versions.add(new Version(noteId, number, markdown, contentHash));
    }

    @Override
    public synchronized Optional<String> latestVersionHash(UUID noteId) {
        for (int index = versions.size() - 1; index >= 0; index--) {
            Version version = versions.get(index);
            if (version.noteId().equals(noteId)) {
                return Optional.ofNullable(version.contentHash());
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized long countByTenant(UUID tenantId) {
        return notes.values().stream()
                .filter(note -> note.tenantId().equals(tenantId))
                .count();
    }

    public synchronized Optional<NoteRecord> findByFileName(String fileName) {
        return notes.values().stream()
                .filter(note -> note.fileName().equals(fileName))
                .findFirst();
    }

    public synchronized List<Version> versions() {
        return List.copyOf(versions);
    }

    public synchronized int noteCount() {
        return notes.size();
    }
}
