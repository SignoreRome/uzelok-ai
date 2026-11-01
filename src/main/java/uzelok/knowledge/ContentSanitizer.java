package uzelok.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ContentSanitizer {

    private static final Pattern EXECUTABLE_FENCE = Pattern.compile(
            "(?ism)^[ \\t]{0,3}(```+|~~~+)[ \\t]*(dataviewjs|dataview|templater|jsengine)\\b"
                    + "[^\\n]*\\n.*?^[ \\t]{0,3}\\1[ \\t]*$");
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

        Matcher fence = EXECUTABLE_FENCE.matcher(body);
        StringBuilder fenced = new StringBuilder();
        while (fence.find()) {
            String language = fence.group(2);
            counts.merge("block:" + language, 1, Integer::sum);
            fence.appendReplacement(fenced, Matcher.quoteReplacement(
                    "> [удалён исполняемый блок Obsidian: " + language + "]"));
        }
        fence.appendTail(fenced);
        body = fenced.toString();

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
