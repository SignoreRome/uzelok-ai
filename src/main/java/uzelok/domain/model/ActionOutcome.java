package uzelok.domain.model;

public record ActionOutcome(boolean handled, String userMessage) {

    public static ActionOutcome ignored() {
        return new ActionOutcome(false, null);
    }

    public static ActionOutcome done(String message) {
        return new ActionOutcome(true, message);
    }
}
