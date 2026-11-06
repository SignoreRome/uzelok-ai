package uzelok.domain.model;

public record NoteDraft(
        String title,
        NoteType type,
        String topic,
        String summary,
        String bodyMd,
        double confidence,
        String fileName) {

    public NoteDraft(
            String title,
            NoteType type,
            String topic,
            String summary,
            String bodyMd,
            double confidence) {
        this(title, type, topic, summary, bodyMd, confidence, null);
    }
}
