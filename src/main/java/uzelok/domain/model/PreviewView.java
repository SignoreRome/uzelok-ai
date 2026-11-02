package uzelok.domain.model;

import java.util.List;
import java.util.UUID;

public record PreviewView(
        UUID jobId,
        long chatId,
        Long statusMessageId,
        String relPath,
        String topic,
        String summary,
        String bodyExcerpt,
        String fullMarkdown,
        List<String> warnings) {

    public PreviewView {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
