package bipo.tech.duoraapi.matching.application;

/** Não há evento com esse id. */
public class UnknownEventException extends RuntimeException {

    public UnknownEventException() {
        super("event not found");
    }

}
