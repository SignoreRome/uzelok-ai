package uzelok.domain.model;

import java.util.List;

public record InlineKeyboard(List<List<InlineButton>> rows) {

    public InlineKeyboard {
        rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList();
    }

    public static InlineKeyboard empty() {
        return new InlineKeyboard(List.of());
    }
}
