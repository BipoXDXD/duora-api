package bipo.tech.duoraapi.events.application;

/** Só quem tem o perfil completo (nome, região e 18 anos completos) se inscreve em eventos. */
public class IncompleteProfileException extends RuntimeException {

    public IncompleteProfileException() {
        super("complete your profile (name, birth date and region) before registering");
    }

}
