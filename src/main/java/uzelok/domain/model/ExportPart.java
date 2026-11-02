package uzelok.domain.model;

import java.nio.file.Path;

public record ExportPart(String fileName, Path path, long sizeBytes) {
}
