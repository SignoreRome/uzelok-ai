package uzelok.domain.port;

import uzelok.domain.model.SourceItem;

public record SourceItemCreation(SourceItem item, boolean created) {
}
