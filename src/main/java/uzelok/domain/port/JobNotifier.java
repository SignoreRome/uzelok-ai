package uzelok.domain.port;

import uzelok.domain.model.PreviewView;
import uzelok.domain.model.ProcessingJob;

public interface JobNotifier {

    void preview(PreviewView view);

    default void saved(ProcessingJob job, String relPath) {
    }

    void failed(ProcessingJob job, String userMessage);
}
