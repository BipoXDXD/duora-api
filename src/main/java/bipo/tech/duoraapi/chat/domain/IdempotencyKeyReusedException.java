package bipo.tech.duoraapi.chat.domain;

import bipo.tech.duoraapi.ActionRefusedException;
import bipo.tech.duoraapi.RefusalReason;

/**
 * A mesma Idempotency-Key já gravou uma mensagem com outro texto (docs/adr/0005): não é a repetição do
 * mesmo envio, e devolver a mensagem antiga esconderia do cliente que o texto novo não foi enviado.
 */
public class IdempotencyKeyReusedException extends ActionRefusedException {

    public IdempotencyKeyReusedException() {
        super(RefusalReason.IDEMPOTENCY_KEY_REUSED, "the idempotency key was already used with another text");
    }

}
