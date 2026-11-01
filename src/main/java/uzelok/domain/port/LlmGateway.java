package uzelok.domain.port;

import java.nio.file.Path;
import java.util.List;
import uzelok.domain.model.Extraction;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.Taxonomy;

public interface LlmGateway {

    Extraction extract(List<Path> images, String text, String modelId, String promptVersion);

    NoteDraft draft(Extraction extraction, Taxonomy taxonomy, List<String> existingNames,
                    String modelId, String promptVersion);
}
