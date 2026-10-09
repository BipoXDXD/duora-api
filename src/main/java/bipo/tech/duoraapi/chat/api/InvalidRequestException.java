package bipo.tech.duoraapi.chat.api;

/** Número de rodada, posição, afterSeq ou maxPageSize inválido. A mensagem vai ao cliente, sem o valor recebido. */
class InvalidRequestException extends RuntimeException {

    InvalidRequestException(String message) {
        super(message);
    }

}
