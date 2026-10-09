package bipo.tech.duoraapi.config;

/**
 * maxPageSize ou pageToken de uma lista paginada fora do contrato (docs/adr/0005). Vira 400 no
 * {@link MalformedRequestInputHandler}; a mensagem vai ao cliente e nunca traz o valor recebido.
 */
public class InvalidPageParameterException extends RuntimeException {

    InvalidPageParameterException(String message) {
        super(message);
    }

}
