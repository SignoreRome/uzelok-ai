package uzelok.domain.model;

import java.util.List;

public record Extraction(
        String transcript,
        List<Thesis> theses,
        List<String> unclearSpans,
        double confidence) {

    public Extraction {
        theses = theses == null ? List.of() : List.copyOf(theses);
        unclearSpans = unclearSpans == null ? List.of() : List.copyOf(unclearSpans);
    }
}
