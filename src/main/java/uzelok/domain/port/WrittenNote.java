package uzelok.domain.port;

import java.util.UUID;

public record WrittenNote(UUID noteId, String relPath, String contentHash, boolean created) {
}
