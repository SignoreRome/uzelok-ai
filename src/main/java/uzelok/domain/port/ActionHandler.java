package uzelok.domain.port;

import uzelok.domain.model.ActionOutcome;
import uzelok.domain.model.PendingAction;

public interface ActionHandler {

    ActionOutcome handle(PendingAction action);
}
