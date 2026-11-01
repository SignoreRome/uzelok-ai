package uzelok.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.Taxonomy;

public final class TaxonomyLoader {

    public Taxonomy load(Path taxonomyFile) throws ContractViolation {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object root;
        try (InputStream input = Files.newInputStream(taxonomyFile)) {
            root = yaml.load(input);
        } catch (IOException | YAMLException exception) {
            throw new ContractViolation("не удалось прочитать словарь таксономии " + taxonomyFile
                    + ": " + exception.getMessage());
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new ContractViolation("словарь таксономии " + taxonomyFile + " должен быть YAML-объектом");
        }

        int contractVersion = requireVersion(map, taxonomyFile);
        try {
            List<Taxonomy.Domain> domains = readDomains(map.get("domains"), taxonomyFile);
            return new Taxonomy(contractVersion, domains);
        } catch (IllegalArgumentException exception) {
            throw new ContractViolation("некорректный словарь " + taxonomyFile
                    + ": " + exception.getMessage());
        }
    }

    private static int requireVersion(Map<?, ?> map, Path taxonomyFile) {
        if (!map.containsKey("contract_version")) {
            throw new ContractViolation("в словаре " + taxonomyFile + " отсутствует contract_version");
        }
        Object raw = map.get("contract_version");
        int version;
        if (raw instanceof Number number) {
            version = number.intValue();
        } else if (raw != null) {
            try {
                version = Integer.parseInt(raw.toString().trim());
            } catch (NumberFormatException exception) {
                throw new ContractViolation("в словаре " + taxonomyFile
                        + " некорректный contract_version: " + raw);
            }
        } else {
            throw new ContractViolation("в словаре " + taxonomyFile + " отсутствует contract_version");
        }
        if (version != Taxonomy.SUPPORTED_CONTRACT_VERSION) {
            throw new ContractViolation("неподдерживаемая версия контракта словаря: " + version
                    + " (поддерживается " + Taxonomy.SUPPORTED_CONTRACT_VERSION + ")");
        }
        return version;
    }

    private static List<Taxonomy.Domain> readDomains(Object rawDomains, Path taxonomyFile) {
        if (!(rawDomains instanceof List<?> domainList) || domainList.isEmpty()) {
            throw new ContractViolation("в словаре " + taxonomyFile
                    + " domains должен быть непустым списком");
        }
        List<Taxonomy.Domain> domains = new ArrayList<>();
        Set<String> domainIds = new HashSet<>();
        Map<String, String> topicOwners = new HashMap<>();

        for (Object rawDomain : domainList) {
            if (!(rawDomain instanceof Map<?, ?> domain)) {
                throw new ContractViolation("домен в словаре " + taxonomyFile + " должен быть объектом");
            }
            String domainId = requireId(domain.get("id"), "id домена", taxonomyFile);
            if (!domainIds.add(domainId)) {
                throw new ContractViolation("домен повторяется в словаре: " + domainId);
            }
            List<String> aliases = readAliases(domain.get("aliases"), "домена " + domainId, taxonomyFile);
            List<Taxonomy.Topic> topics = readTopics(
                    domain.get("topics"), domainId, topicOwners, taxonomyFile);
            domains.add(new Taxonomy.Domain(domainId, aliases, topics));
        }
        return List.copyOf(domains);
    }

    private static List<Taxonomy.Topic> readTopics(
            Object rawTopics, String domainId, Map<String, String> topicOwners, Path taxonomyFile) {
        if (rawTopics == null) {
            return List.of();
        }
        if (!(rawTopics instanceof List<?> topicList)) {
            throw new ContractViolation("topics домена " + domainId + " должен быть списком");
        }
        List<Taxonomy.Topic> topics = new ArrayList<>();
        Set<String> topicIds = new HashSet<>();
        for (Object rawTopic : topicList) {
            String topicId;
            List<String> aliases;
            if (rawTopic instanceof Map<?, ?> topic) {
                topicId = requireId(topic.get("id"), "id темы", taxonomyFile);
                aliases = readAliases(topic.get("aliases"), "темы " + topicId, taxonomyFile);
            } else if (rawTopic instanceof String text && !text.isBlank()) {
                topicId = text.trim();
                aliases = List.of();
            } else {
                throw new ContractViolation("тема домена " + domainId
                        + " должна быть объектом с id или строкой");
            }
            if (!topicIds.add(topicId)) {
                throw new ContractViolation("тема повторяется в домене " + domainId + ": " + topicId);
            }
            String owner = topicOwners.putIfAbsent(topicId, domainId);
            if (owner != null) {
                throw new ContractViolation("тема " + topicId + " объявлена в двух доменах: "
                        + owner + " и " + domainId);
            }
            topics.add(new Taxonomy.Topic(topicId, aliases));
        }
        return List.copyOf(topics);
    }

    private static List<String> readAliases(Object rawAliases, String subject, Path taxonomyFile) {
        if (rawAliases == null) {
            return List.of();
        }
        if (!(rawAliases instanceof List<?> aliasList)) {
            throw new ContractViolation("aliases " + subject + " в словаре " + taxonomyFile
                    + " должен быть списком");
        }
        List<String> aliases = new ArrayList<>();
        for (Object rawAlias : aliasList) {
            if (rawAlias != null) {
                aliases.add(rawAlias.toString());
            }
        }
        return List.copyOf(aliases);
    }

    private static String requireId(Object rawId, String subject, Path taxonomyFile) {
        if (rawId == null || rawId.toString().isBlank()) {
            throw new ContractViolation("в словаре " + taxonomyFile + " отсутствует " + subject);
        }
        return rawId.toString().trim();
    }
}
