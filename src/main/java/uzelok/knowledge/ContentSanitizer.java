package uzelok.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

public final class ContentSanitizer {

    private static final Set<String> EXECUTABLE_LANGUAGES = Set.of(
            "dataview", "dataviewjs", "templater", "jsengine", "js-engine", "js-engine-debug");
    private static final Pattern TEMPLATER = Pattern.compile("(?s)<%[-+*]?.*?%>");
    private static final Pattern HREF_SRC = Pattern.compile(
            "(?i)\\b(?:href|src)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final Pattern OPEN_TAG = Pattern.compile("(?i)^<\\s*([a-zA-Z][a-zA-Z0-9-]*)");

    private static final Parser PARSER = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    public record SanitizedBody(String body, List<String> warnings) {

        public SanitizedBody {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public SanitizedBody sanitize(String bodyMd) {
        String body = bodyMd == null ? "" : bodyMd;
        Map<String, Integer> counts = new LinkedHashMap<>();

        Node document = PARSER.parse(body);
        int[] lineStarts = lineStarts(body);
        List<Edit> edits = new ArrayList<>();
        collect(document, lineStarts, edits, counts);
        body = applyEdits(body, edits);

        body = stripTemplater(body, counts);

        return new SanitizedBody(body, warnings(counts));
    }

    private static void collect(
            Node node, int[] lineStarts, List<Edit> edits, Map<String, Integer> counts) {
        while (node != null) {
            if (node instanceof FencedCodeBlock block) {
                String language = firstWord(block.getInfo()).toLowerCase(Locale.ROOT);
                if (EXECUTABLE_LANGUAGES.contains(language)) {
                    counts.merge("block:" + language, 1, Integer::sum);
                    addEdit(edits, block, lineStarts,
                            "> [удалён исполняемый блок Obsidian: " + language + "]");
                }
            } else if (node instanceof Code code) {
                String literal = code.getLiteral() == null ? "" : code.getLiteral().strip();
                if (literal.startsWith("=") || literal.startsWith("$=")) {
                    counts.merge("inline", 1, Integer::sum);
                    addEdit(edits, code, lineStarts, "");
                }
            } else if (node instanceof HtmlBlock block) {
                collectHtmlBlock(block, lineStarts, edits, counts);
            } else if (node instanceof HtmlInline inline) {
                collectHtmlInline(inline, lineStarts, edits, counts);
            } else if (node instanceof Link link) {
                String destination = link.getDestination();
                if (dangerous(destination)) {
                    counts.merge("javascript", 1, Integer::sum);
                    String label = textContent(link);
                    addEdit(edits, link, lineStarts, dangerous(label) ? "" : label);
                } else {
                    collect(link.getFirstChild(), lineStarts, edits, counts);
                }
            } else if (node instanceof Image image) {
                if (dangerous(image.getDestination())) {
                    counts.merge("javascript", 1, Integer::sum);
                    addEdit(edits, image, lineStarts, "");
                } else {
                    collect(image.getFirstChild(), lineStarts, edits, counts);
                }
            } else if (node instanceof LinkReferenceDefinition reference) {
                if (dangerous(reference.getDestination())) {
                    counts.merge("javascript", 1, Integer::sum);
                    addEdit(edits, reference, lineStarts, "");
                }
            } else {
                Node child = node.getFirstChild();
                if (child != null) {
                    collect(child, lineStarts, edits, counts);
                }
            }
            node = node.getNext();
        }
    }

    private static void collectHtmlBlock(
            HtmlBlock block, int[] lineStarts, List<Edit> edits, Map<String, Integer> counts) {
        String literal = block.getLiteral();
        if (literal == null) {
            return;
        }
        String lower = literal.toLowerCase(Locale.ROOT);
        if (lower.contains("<script")) {
            String key = lower.contains("</script") ? "script" : "script-unclosed";
            counts.merge(key, 1, Integer::sum);
            addEdit(edits, block, lineStarts, "[удалён script]");
        } else if (dangerousAttribute(literal)) {
            counts.merge("javascript", 1, Integer::sum);
            addEdit(edits, block, lineStarts, "");
        }
    }

    private static void collectHtmlInline(
            HtmlInline inline, int[] lineStarts, List<Edit> edits, Map<String, Integer> counts) {
        String literal = inline.getLiteral();
        if (literal == null) {
            return;
        }
        String lower = literal.toLowerCase(Locale.ROOT);
        if (lower.contains("<script")) {
            counts.merge("script", 1, Integer::sum);
            addEdit(edits, inline, lineStarts, "[удалён script]");
        } else if (dangerousAttribute(literal)) {
            counts.merge("javascript", 1, Integer::sum);
            addEdit(edits, inline, lineStarts, "");
            removeMatchingClosing(inline, lineStarts, edits);
        }
    }

    private static void removeMatchingClosing(
            HtmlInline opening, int[] lineStarts, List<Edit> edits) {
        String name = tagName(opening.getLiteral());
        if (name.isEmpty()) {
            return;
        }
        Node sibling = opening.getNext();
        while (sibling != null) {
            if (sibling instanceof HtmlInline inline && isClosingTag(inline.getLiteral(), name)) {
                addEdit(edits, sibling, lineStarts, "");
                return;
            }
            sibling = sibling.getNext();
        }
    }

    private static boolean dangerous(String destination) {
        if (destination == null) {
            return false;
        }
        String value = destination.strip().toLowerCase(Locale.ROOT);
        return value.startsWith("javascript:") || value.startsWith("vbscript:");
    }

    private static boolean dangerousAttribute(String literal) {
        Matcher matcher = HREF_SRC.matcher(literal);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value.length() >= 2 && (value.startsWith("\"") || value.startsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            String normalized = value.strip().toLowerCase(Locale.ROOT);
            normalized = normalized.replace("&#58;", ":").replace("&#x3a;", ":");
            if (normalized.startsWith("javascript:") || normalized.startsWith("vbscript:")) {
                return true;
            }
        }
        return false;
    }

    private static String tagName(String literal) {
        if (literal == null) {
            return "";
        }
        Matcher matcher = OPEN_TAG.matcher(literal);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean isClosingTag(String literal, String name) {
        if (literal == null) {
            return false;
        }
        String trimmed = literal.strip().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("</" + name.toLowerCase(Locale.ROOT)) && trimmed.endsWith(">");
    }

    private static String stripTemplater(String body, Map<String, Integer> counts) {
        List<MarkdownCode.Range> codeRanges = MarkdownCode.codeAndMathRanges(body);
        Matcher matcher = TEMPLATER.matcher(body);
        List<Edit> edits = new ArrayList<>();
        int count = 0;
        while (matcher.find()) {
            if (overlaps(matcher.start(), matcher.end(), codeRanges)) {
                continue;
            }
            edits.add(new Edit(matcher.start(), matcher.end(), "[удалён шаблон Templater]"));
            count++;
        }
        if (count == 0) {
            return body;
        }
        counts.put("templater", count);
        return applyEdits(body, edits);
    }

    private static boolean overlaps(int start, int end, List<MarkdownCode.Range> ranges) {
        for (MarkdownCode.Range range : ranges) {
            if (start < range.end() && end > range.start()) {
                return true;
            }
        }
        return false;
    }

    private static void addEdit(List<Edit> edits, Node node, int[] lineStarts, String replacement) {
        int start = startOffset(node, lineStarts);
        int end = endOffset(node, lineStarts);
        if (start >= 0 && end > start) {
            edits.add(new Edit(start, end, replacement));
        }
    }

    private static String applyEdits(String body, List<Edit> edits) {
        if (edits.isEmpty()) {
            return body;
        }
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        String result = body;
        for (Edit edit : edits) {
            result = result.substring(0, edit.start()) + edit.replacement()
                    + result.substring(edit.end());
        }
        return result;
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                starts.add(index + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int startOffset(Node node, int[] lineStarts) {
        int min = -1;
        for (SourceSpan span : node.getSourceSpans()) {
            int offset = offsetOf(span, lineStarts);
            if (min < 0 || offset < min) {
                min = offset;
            }
        }
        return min;
    }

    private static int endOffset(Node node, int[] lineStarts) {
        int max = -1;
        for (SourceSpan span : node.getSourceSpans()) {
            int offset = offsetOf(span, lineStarts) + span.getLength();
            if (offset > max) {
                max = offset;
            }
        }
        return max;
    }

    private static int offsetOf(SourceSpan span, int[] lineStarts) {
        int line = span.getLineIndex();
        if (line < 0 || line >= lineStarts.length) {
            return 0;
        }
        return lineStarts[line] + span.getColumnIndex();
    }

    private static String firstWord(String info) {
        if (info == null) {
            return "";
        }
        String trimmed = info.strip();
        int index = 0;
        while (index < trimmed.length() && !Character.isWhitespace(trimmed.charAt(index))) {
            index++;
        }
        return trimmed.substring(0, index);
    }

    private static String textContent(Node node) {
        StringBuilder result = new StringBuilder();
        appendText(node.getFirstChild(), result);
        return result.toString();
    }

    private static void appendText(Node node, StringBuilder result) {
        while (node != null) {
            if (node instanceof Text text) {
                result.append(text.getLiteral());
            } else if (node instanceof Code code) {
                result.append(code.getLiteral());
            } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
                result.append('\n');
            } else {
                Node child = node.getFirstChild();
                if (child != null) {
                    appendText(child, result);
                }
            }
            node = node.getNext();
        }
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
            } else if ("inline".equals(key)) {
                warnings.add("удалён inline-запрос Dataview (" + count + ")");
            }
        });
        return List.copyOf(warnings);
    }

    private record Edit(int start, int end, String replacement) {
    }
}
