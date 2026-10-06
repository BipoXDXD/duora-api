package bipo.tech.duoraapi.events.domain;

/**
 * O estado atual do evento não permite a ação pedida: publicar, cancelar, inscrever-se ou sair. A
 * mensagem vai ao cliente.
 */
public class EventStateConflictException extends RuntimeException {

    public EventStateConflictException(String message) {
        super(message);
    }

}
