package bipo.tech.duoraapi.events.domain;

/** Dado de evento fora das regras. A mensagem vai ao cliente. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

}
