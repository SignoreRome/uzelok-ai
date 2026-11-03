package uzelok.knowledge;

public record VaultOptions(double confidenceThreshold) {

    public VaultOptions {
        if (!(confidenceThreshold >= 0 && confidenceThreshold <= 1)) {
            throw new IllegalArgumentException(
                    "confidenceThreshold должен быть в диапазоне 0..1: " + confidenceThreshold);
        }
    }
}
