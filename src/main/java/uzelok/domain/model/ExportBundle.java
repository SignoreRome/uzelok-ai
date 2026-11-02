package uzelok.domain.model;

import java.nio.file.Path;
import java.util.List;

public record ExportBundle(
        Path tempDirectory,
        List<ExportPart> parts,
        int fileCount,
        List<String> warnings) {

    public ExportBundle {
        parts = parts == null ? List.of() : List.copyOf(parts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
