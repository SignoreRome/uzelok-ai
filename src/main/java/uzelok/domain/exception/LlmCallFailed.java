package uzelok.domain.exception;

public class LlmCallFailed extends DomainException {

    public LlmCallFailed(String message) {
        super(message);
    }

    public LlmCallFailed(String message, Throwable cause) {
        super(message, cause);
    }
}
