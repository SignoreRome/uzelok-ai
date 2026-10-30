package uzelok.domain.port;

import java.util.List;

public record PreparedNote(
        String relPath,
        String fileName,
        String markdown,
        String contentHash,
        List<String> warnings) {

    public PreparedNote {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
