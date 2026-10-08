package bipo.tech.duoraapi.chat.domain;

import bipo.tech.duoraapi.FieldErrorCode;
import bipo.tech.duoraapi.InvalidFieldException;

/** Texto de mensagem fora das regras. A mensagem vai ao cliente: nunca leva o texto recebido. */
public class InvalidChatMessageException extends InvalidFieldException {

    public InvalidChatMessageException(String field, FieldErrorCode code, String message) {
        super(field, code, message);
    }

}
