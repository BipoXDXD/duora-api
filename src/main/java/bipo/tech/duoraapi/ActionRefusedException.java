package bipo.tech.duoraapi;

import java.util.Objects;

/**
 * Uma ação bem formada que a regra de negócio recusa no estado atual, com o motivo, para a API devolvê-lo
 * em {@code reason} (docs/adr/0020). Cada módulo estende esta classe com a própria exceção, e a camada web
 * traduz todas num ponto só, escolhendo o status pelo motivo. A mensagem vai ao cliente como detail: nunca
 * leva valor recebido nem dado de outra pessoa.
 */
public abstract class ActionRefusedException extends RuntimeException {

    private final RefusalReason reason;

    protected ActionRefusedException(RefusalReason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public RefusalReason reason() {
        return reason;
    }

}
