package bipo.tech.duoraapi.profiles.api;

import java.time.LocalDate;

import bipo.tech.duoraapi.profiles.application.ProfileView;

/**
 * O perfil para o próprio dono. Campos não preenchidos vêm como null, e a chave continua no JSON.
 * {@code complete}: nome, data de nascimento de maior de idade e região preenchidos. Sem id da conta
 * nem versão no corpo: a versão vai no ETag.
 */
record ProfileResponse(String displayName, LocalDate birthDate, String bio, String region, boolean complete) {

    static ProfileResponse of(ProfileView profile) {
        return new ProfileResponse(profile.displayName(), profile.birthDate(), profile.bio(), profile.regionCode(),
                profile.complete());
    }

    /** Nome, data de nascimento e bio são dados pessoais: fora dos logs. */
    @Override
    public String toString() {
        return "ProfileResponse[complete=" + complete + "]";
    }

}
