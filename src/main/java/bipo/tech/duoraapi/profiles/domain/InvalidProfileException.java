package bipo.tech.duoraapi.profiles.domain;

import bipo.tech.duoraapi.FieldErrorCode;
import bipo.tech.duoraapi.InvalidFieldException;

/** Valor ou mudança de perfil fora das regras. A mensagem vai ao cliente: nunca leva o valor recebido. */
public class InvalidProfileException extends InvalidFieldException {

    public InvalidProfileException(String field, FieldErrorCode code, String message) {
        super(field, code, message);
    }

}
