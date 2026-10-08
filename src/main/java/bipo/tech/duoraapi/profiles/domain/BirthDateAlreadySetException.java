package bipo.tech.duoraapi.profiles.domain;

import bipo.tech.duoraapi.ActionRefusedException;
import bipo.tech.duoraapi.RefusalReason;

/** A data de nascimento só é informada uma vez; trocar exige outro caminho (suporte), ainda não definido. */
public class BirthDateAlreadySetException extends ActionRefusedException {

    public BirthDateAlreadySetException() {
        super(RefusalReason.BIRTH_DATE_ALREADY_SET, "birthDate is already set and cannot be changed");
    }

}
