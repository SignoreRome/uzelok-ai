package uzelok.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public record Taxonomy(int contractVersion, List<Domain> domains) {

    public static final int SUPPORTED_CONTRACT_VERSION = 2;

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_]+");

    public record Domain(String id, List<String> aliases, List<Topic> topics) {

        public Domain {
            requireId(id, "домена");
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            topics = topics == null ? List.of() : List.copyOf(topics);
        }
    }

    public record Topic(String id, List<String> aliases) {

        public Topic {
            requireId(id, "темы");
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    public Taxonomy {
        domains = domains == null ? List.of() : List.copyOf(domains);
    }

    public static Taxonomy free() {
        return new Taxonomy(0, List.of());
    }

    public boolean isFree() {
        return domains.isEmpty();
    }

    public List<String> domainIds() {
        return domains.stream().map(Domain::id).toList();
    }

    public List<String> topicIds() {
        List<String> ids = new ArrayList<>();
        for (Domain domain : domains) {
            for (Topic topic : domain.topics()) {
                ids.add(topic.id());
            }
        }
        return List.copyOf(ids);
    }

    public Optional<TopicRef> resolve(String topicOrAlias) {
        String value = normalize(topicOrAlias);
        if (value == null) {
            return Optional.empty();
        }
        for (Domain domain : domains) {
            for (Topic topic : domain.topics()) {
                if (topic.id().equalsIgnoreCase(value)) {
                    return Optional.of(new TopicRef(domain.id(), topic.id()));
                }
            }
        }
        for (Domain domain : domains) {
            for (Topic topic : domain.topics()) {
                if (containsIgnoreCase(topic.aliases(), value)) {
                    return Optional.of(new TopicRef(domain.id(), topic.id()));
                }
            }
        }
        return Optional.empty();
    }

    public boolean hasTopic(String domainId, String topicId) {
        for (Domain domain : domains) {
            if (domain.id().equals(domainId)) {
                return domain.topics().stream().anyMatch(topic -> topic.id().equals(topicId));
            }
        }
        return false;
    }

    public Optional<TopicRef> resolveIn(String domainId, String topicOrAlias) {
        String domainValue = normalize(domainId);
        String value = normalize(topicOrAlias);
        if (domainValue == null || value == null) {
            return Optional.empty();
        }
        for (Domain domain : domains) {
            if (!domain.id().equalsIgnoreCase(domainValue)) {
                continue;
            }
            for (Topic topic : domain.topics()) {
                if (topic.id().equalsIgnoreCase(value)) {
                    return Optional.of(new TopicRef(domain.id(), topic.id()));
                }
            }
            for (Topic topic : domain.topics()) {
                if (containsIgnoreCase(topic.aliases(), value)) {
                    return Optional.of(new TopicRef(domain.id(), topic.id()));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        return values.stream().anyMatch(candidate -> candidate != null && candidate.equalsIgnoreCase(value));
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void requireId(String id, String subject) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "id " + subject + " должен соответствовать [A-Za-z0-9_]+: " + id);
        }
    }
}
