package uzelok.domain.model;

public record RenderedNote(
        String relPath,
        String fileName,
        String frontmatter,
        String markdown,
        String hash) {
}
