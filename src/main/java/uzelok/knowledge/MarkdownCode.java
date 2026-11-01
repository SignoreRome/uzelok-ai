package uzelok.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

public final class MarkdownCode {

    private static final Parser PARSER = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    private MarkdownCode() {
    }

    public record Range(int start, int end) {
    }

    public static List<Range> codeAndMathRanges(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return List.of();
        }
        int[] lineStarts = lineStarts(markdown);
        List<Range> ranges = new ArrayList<>();
        collectCode(PARSER.parse(markdown), lineStarts, ranges);
        collectMath(markdown, ranges);
        ranges.sort(Comparator.comparingInt(Range::start));
        return merge(ranges);
    }

    private static void collectCode(Node node, int[] lineStarts, List<Range> ranges) {
        while (node != null) {
            if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock
                    || node instanceof Code) {
                addRange(node, lineStarts, ranges);
            } else {
                Node child = node.getFirstChild();
                if (child != null) {
                    collectCode(child, lineStarts, ranges);
                }
            }
            node = node.getNext();
        }
    }

    private static void addRange(Node node, int[] lineStarts, List<Range> ranges) {
        int start = startOffset(node, lineStarts);
        int end = endOffset(node, lineStarts);
        if (start >= 0 && end > start) {
            ranges.add(new Range(start, end));
        }
    }

    private static void collectMath(String markdown, List<Range> ranges) {
        List<Integer> positions = new ArrayList<>();
        int index = 0;
        while ((index = markdown.indexOf("$$", index)) >= 0) {
            if (!inside(index, ranges)) {
                positions.add(index);
            }
            index += 2;
        }
        for (int i = 0; i + 1 < positions.size(); i += 2) {
            ranges.add(new Range(positions.get(i), positions.get(i + 1) + 2));
        }
    }

    private static boolean inside(int offset, List<Range> ranges) {
        for (Range range : ranges) {
            if (offset >= range.start() && offset < range.end()) {
                return true;
            }
        }
        return false;
    }

    private static List<Range> merge(List<Range> ranges) {
        if (ranges.isEmpty()) {
            return List.of();
        }
        List<Range> merged = new ArrayList<>();
        Range current = ranges.get(0);
        for (int i = 1; i < ranges.size(); i++) {
            Range next = ranges.get(i);
            if (next.start() <= current.end()) {
                current = new Range(current.start(), Math.max(current.end(), next.end()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return List.copyOf(merged);
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
}
