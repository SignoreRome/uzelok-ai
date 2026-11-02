package uzelok.domain.model;

import java.util.List;

/** messageKey — ключ из strings/ru.properties, args — подстановки MessageFormat (строки). */
public record OnboardingResult(String messageKey, List<String> args) {

    public OnboardingResult {
        args = args == null ? List.of() : List.copyOf(args);
    }
}
