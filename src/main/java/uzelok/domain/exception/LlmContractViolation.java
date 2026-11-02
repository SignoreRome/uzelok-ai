package uzelok.domain.exception;

public class LlmContractViolation extends DomainException {

    public LlmContractViolation(String message) {
        super(message);
    }

    public LlmContractViolation(String message, Throwable cause) {
        super(message, cause);
    }
}
