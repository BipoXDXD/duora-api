package bipo.tech.duoraapi.trustsafety.domain;

/**
 * A conta alvo de um bloqueio ou de uma denúncia não existe. A resposta é a mesma para qualquer id que
 * não seja de conta, sem dizer mais nada sobre ele.
 */
public class UnknownAccountException extends RuntimeException {

    public UnknownAccountException() {
        super("account not found");
    }

}
