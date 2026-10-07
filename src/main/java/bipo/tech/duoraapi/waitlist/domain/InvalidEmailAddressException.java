package bipo.tech.duoraapi.waitlist.domain;

import bipo.tech.duoraapi.FieldErrorCode;
import bipo.tech.duoraapi.InvalidFieldException;

/** E-mail fora das regras. A mensagem vai ao cliente: nunca leva o endereço recebido. */
public class InvalidEmailAddressException extends InvalidFieldException {

    public InvalidEmailAddressException(String field, FieldErrorCode code, String message) {
        super(field, code, message);
    }

}
