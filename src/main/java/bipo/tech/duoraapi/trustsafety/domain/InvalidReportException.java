package bipo.tech.duoraapi.trustsafety.domain;

/** Denúncia fora das regras. A mensagem vai ao cliente: nunca leva o texto recebido. */
public class InvalidReportException extends RuntimeException {

    public InvalidReportException(String message) {
        super(message);
    }

}
