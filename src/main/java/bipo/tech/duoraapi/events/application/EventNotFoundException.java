package bipo.tech.duoraapi.events.application;

/** O evento não existe ou, para quem não é ADMIN, ainda é rascunho: as duas situações respondem igual. */
public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException() {
        super("event not found");
    }

}
