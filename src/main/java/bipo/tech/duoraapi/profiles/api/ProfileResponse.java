package bipo.tech.duoraapi.profiles.api;

import java.time.LocalDate;

import bipo.tech.duoraapi.profiles.application.ProfileView;
import bipo.tech.duoraapi.profiles.domain.Bio;
import bipo.tech.duoraapi.profiles.domain.DisplayName;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * O perfil para o próprio dono. Campos não preenchidos vêm como null, e a chave continua no JSON.
 * {@code complete}: nome, data de nascimento de maior de idade e região preenchidos. Sem id da conta
 * nem versão no corpo: a versão vai no ETag.
 */
record ProfileResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                maxLength = DisplayName.MAX_LENGTH, description = "Nome de exibição, ou null se ainda não informado")
        String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date",
                maxLength = EditProfileRequest.ISO_DATE_LENGTH,
                description = "Data de nascimento, ou null se ainda não informada")
        LocalDate birthDate,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, maxLength = Bio.MAX_LENGTH,
                description = "Apresentação, ou null")
        String bio,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                description = "Estado, pelo código ISO 3166-2, ou null se ainda não informado")
        String region,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Nome, data de nascimento de maior de idade e região preenchidos")
        boolean complete) {

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
