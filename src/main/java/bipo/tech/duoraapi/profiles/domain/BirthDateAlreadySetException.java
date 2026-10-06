package bipo.tech.duoraapi.profiles.domain;

/** A data de nascimento só é informada uma vez; trocar exige outro caminho (suporte), ainda não definido. */
public class BirthDateAlreadySetException extends RuntimeException {

    public BirthDateAlreadySetException() {
        super("birthDate is already set and cannot be changed");
    }

}
