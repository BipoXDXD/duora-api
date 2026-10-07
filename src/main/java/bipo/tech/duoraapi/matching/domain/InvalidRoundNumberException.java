package bipo.tech.duoraapi.matching.domain;

/** Número de rodada fora do intervalo aceito; a mensagem nunca traz o valor recebido. */
public class InvalidRoundNumberException extends RuntimeException {

    public InvalidRoundNumberException(String message) {
        super(message);
    }

}
