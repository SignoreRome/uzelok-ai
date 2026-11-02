package uzelok.support;

import java.util.UUID;
import uzelok.domain.model.OnboardingResult;
import uzelok.domain.port.OnboardingHandler;

public final class StubOnboardingHandler implements OnboardingHandler {

    private boolean awaiting;
    private OnboardingResult createResult = new OnboardingResult("base.created", java.util.List.of());
    private OnboardingResult connectResult = new OnboardingResult("base.connected", java.util.List.of());

    public StubOnboardingHandler createResult(OnboardingResult result) {
        this.createResult = result;
        return this;
    }

    public StubOnboardingHandler connectResult(OnboardingResult result) {
        this.connectResult = result;
        return this;
    }

    @Override
    public boolean awaitingPath(long chatId) {
        return awaiting;
    }

    @Override
    public OnboardingResult expectPath(long chatId, UUID tenantId) {
        awaiting = true;
        return new OnboardingResult("base.ask_path", java.util.List.of());
    }

    @Override
    public OnboardingResult createInternal(long chatId, UUID tenantId) {
        awaiting = false;
        return createResult;
    }

    @Override
    public OnboardingResult connect(long chatId, UUID tenantId, String pathText) {
        awaiting = false;
        return connectResult;
    }

    @Override
    public void reset(long chatId) {
        awaiting = false;
    }
}
