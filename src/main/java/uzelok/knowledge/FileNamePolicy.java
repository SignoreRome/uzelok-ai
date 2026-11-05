package uzelok.knowledge;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class FileNamePolicy {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern INVALID_CHARS = Pattern.compile("[^A-Za-z0-9_]");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-.]+");
    private static final Pattern KNOWN_EXTENSION = Pattern.compile("(?i)\\.(md|markdown)$");
    private static final Pattern REPEATED_UNDERSCORES = Pattern.compile("_+");
    private static final Pattern EDGE_UNDERSCORES = Pattern.compile("^_+|_+$");
    private static final String FALLBACK_NAME = "Note";

    private static final Map<Character, String> TRANSLITERATION = transliterationTable();

    private final int maxLength;

    public FileNamePolicy(int maxLength) {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength должен быть положительным: " + maxLength);
        }
        this.maxLength = maxLength;
    }

    public String fromTitle(String title) {
        String transliterated = transliterate(title == null ? "" : title);
        String normalized = INVALID_CHARS.matcher(transliterated).replaceAll("_");
        normalized = REPEATED_UNDERSCORES.matcher(normalized).replaceAll("_");
        normalized = EDGE_UNDERSCORES.matcher(normalized).replaceAll("");
        if (normalized.isEmpty()) {
            return FALLBACK_NAME;
        }
        String name = titleCaseTokens(normalized);
        return limit(name.isEmpty() ? FALLBACK_NAME : name);
    }

    public String sanitize(String candidate, String fallbackTitle) {
        String value = candidate == null ? "" : candidate.trim();
        value = KNOWN_EXTENSION.matcher(value).replaceFirst("");
        boolean mixedAlphabet = value.codePoints()
                .anyMatch(codePoint -> codePoint > 127 && Character.isLetterOrDigit(codePoint));
        value = SEPARATORS.matcher(value).replaceAll("_");
        value = INVALID_CHARS.matcher(value).replaceAll("");
        value = REPEATED_UNDERSCORES.matcher(value).replaceAll("_");
        value = EDGE_UNDERSCORES.matcher(value).replaceAll("");
        String name = titleCaseTokens(value);
        boolean tooFewSignificant = value.codePoints()
                .filter(Character::isLetterOrDigit).count() < 2;
        if (mixedAlphabet || tooFewSignificant || name.length() > maxLength) {
            return fromTitle(fallbackTitle);
        }
        return name;
    }

    private static String titleCaseTokens(String normalized) {
        StringBuilder result = new StringBuilder();
        for (String token : normalized.split("_")) {
            if (token.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append('_');
            }
            result.append(titleCase(token));
        }
        return result.toString();
    }

    public String withSuffix(String baseName, String suffix) {
        String base = baseName == null || baseName.isBlank() ? FALLBACK_NAME : baseName;
        String safeSuffix = suffix == null ? "" : suffix.trim();
        if (safeSuffix.isEmpty()) {
            return limit(base);
        }
        if (safeSuffix.length() >= maxLength) {
            safeSuffix = safeSuffix.substring(0, maxLength);
        }
        String candidate = base + "_" + safeSuffix;
        if (candidate.length() <= maxLength) {
            return candidate;
        }
        int available = maxLength - safeSuffix.length() - 1;
        if (available <= 0) {
            return safeSuffix;
        }
        String truncated = base.substring(0, Math.min(base.length(), available));
        int boundary = truncated.lastIndexOf('_');
        if (boundary > 0) {
            truncated = truncated.substring(0, boundary);
        }
        truncated = EDGE_UNDERSCORES.matcher(truncated).replaceAll("");
        if (truncated.isEmpty()) {
            return safeSuffix;
        }
        return truncated + "_" + safeSuffix;
    }

    public boolean isValid(String name) {
        if (name == null || name.isEmpty() || name.length() > maxLength) {
            return false;
        }
        if (!VALID_NAME.matcher(name).matches()) {
            return false;
        }
        char first = name.charAt(0);
        return Character.isDigit(first) || (first >= 'A' && first <= 'Z');
    }

    private String limit(String name) {
        if (name.length() <= maxLength) {
            return name;
        }
        String truncated = name.substring(0, maxLength);
        int boundary = truncated.lastIndexOf('_');
        if (boundary > 0) {
            truncated = truncated.substring(0, boundary);
        }
        truncated = EDGE_UNDERSCORES.matcher(truncated).replaceAll("");
        return truncated.isEmpty() ? FALLBACK_NAME : truncated;
    }

    private static String titleCase(String token) {
        boolean lettersOnlyUpper = token.equals(token.toUpperCase(Locale.ROOT))
                && token.chars().anyMatch(Character::isLetter);
        if (lettersOnlyUpper && token.length() <= 5) {
            return token;
        }
        return Character.toUpperCase(token.charAt(0)) + token.substring(1);
    }

    private static String transliterate(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 128) {
                result.append(character);
                continue;
            }
            String replacement = TRANSLITERATION.get(Character.toLowerCase(character));
            result.append(replacement == null ? "_" : replacement);
        }
        return result.toString();
    }

    private static Map<Character, String> transliterationTable() {
        Map<Character, String> table = new HashMap<>();
        put(table, "аa", "бb", "вv", "гg", "дd", "еe", "ёe", "жzh", "зz", "иi", "йy",
                "кk", "лl", "мm", "нn", "оo", "пp", "рr", "сs", "тt", "уu", "фf", "хkh",
                "цts", "чch", "шsh", "щsch", "ъ", "ыy", "ь", "эe", "юyu", "яya");
        return Map.copyOf(table);
    }

    private static void put(Map<Character, String> table, String... pairs) {
        for (String pair : pairs) {
            if (pair.length() >= 1) {
                char key = pair.charAt(0);
                String value = pair.length() > 1 ? pair.substring(1) : "";
                table.put(key, value);
            }
        }
    }
}
