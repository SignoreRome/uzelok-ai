package uzelok.domain.model;

import java.util.List;
import java.util.UUID;

public record RenderedNote(
        UUID noteId,
        String relPath,
        String fileName,
        String frontmatter,
        String markdown,
        String contentHash,
        List<String> warnings,
        boolean inbox) {

    public RenderedNote {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
