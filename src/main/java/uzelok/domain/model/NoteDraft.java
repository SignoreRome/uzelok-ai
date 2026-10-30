package uzelok.domain.model;

public record NoteDraft(
        String title,
        NoteType type,
        String topic,
        String summary,
        String bodyMd,
        double confidence) {
}
