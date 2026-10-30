package uzelok.domain.model;

import java.util.List;

public record Extraction(
        String transcript,
        List<Thesis> theses,
        List<String> unclearSpans,
        double confidence) {
}
