package bipo.tech.duoraapi.events.domain;

import bipo.tech.duoraapi.ActionRefusedException;
import bipo.tech.duoraapi.RefusalReason;

/**
 * O estado atual do evento não permite a ação pedida: publicar, cancelar, inscrever-se ou sair. A
 * mensagem e o motivo vão ao cliente (docs/adr/0020).
 */
public class EventStateConflictException extends ActionRefusedException {

    public EventStateConflictException(RefusalReason reason, String message) {
        super(reason, message);
    }

}
