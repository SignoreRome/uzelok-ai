package uzelok.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialHashTest {

    @Test
    void sameTextWithDifferentLineBreaksAndSpacesHasSameHash() {
        String first = "первая строка\r\nвторая  строка\rтретья";
        String second = "первая строка\nвторая строка\nтретья";

        assertThat(MaterialHash.normalizeText(first))
                .isEqualTo(MaterialHash.normalizeText(second));
        assertThat(MaterialHash.ofHashes(List.of("sha-1"), first))
                .isEqualTo(MaterialHash.ofHashes(List.of("sha-1"), second));
    }

    @Test
    void nullAndEmptyTextAreEquivalent() {
        assertThat(MaterialHash.normalizeText(null)).isEmpty();
        assertThat(MaterialHash.normalizeText("")).isEmpty();
        assertThat(MaterialHash.ofHashes(List.of(), null))
                .isEqualTo(MaterialHash.ofHashes(List.of(), ""));
    }
}
