package uzelok.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ContentSanitizer {

    private static final Set<String> EXECUTABLE_LANGUAGES =
            Set.of("dataview", "dataviewjs", "templater", "jsengine");
    private static final Pattern TEMPLATER = Pattern.compile("(?s)<%[-+*]?.*?%>");
    private static final Pattern SCRIPT = Pattern.compile(
            "(?is)<script\\b[^>]*?(?<!/)>.*?</script\\s*>");
    private static final Pattern SCRIPT_SELF_CLOSING = Pattern.compile(
            "(?is)<script\\b[^>]*/\\s*>");
    private static final Pattern SCRIPT_UNCLOSED = Pattern.compile("(?is)<script\\b[^>]*>.*");
    private static final Pattern JAVASCRIPT_LINK = Pattern.compile(
            "(?i)\\[([^\\]]*)\\]\\(\\s*(?:javascript|vbscript):[^)]*\\)");

    public record SanitizedBody(String body, List<String> warnings) {

        public SanitizedBody {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public SanitizedBody sanitize(String bodyMd) {
        String body = bodyMd == null ? "" : bodyMd;
        Map<String, Integer> counts = new LinkedHashMap<>();

        body = stripExecutableFences(body, counts);

        body = replaceSimple(body, TEMPLATER, "[удалён шаблон Templater]", "templater", counts);
        body = replaceSimple(body, SCRIPT, "[удалён script]", "script", counts);
        body = replaceSimple(body, SCRIPT_SELF_CLOSING, "[удалён script]", "script", counts);
        body = replaceSimple(body, SCRIPT_UNCLOSED, "[удалён script]", "script-unclosed", counts);

        Matcher link = JAVASCRIPT_LINK.matcher(body);
        StringBuilder links = new StringBuilder();
        int linkCount = 0;
        while (link.find()) {
            linkCount++;
            link.appendReplacement(links, Matcher.quoteReplacement(link.group(1)));
        }
        link.appendTail(links);
        if (linkCount > 0) {
            body = links.toString();
            counts.put("javascript", linkCount);
        }

        return new SanitizedBody(body, warnings(counts));
    }

    private static String stripExecutableFences(String body, Map<String, Integer> counts) {
        List<Line> lines = splitLines(body);
        StringBuilder result = new StringBuilder(body.length());
        int index = 0;
        while (index < lines.size()) {
            Fence fence = openingFence(lines.get(index).text());
            if (fence == null) {
                append(result, lines.get(index));
                index++;
                continue;
            }
            int closing = findClosing(lines, index + 1, fence);
            if (!fence.executable()) {
                int end = closing < 0 ? lines.size() - 1 : closing;
                for (int current = index; current <= end; current++) {
                    append(result, lines.get(current));
                }
                index = end + 1;
                continue;
            }
            String language = fence.language().toLowerCase(Locale.ROOT);
            counts.merge("block:" + language, 1, Integer::sum);
            result.append("> [удалён исполняемый блок Obsidian: ").append(language).append(']');
            int blockEnd = closing < 0 ? lines.size() - 1 : closing;
            result.append(lines.get(blockEnd).ending());
            index = closing < 0 ? lines.size() : closing + 1;
        }
        return result.toString();
    }

    private static Fence openingFence(String line) {
        String effective = effectiveStart(line);
        if (effective.isEmpty()) {
            return null;
        }
        char marker = effective.charAt(0);
        if (marker != '`' && marker != '~') {
            return null;
        }
        int length = 0;
        while (length < effective.length() && effective.charAt(length) == marker) {
            length++;
        }
        if (length < 3) {
            return null;
        }
        String language = firstWord(effective.substring(length));
        boolean executable = EXECUTABLE_LANGUAGES.contains(language.toLowerCase(Locale.ROOT));
        return new Fence(marker, length, language, executable);
    }

    private static int findClosing(List<Line> lines, int from, Fence fence) {
        for (int index = from; index < lines.size(); index++) {
            if (isClosing(effectiveStart(lines.get(index).text()), fence)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isClosing(String effective, Fence fence) {
        if (effective.isEmpty() || effective.charAt(0) != fence.marker()) {
            return false;
        }
        int length = 0;
        while (length < effective.length() && effective.charAt(length) == fence.marker()) {
            length++;
        }
        return length >= fence.length();
    }

    private static String effectiveStart(String line) {
        int index = skipSpaces(line, 0, 3);
        while (index < line.length() && line.charAt(index) == '>') {
            index++;
            if (index < line.length() && line.charAt(index) == ' ') {
                index++;
            }
        }
        index = skipSpaces(line, index, 3);
        return line.substring(index);
    }

    private static int skipSpaces(String line, int from, int limit) {
        int index = from;
        int skipped = 0;
        while (index < line.length() && skipped < limit && line.charAt(index) == ' ') {
            index++;
            skipped++;
        }
        return index;
    }

    private static String firstWord(String rest) {
        int index = 0;
        while (index < rest.length() && (rest.charAt(index) == ' ' || rest.charAt(index) == '\t')) {
            index++;
        }
        int start = index;
        while (index < rest.length() && !Character.isWhitespace(rest.charAt(index))) {
            index++;
        }
        return rest.substring(start, index);
    }

    private static List<Line> splitLines(String body) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < body.length()) {
            if (body.charAt(index) == '\n') {
                int contentEnd = index;
                String ending = "\n";
                if (contentEnd > start && body.charAt(contentEnd - 1) == '\r') {
                    contentEnd--;
                    ending = "\r\n";
                }
                lines.add(new Line(body.substring(start, contentEnd), ending));
                start = index + 1;
            }
            index++;
        }
        if (start < body.length()) {
            lines.add(new Line(body.substring(start), ""));
        }
        return lines;
    }

    private static void append(StringBuilder result, Line line) {
        result.append(line.text()).append(line.ending());
    }

    private record Line(String text, String ending) {
    }

    private record Fence(char marker, int length, String language, boolean executable) {
    }

    private static String replaceSimple(
            String body, Pattern pattern, String replacement, String key, Map<String, Integer> counts) {
        Matcher matcher = pattern.matcher(body);
        StringBuilder result = new StringBuilder();
        int count = 0;
        while (matcher.find()) {
            count++;
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        if (count > 0) {
            counts.put(key, count);
            return result.toString();
        }
        return body;
    }

    private static List<String> warnings(Map<String, Integer> counts) {
        List<String> warnings = new ArrayList<>();
        counts.forEach((key, count) -> {
            if (key.startsWith("block:")) {
                warnings.add("удалён исполняемый блок: " + key.substring("block:".length())
                        + " (" + count + ")");
            } else if ("templater".equals(key)) {
                warnings.add("удалён шаблон Templater (" + count + ")");
            } else if ("script".equals(key)) {
                warnings.add("удалён script (" + count + ")");
            } else if ("script-unclosed".equals(key)) {
                warnings.add("удалён script (незакрытый)");
            } else if ("javascript".equals(key)) {
                warnings.add("удалена javascript-ссылка (" + count + ")");
            }
        });
        return List.copyOf(warnings);
    }
}
