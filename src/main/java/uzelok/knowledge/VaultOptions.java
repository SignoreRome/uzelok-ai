package uzelok.knowledge;

public record VaultOptions(String tmpDir, double confidenceThreshold) {

    public VaultOptions {
        tmpDir = tmpDir == null || tmpDir.isBlank() ? ".uzelok-tmp" : tmpDir;
        if (!(confidenceThreshold >= 0 && confidenceThreshold <= 1)) {
            throw new IllegalArgumentException(
                    "confidenceThreshold должен быть в диапазоне 0..1: " + confidenceThreshold);
        }
    }
}
