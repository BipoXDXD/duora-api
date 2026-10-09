package bipo.tech.duoraapi.profiles.api;

import java.util.List;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Só o que o front exibe: e-mail, oid e id da conta ficam no servidor. displayName é o nome do Entra, null
 * quando o usuário não tem nome lá. profileComplete diz se o front deve levar ao cadastro do perfil
 * (docs/adr/0011); roles diz quais telas administrativas oferecer. Os dois vieram depois, como campos novos,
 * sem mudar o que já existia. Quem manda é o servidor: o papel é conferido de novo em cada rota.
 */
record CurrentUserResponse(
        // 256: limite do displayName de usuário no Microsoft Graph, de onde vem a claim name.
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, maxLength = 256,
                description = "Nome de exibição no Entra, ou null se o usuário não tiver nome")
        String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Se o perfil já tem o necessário para usar o Duora; se não, o front leva ao cadastro")
        boolean profileComplete,
        // 10: folga sobre os papéis conhecidos, só para o lint exigir um teto de itens.
        @ArraySchema(maxItems = 10, uniqueItems = true,
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Papéis do usuário, vazio para o usuário comum. O front só decide o que "
                                + "mostrar; cada rota confere o papel no servidor"))
        List<UserRole> roles) {

    /** O Spring MVC registra a resposta por este toString em DEBUG: o nome é dado pessoal e fica de fora. */
    @Override
    public String toString() {
        return "CurrentUserResponse[profileComplete=" + profileComplete + ", roles=" + roles + "]";
    }

}
