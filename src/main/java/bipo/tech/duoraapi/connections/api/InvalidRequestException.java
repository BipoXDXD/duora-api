package bipo.tech.duoraapi.connections.api;

/** Número de rodada, maxPageSize ou pageToken inválido. A mensagem vai ao cliente, sem o valor recebido. */
class InvalidRequestException extends RuntimeException {

    InvalidRequestException(String message) {
        super(message);
    }

}
