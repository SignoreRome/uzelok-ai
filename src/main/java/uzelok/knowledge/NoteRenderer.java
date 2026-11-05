package uzelok.knowledge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.NoteType;
import uzelok.domain.model.RenderedNote;
import uzelok.domain.model.Taxonomy;
import uzelok.domain.model.TopicRef;

public final class NoteRenderer {

    private static final int SUMMARY_LIMIT = 150;
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]]+)]]");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?:/mnt/|/home/|/srv/|/Users/|agora/)");

    private final FileNamePolicy names;
    private final ContentSanitizer sanitizer;
    private final double confidenceThreshold;

    public NoteRenderer(
            FileNamePolicy names, ContentSanitizer sanitizer, double confidenceThreshold) {
        this.names = names;
        this.sanitizer = sanitizer;
        this.confidenceThreshold = confidenceThreshold;
    }

    public RenderedNote render(
            Taxonomy taxonomy,
            KnowledgeBase base,
            UUID noteId,
            String jobRef,
            NoteDraft draft,
            boolean forceInbox,
            LocalDate today,
            Set<String> existingNames) {
        if (draft.type() == null) {
            throw new ContractViolation("тип заметки не задан");
        }

        Set<String> warnings = new LinkedHashSet<>();
        ContentSanitizer.SanitizedBody sanitized = sanitizer.sanitize(draft.bodyMd());
        warnings.addAll(sanitized.warnings());
        rejectAbsolutePaths(sanitized.body());

        boolean free = taxonomy.isFree();
        String topicText = draft.topic() == null ? "" : draft.topic().trim();
        boolean inbox = !free && forceInbox;
        if (free && forceInbox) {
            warnings.add("внутренняя база без входящих, заметка останется в notes");
        }

        Optional<TopicRef> resolved = free ? Optional.empty() : taxonomy.resolve(draft.topic());
        if (!free && resolved.isEmpty()) {
            inbox = true;
            warnings.add("тема '" + topicText + "' не найдена в словаре, заметка уйдёт в _Inbox");
        }
        if (draft.confidence() < confidenceThreshold) {
            warnings.add("низкая уверенность модели (" + draft.confidence()
                    + " < " + confidenceThreshold + "), проверьте заметку");
        }
        if (draft.type() == NoteType.problem) {
            if (free) {
                warnings.add("тип problem требует problem_*, которых нет в черновике; "
                        + "заметка останется в notes");
            } else {
                inbox = true;
                warnings.add("тип problem требует problem_*, которых нет в черновике");
            }
        }

        String baseName = names.sanitize(draft.fileName(), draft.title());
        String name = baseName;
        if (existingNames.contains(baseName.toLowerCase(Locale.ROOT))) {
            name = names.withSuffix(baseName, collisionSuffix(jobRef, noteId));
            if (free) {
                warnings.add("имя занято, сохраню как " + name);
            } else {
                inbox = true;
                warnings.add("имя занято, сохраню во входящие как " + name);
            }
        }

        String body = normalizeBody(sanitized.body());
        body = resolveWikilinks(body, existingNames, warnings);

        String relPath;
        if (free) {
            String topicSlug = names.fromTitle(topicText.isEmpty() ? "Inbox" : topicText);
            relPath = join(PlainVault.NOTES_DIRECTORY, topicSlug, name + ".md");
        } else if (inbox) {
            relPath = join(base.inboxRelPath(), name + ".md");
        } else {
            TopicRef reference = resolved.orElseThrow();
            relPath = join(knowledgePrefix(base.taxonomyRelPath()),
                    reference.domainId(), reference.topicId(), name + ".md");
        }

        String frontmatter = frontmatter(noteId, draft, resolved.orElse(null), today, free,
                topicText);
        String markdown = "---\n" + frontmatter + "\n---\n\n" + body;
        String contentHash = sha256(markdown);

        return new RenderedNote(noteId, relPath, name + ".md", frontmatter, markdown, contentHash,
                List.copyOf(warnings), inbox);
    }

    private static String frontmatter(
            UUID noteId, NoteDraft draft, TopicRef topicReference, LocalDate today,
            boolean free, String topicText) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("type", draft.type().name());
        if (free) {
            values.put("topic", topicText.isEmpty() ? "Inbox" : topicText);
        } else if (topicReference != null) {
            values.put("domain", topicReference.domainId());
            values.put("topic", topicReference.topicId());
        }
        values.put("status", "raw");
        values.put("source", "telegram");
        values.put("id", noteId.toString());
        values.put("created", new IsoDate(today.toString()));
        values.put("updated", new IsoDate(today.toString()));
        values.put("summary", summary(draft.summary()));

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setSplitLines(false);
        options.setAllowUnicode(true);
        options.setWidth(Integer.MAX_VALUE);
        Yaml yaml = new Yaml(new IsoDateRepresenter(options), options);
        return yaml.dump(values).stripTrailing();
    }

    private record IsoDate(String value) {
    }

    private static final class IsoDateRepresenter extends Representer {

        private IsoDateRepresenter(DumperOptions options) {
            super(options);
            representers.put(IsoDate.class,
                    data -> representScalar(Tag.TIMESTAMP, ((IsoDate) data).value()));
        }
    }

    private static String summary(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ContractViolation("summary не заполнен");
        }
        String oneLine = raw.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("[ \\t]+", " ").trim();
        if (oneLine.length() <= SUMMARY_LIMIT) {
            return oneLine;
        }
        int cut = SUMMARY_LIMIT - 1;
        int boundary = oneLine.lastIndexOf(' ', cut);
        if (boundary > 0) {
            cut = boundary;
        }
        return oneLine.substring(0, cut).stripTrailing() + "…";
    }

    private static void rejectAbsolutePaths(String body) {
        List<MarkdownCode.Range> ranges = MarkdownCode.codeAndMathRanges(body);
        int position = 0;
        for (MarkdownCode.Range range : ranges) {
            rejectAbsolutePathIn(body, position, range.start());
            position = range.end();
        }
        rejectAbsolutePathIn(body, position, body.length());
    }

    private static void rejectAbsolutePathIn(String body, int from, int to) {
        Matcher matcher = ABSOLUTE_PATH.matcher(body.substring(from, to));
        if (matcher.find()) {
            throw new ContractViolation("в тексте заметки найден абсолютный путь: "
                    + matcher.group());
        }
    }

    private static String normalizeBody(String body) {
        String normalized = body.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder result = new StringBuilder(normalized.length() + 1);
        for (String line : normalized.split("\n", -1)) {
            result.append(line.stripTrailing()).append('\n');
        }
        int end = result.length();
        while (end > 0 && result.charAt(end - 1) == '\n') {
            end--;
        }
        result.setLength(end);
        return result.append('\n').toString();
    }

    private static String resolveWikilinks(
            String body, Set<String> existingNames, Set<String> warnings) {
        List<MarkdownCode.Range> ranges = MarkdownCode.codeAndMathRanges(body);
        StringBuilder result = new StringBuilder(body.length());
        int position = 0;
        for (MarkdownCode.Range range : ranges) {
            resolveWikilinksIn(body, position, range.start(), result, existingNames, warnings);
            result.append(body, range.start(), range.end());
            position = range.end();
        }
        resolveWikilinksIn(body, position, body.length(), result, existingNames, warnings);
        return result.toString();
    }

    private static void resolveWikilinksIn(
            String body,
            int from,
            int to,
            StringBuilder result,
            Set<String> existingNames,
            Set<String> warnings) {
        Matcher matcher = WIKILINK.matcher(body.substring(from, to));
        StringBuilder segment = new StringBuilder();
        while (matcher.find()) {
            String inner = matcher.group(1);
            int pipe = inner.indexOf('|');
            String target = pipe >= 0 ? inner.substring(0, pipe).trim() : inner.trim();
            String display = pipe >= 0 ? inner.substring(pipe + 1).trim() : target;
            String lookup = target;
            int hash = lookup.indexOf('#');
            if (hash >= 0) {
                lookup = lookup.substring(0, hash).trim();
            }
            if (lookup.toLowerCase(Locale.ROOT).endsWith(".md")) {
                lookup = lookup.substring(0, lookup.length() - 3);
            }
            if (existingNames.contains(lookup.toLowerCase(Locale.ROOT))) {
                matcher.appendReplacement(segment, Matcher.quoteReplacement(matcher.group()));
            } else {
                warnings.add("ссылка [[" + target + "]] вырезана: заметки нет");
                matcher.appendReplacement(segment,
                        Matcher.quoteReplacement(display.isEmpty() ? target : display));
            }
        }
        matcher.appendTail(segment);
        result.append(segment);
    }

    private static String collisionSuffix(String jobRef, UUID noteId) {
        String fromJob = cleanSuffix(firstEight(jobRef));
        if (!fromJob.isEmpty()) {
            return fromJob;
        }
        String fromId = cleanSuffix(firstEight(noteId == null ? null : noteId.toString()));
        return fromId.isEmpty() ? "note" : fromId;
    }

    private static String cleanSuffix(String value) {
        String replaced = value.replaceAll("[^A-Za-z0-9_]", "_");
        String collapsed = replaced.replaceAll("_+", "_");
        return collapsed.replaceAll("^_+|_+$", "");
    }

    private static String firstEight(String jobRef) {
        if (jobRef == null) {
            return "";
        }
        return jobRef.length() <= 8 ? jobRef : jobRef.substring(0, 8);
    }

    private static String knowledgePrefix(String taxonomyRelPath) {
        if (taxonomyRelPath == null || taxonomyRelPath.isBlank()) {
            return "";
        }
        Path metaFolder = Path.of(taxonomyRelPath).getParent();
        if (metaFolder == null) {
            return "";
        }
        Path knowledgeRoot = metaFolder.getParent();
        Path prefix = knowledgeRoot == null ? metaFolder : knowledgeRoot;
        return prefix.toString().replace('\\', '/');
    }

    private static String join(String first, String... rest) {
        StringBuilder result = new StringBuilder();
        appendSegment(result, first);
        for (String segment : rest) {
            appendSegment(result, segment);
        }
        return result.toString();
    }

    private static void appendSegment(StringBuilder result, String segment) {
        if (segment == null) {
            return;
        }
        String cleaned = segment.replace('\\', '/').replaceAll("^/+|/+$", "");
        if (cleaned.isEmpty()) {
            return;
        }
        if (!result.isEmpty()) {
            result.append('/');
        }
        result.append(cleaned);
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 недоступен", exception);
        }
    }
}
