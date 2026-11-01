package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentSanitizerTest {

    private final ContentSanitizer sanitizer = new ContentSanitizer();

    @Test
    void removesExecutableFencedBlocks() {
        String body = """
                ```dataview
                TABLE file.name FROM "notes"
                ```

                ```dataviewjs
                dv.list(dv.pages())
                ```

                ```templater
                <%* tp.file.title %>
                ```

                ```jsengine
                console.log(1)
                ```
                """;

        ContentSanitizer.SanitizedBody result = sanitizer.sanitize(body);

        assertThat(result.body()).doesNotContain(
                "TABLE file.name", "dv.list", "tp.file.title", "console.log");
        assertThat(result.body()).contains(
                "[удалён исполняемый блок Obsidian: dataview]",
                "[удалён исполняемый блок Obsidian: dataviewjs]",
                "[удалён исполняемый блок Obsidian: templater]",
                "[удалён исполняемый блок Obsidian: jsengine]");
        assertThat(result.warnings()).contains(
                "удалён исполняемый блок: dataview (1)",
                "удалён исполняемый блок: dataviewjs (1)");
    }

    @Test
    void aggregatesWarningsByType() {
        String body = """
                ```dataview
                first
                ```

                ```dataview
                second
                ```
                """;

        ContentSanitizer.SanitizedBody result = sanitizer.sanitize(body);

        assertThat(result.body()).doesNotContain("first", "second");
        assertThat(result.warnings()).contains("удалён исполняемый блок: dataview (2)");
    }

    @Test
    void removesTemplaterExpressionsIncludingMultiline() {
        ContentSanitizer.SanitizedBody result = sanitizer.sanitize(
                "До\n<% tp.date.now(\n  \"YYYY-MM-DD\") %>\nПосле");

        assertThat(result.body()).doesNotContain("<%", "tp.date.now");
        assertThat(result.body()).contains("До", "После", "[удалён шаблон Templater]");
        assertThat(result.warnings()).contains("удалён шаблон Templater (1)");
    }

    @Test
    void removesScriptTagsAndJavascriptLinks() {
        ContentSanitizer.SanitizedBody result = sanitizer.sanitize(
                "<script type=\"text/javascript\">alert('x')</script>\n"
                        + "[нажми](javascript:alert('x')) и [ещё](JavaScript:void(0))");

        assertThat(result.body()).doesNotContain("<script", "alert(", "javascript:", "JavaScript:");
        assertThat(result.body()).contains("[удалён script]", "нажми", "ещё");
        assertThat(result.warnings()).contains("удалён script (1)", "удалена javascript-ссылка (2)");
    }

    @Test
    void removesUppercaseDataviewFence() {
        ContentSanitizer.SanitizedBody result = sanitizer.sanitize("""
                ```DATAVIEW
                TABLE file.name FROM "notes"
                ```
                """);

        assertThat(result.body()).doesNotContain("TABLE file.name");
        assertThat(result.body()).contains("[удалён исполняемый блок Obsidian: dataview]");
        assertThat(result.warnings()).contains("удалён исполняемый блок: dataview (1)");
    }

    @Test
    void removesUnclosedExecutableFenceToEndOfBody() {
        ContentSanitizer.SanitizedBody result = sanitizer.sanitize("""
                До
                ```dataviewjs
                dv.list(dv.pages())
                хвост без закрытия""");

        assertThat(result.body()).doesNotContain("dv.list", "хвост без закрытия");
        assertThat(result.body()).contains("До", "[удалён исполняемый блок Obsidian: dataviewjs]");
        assertThat(result.warnings()).contains("удалён исполняемый блок: dataviewjs (1)");
    }

    @Test
    void removesExecutableFenceWithQuotePrefix() {
        ContentSanitizer.SanitizedBody result = sanitizer.sanitize("""
                > ```dataviewjs
                > dv.list(dv.pages())
                > ```
                """);

        assertThat(result.body()).doesNotContain("dv.list", "```dataviewjs");
        assertThat(result.body()).contains("[удалён исполняемый блок Obsidian: dataviewjs]");
        assertThat(result.warnings()).contains("удалён исполняемый блок: dataviewjs (1)");
    }

    @Test
    void removesFenceWhenClosingShorterThanOpening() {
        ContentSanitizer.SanitizedBody result = sanitizer.sanitize("""
                ````dataview
                TABLE file.name FROM "notes"
                ```
                всё ещё внутри блока
                """);

        assertThat(result.body()).doesNotContain("TABLE file.name", "всё ещё внутри блока");
        assertThat(result.body()).contains("[удалён исполняемый блок Obsidian: dataview]");
        assertThat(result.warnings()).contains("удалён исполняемый блок: dataview (1)");
    }

    @Test
    void keepsDataviewFenceInsideOrdinaryCodeBlock() {
        String body = """
                ```java
                ```dataview
                TABLE file.name FROM "notes"
                ```
                ```
                """;

        ContentSanitizer.SanitizedBody result = sanitizer.sanitize(body);

        assertThat(result.body()).isEqualTo(body);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void removesTemplaterStarExpressions() {
        ContentSanitizer.SanitizedBody result = sanitizer.sanitize(
                "До <%* tp.file.title %> После");

        assertThat(result.body()).doesNotContain("<%", "tp.file.title");
        assertThat(result.body()).contains("До", "После", "[удалён шаблон Templater]");
        assertThat(result.warnings()).contains("удалён шаблон Templater (1)");
    }

    @Test
    void removesSelfClosingScriptTag() {
        ContentSanitizer.SanitizedBody result = sanitizer.sanitize(
                "До <script src=\"plugin.js\" /> После");

        assertThat(result.body()).doesNotContain("<script", "plugin.js");
        assertThat(result.body()).contains("[удалён script]");
        assertThat(result.warnings()).contains("удалён script (1)");
    }

    @Test
    void removesUnclosedScriptToEndOfBody() {
        ContentSanitizer.SanitizedBody result = sanitizer.sanitize(
                "До\n<script type=\"text/javascript\">\nalert('x')\nи хвост");

        assertThat(result.body()).doesNotContain("<script", "alert(", "хвост");
        assertThat(result.body()).contains("До", "[удалён script]");
        assertThat(result.warnings()).contains("удалён script (незакрытый)");
    }

    @Test
    void keepsOrdinaryCodeLatexTablesAndInlineCode() {
        String body = """
                Обычный блок:

                ```java
                int x = 1;
                ```

                Inline `dataview` не исполняется.

                $$E = mc^2$$

                | a | b |
                |---|---|
                | 1 | 2 |
                """;

        ContentSanitizer.SanitizedBody result = sanitizer.sanitize(body);

        assertThat(result.body()).isEqualTo(body);
        assertThat(result.warnings()).isEmpty();
    }
}
