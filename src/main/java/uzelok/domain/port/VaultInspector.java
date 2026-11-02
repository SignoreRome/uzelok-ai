package uzelok.domain.port;

import java.nio.file.Path;
import java.util.List;

public interface VaultInspector {

    /** Пусто — база валидна; иначе человекочитаемые нарушения контракта. */
    List<String> violations(Path rootPath);
}
