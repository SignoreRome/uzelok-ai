package uzelok.domain.model;

import java.util.UUID;

public record KnowledgeBase(
        UUID id,
        KnowledgeBaseKind kind,
        String rootPath,
        String taxonomyRelPath,
        String inboxRelPath) {
}
