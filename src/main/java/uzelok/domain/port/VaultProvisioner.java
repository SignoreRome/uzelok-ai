package uzelok.domain.port;

import java.nio.file.Path;
import uzelok.domain.exception.ContractViolation;

public interface VaultProvisioner {

    /** Создаёт внутреннюю базу в rootPath из шаблона, идемпотентно. Возвращает канонический корень. */
    Path create(Path rootPath) throws ContractViolation;
}
