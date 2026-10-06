package bipo.tech.duoraapi.profiles.domain;

/** Valor ou mudança de perfil fora das regras. A mensagem vai ao cliente: nunca leva o valor recebido. */
public class InvalidProfileException extends RuntimeException {

    public InvalidProfileException(String message) {
        super(message);
    }

}
