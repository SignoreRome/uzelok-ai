package uzelok.domain.port;

import uzelok.domain.model.PreviewView;
import uzelok.domain.model.ProcessingJob;

public interface JobNotifier {

    void preview(PreviewView view);

    void failed(ProcessingJob job, String userMessage);
}
