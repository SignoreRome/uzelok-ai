package uzelok.domain.port;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import uzelok.domain.model.Extraction;
import uzelok.domain.model.LlmContext;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.Taxonomy;

public interface LlmGateway {

    Extraction extract(LlmContext context, List<Path> images, String text);

    NoteDraft draft(LlmContext context, Extraction extraction, Taxonomy taxonomy,
                    Set<String> existingNames);
}
