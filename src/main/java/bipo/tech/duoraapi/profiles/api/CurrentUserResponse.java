package bipo.tech.duoraapi.profiles.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Só o que o front exibe: e-mail, oid, id da conta e papéis ficam no servidor. displayName é o nome do
 * Entra, null quando o usuário não tem nome lá. profileComplete diz se o front deve levar ao cadastro
 * do perfil (docs/adr/0011); veio depois, como campo novo, sem mudar o que já existia.
 */
record CurrentUserResponse(
        // 256: limite do displayName de usuário no Microsoft Graph, de onde vem a claim name.
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, maxLength = 256,
                description = "Nome de exibição no Entra, ou null se o usuário não tiver nome")
        String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Se o perfil já tem o necessário para usar o Duora; se não, o front leva ao cadastro")
        boolean profileComplete) {
}
