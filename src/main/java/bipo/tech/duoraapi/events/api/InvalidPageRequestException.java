package bipo.tech.duoraapi.events.api;

/** pageSize ou pageToken inválido. A mensagem vai ao cliente, sem o valor recebido. */
class InvalidPageRequestException extends RuntimeException {

    InvalidPageRequestException(String message) {
        super(message);
    }

}
