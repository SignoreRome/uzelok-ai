package uzelok.support;

import java.util.ArrayList;
import java.util.List;
import uzelok.domain.model.PreviewView;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.port.JobNotifier;

public final class RecordingJobNotifier implements JobNotifier {

    public record Failure(ProcessingJob job, String userMessage) {
    }

    private final List<PreviewView> previews = new ArrayList<>();
    private final List<String> saved = new ArrayList<>();
    private final List<Failure> failures = new ArrayList<>();

    @Override
    public synchronized void preview(PreviewView view) {
        previews.add(view);
    }

    @Override
    public synchronized void saved(ProcessingJob job, String relPath) {
        saved.add(relPath);
    }

    @Override
    public synchronized void failed(ProcessingJob job, String userMessage) {
        failures.add(new Failure(job, userMessage));
    }

    public synchronized List<PreviewView> previews() {
        return List.copyOf(previews);
    }

    public synchronized List<String> saved() {
        return List.copyOf(saved);
    }

    public synchronized List<Failure> failures() {
        return List.copyOf(failures);
    }
}
