package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FileNamePolicyTest {

    private final FileNamePolicy policy = new FileNamePolicy(60);

    @Test
    void transliteratesAndTitleCasesCyrillicTitle() {
        assertThat(policy.fromTitle("Два указателя")).isEqualTo("Dva_Ukazatelya");
        assertThat(policy.fromTitle("ЖУРНАЛ")).isEqualTo("Zhurnal");
        assertThat(policy.fromTitle("Ж")).isEqualTo("Zh");
    }

    @Test
    void keepsAcronymsAndInternalCase() {
        assertThat(policy.fromTitle("HTTP/2 и TLS")).isEqualTo("HTTP_2_I_TLS");
        assertThat(policy.fromTitle("3Sum")).isEqualTo("3Sum");
        assertThat(policy.fromTitle("iOS")).isEqualTo("IOS");
    }

    @Test
    void fallsBackToNoteForEmptyTitle() {
        assertThat(policy.fromTitle("")).isEqualTo("Note");
        assertThat(policy.fromTitle("!!! ---")).isEqualTo("Note");
        assertThat(policy.fromTitle(null)).isEqualTo("Note");
    }

    @Test
    void sanitizesEnglishCandidateIntoTitleCase() {
        assertThat(policy.sanitize("Two Pointers", "Метод двух указателей"))
                .isEqualTo("Two_Pointers");
        assertThat(policy.sanitize("  two-pointers.v2  ", "fallback"))
                .isEqualTo("Two_Pointers_V2");
    }

    @Test
    void sanitizeFallsBackToTransliteratedTitleForCyrillic() {
        assertThat(policy.sanitize("Метод двух указателей", "Метод двух указателей"))
                .isEqualTo("Metod_Dvukh_Ukazateley");
    }

    @Test
    void sanitizeStripsKnownMarkdownExtension() {
        assertThat(policy.sanitize("Two_Pointers.md", "fallback")).isEqualTo("Two_Pointers");
        assertThat(policy.sanitize("Two_Pointers.MarkDown", "fallback"))
                .isEqualTo("Two_Pointers");
    }

    @Test
    void sanitizeFallsBackForMixedAlphabet() {
        assertThat(policy.sanitize("Метод two pointers", "Метод двух указателей"))
                .isEqualTo("Metod_Dvukh_Ukazateley");
    }

    @Test
    void sanitizeFallsBackForEmptyOrSpecialOnlyCandidate() {
        assertThat(policy.sanitize("", "Два указателя")).isEqualTo("Dva_Ukazatelya");
        assertThat(policy.sanitize(null, "Два указателя")).isEqualTo("Dva_Ukazatelya");
        assertThat(policy.sanitize("!!! ---", "Два указателя")).isEqualTo("Dva_Ukazatelya");
    }

    @Test
    void sanitizeFallsBackWhenCandidateExceedsLimit() {
        assertThat(policy.sanitize("Word_".repeat(20), "Two Pointers"))
                .isEqualTo("Two_Pointers");
    }

    @Test
    void sanitizeTruncatesLongFallbackWithoutDanglingUnderscore() {
        String name = policy.sanitize("Word_".repeat(20),
                "Очень длинное название темы ".repeat(10));

        assertThat(name.length()).isLessThanOrEqualTo(60);
        assertThat(name).doesNotEndWith("_");
        assertThat(policy.isValid(name)).isTrue();
    }

    @Test
    void truncatesLongNameToLimit() {
        String name = policy.fromTitle("Очень длинное название темы ".repeat(10));

        assertThat(name.length()).isLessThanOrEqualTo(60);
        assertThat(policy.isValid(name)).isTrue();
    }

    @Test
    void suffixFitsWithoutCuttingSuffix() {
        assertThat(policy.withSuffix("Short", "abcdef12")).isEqualTo("Short_abcdef12");

        String longName = policy.withSuffix("A".repeat(60), "abcdef12");

        assertThat(longName).endsWith("_abcdef12");
        assertThat(longName.length()).isLessThanOrEqualTo(60);
        assertThat(policy.isValid(longName)).isTrue();
    }

    @Test
    void truncatesSuffixLongerThanLimit() {
        String name = policy.withSuffix("Short", "a".repeat(80));

        assertThat(name.length()).isLessThanOrEqualTo(60);
        assertThat(name).isEqualTo("a".repeat(60));
    }

    @Test
    void suffixKeepsUnderscoreBoundaryWhenPossible() {
        String name = policy.withSuffix("Very_Long_Base_Name_For_The_Note", "12345678");

        assertThat(name).endsWith("_12345678");
        assertThat(name.length()).isLessThanOrEqualTo(60);
    }

    @Test
    void validatesNamesStrictly() {
        assertThat(policy.isValid("Dva_Ukazatelya")).isTrue();
        assertThat(policy.isValid("3Sum")).isTrue();
        assertThat(policy.isValid("dva")).isFalse();
        assertThat(policy.isValid("Dva Ukazatelya")).isFalse();
        assertThat(policy.isValid("Dva-Ukazatelya")).isFalse();
        assertThat(policy.isValid("Два")).isFalse();
        assertThat(policy.isValid("A".repeat(61))).isFalse();
        assertThat(policy.isValid(null)).isFalse();
    }
}
