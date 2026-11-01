package uzelok.domain.port;

import java.util.List;
import java.util.UUID;

public record PreparedNote(
        UUID noteId,
        String relPath,
        String fileName,
        String markdown,
        String contentHash,
        List<String> warnings) {

    public PreparedNote {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
