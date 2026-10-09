package bipo.tech.duoraapi.events.api;

/** Filtro de status inválido na lista do ADMIN. A mensagem vai ao cliente, sem o valor recebido. */
class InvalidPageRequestException extends RuntimeException {

    InvalidPageRequestException(String message) {
        super(message);
    }

}
