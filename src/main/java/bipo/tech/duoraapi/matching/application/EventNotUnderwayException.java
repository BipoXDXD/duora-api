package bipo.tech.duoraapi.matching.application;

/** Rodadas só começam com o evento publicado e dentro do horário. */
public class EventNotUnderwayException extends RuntimeException {

    public EventNotUnderwayException() {
        super("rounds start only while the event is published and underway");
    }

}
