package uzelok.config;

import uzelok.domain.model.ActionOutcome;
import uzelok.domain.model.PendingAction;
import uzelok.domain.port.ActionHandler;
import uzelok.pipeline.ActionService;

public final class ActionDispatcher implements ActionHandler {

    private final ActionService service;

    public ActionDispatcher(ActionService service) {
        this.service = service;
    }

    @Override
    public ActionOutcome handle(PendingAction action) {
        return service.handle(action);
    }
}
