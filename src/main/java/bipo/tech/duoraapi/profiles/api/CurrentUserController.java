package bipo.tech.duoraapi.profiles.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.profiles.application.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Quem está logado, para o front web saber se há sessão, o que exibir e se falta completar o perfil
 * (docs/adr/0002). O principal é o OidcUser da sessão web ou o Jwt da porta bearer; os dois expõem as
 * claims do Entra. É a primeira chamada do front depois do login, então é aqui que a conta interna
 * costuma ser aberta (docs/adr/0011).
 */
@RestController
@Tag(name = "session", description = "Sessão do usuário logado")
class CurrentUserController {

    private final ProfileService profiles;

    CurrentUserController(ProfileService profiles) {
        this.profiles = profiles;
    }

    /** errorOnInvalidType: outro tipo de principal falha aqui, com a causa, em vez de chegar como null. */
    @GetMapping("/api/me")
    @Operation(operationId = "getCurrentUser", summary = "Quem está logado",
            description = "O front usa para saber se há sessão: 200 com o nome de exibição e se o perfil está "
                    + "completo, ou 401 sem sessão.")
    CurrentUserResponse currentUser(@AuthenticationPrincipal(errorOnInvalidType = true) ClaimAccessor user,
            AccountId account) {
        return new CurrentUserResponse(user.getClaimAsString(StandardClaimNames.NAME),
                profiles.profileOf(account.value()).complete());
    }

}
