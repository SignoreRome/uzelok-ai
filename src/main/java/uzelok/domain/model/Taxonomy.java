package uzelok.domain.model;

import java.util.List;
import java.util.Map;

public record Taxonomy(Map<String, List<String>> domains) {

    public Taxonomy {
        domains = domains == null ? Map.of() : Map.copyOf(domains);
    }
}
