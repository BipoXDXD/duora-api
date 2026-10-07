package bipo.tech.duoraapi.trustsafety.domain;

import bipo.tech.duoraapi.FieldErrorCode;
import bipo.tech.duoraapi.InvalidFieldException;

/** Denúncia fora das regras. A mensagem vai ao cliente: nunca leva o texto recebido. */
public class InvalidReportException extends InvalidFieldException {

    public InvalidReportException(String field, FieldErrorCode code, String message) {
        super(field, code, message);
    }

}
