package bipo.tech.duoraapi.events.application;

import bipo.tech.duoraapi.ActionRefusedException;
import bipo.tech.duoraapi.RefusalReason;

/** Só quem tem o perfil completo (nome, região e 18 anos completos) se inscreve em eventos. */
public class IneligibleToRegisterException extends ActionRefusedException {

    private IneligibleToRegisterException(RefusalReason reason, String message) {
        super(reason, message);
    }

    static IneligibleToRegisterException profileIncomplete() {
        return new IneligibleToRegisterException(RefusalReason.PROFILE_INCOMPLETE,
                "complete your profile (name, birth date and region) before registering");
    }

    static IneligibleToRegisterException underage() {
        return new IneligibleToRegisterException(RefusalReason.UNDERAGE, "only adults (18 or older) can register");
    }

}
