package bipo.tech.duoraapi.events.domain;

import bipo.tech.duoraapi.FieldErrorCode;
import bipo.tech.duoraapi.InvalidFieldException;

/** Dado de evento fora das regras. A mensagem vai ao cliente: nunca leva o valor recebido. */
public class InvalidEventException extends InvalidFieldException {

    public InvalidEventException(String field, FieldErrorCode code, String message) {
        super(field, code, message);
    }

}
