package bipo.tech.duoraapi.waitlist.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bipo.tech.duoraapi.waitlist.application.WaitlistService;
import bipo.tech.duoraapi.waitlist.domain.EmailAddress;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "waitlist", description = "Lista de espera do pré-lançamento")
class WaitlistController {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_SCHEMA = "#/components/schemas/ProblemDetail";

    /** Teto real de um long na spec: o lint OWASP exige mínimo e máximo declarados em todo inteiro. */
    static final String INT64_MAX = "9223372036854775807";

    private final WaitlistService waitlistService;

    WaitlistController(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    /** Mesma resposta para e-mail novo ou repetido: não revela quem já está na lista. */
    @PostMapping("/api/waitlist")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "joinWaitlist", summary = "Inscreve um e-mail na lista de espera",
            description = "Pública. A resposta é a mesma para e-mail novo ou já inscrito, para não revelar quem "
                    + "está na lista. Limitada por IP (IPv6 por rede /64), com o estado compartilhado entre réplicas.")
    @SecurityRequirements
    @ApiResponse(responseCode = "202", description = "Inscrição aceita, nova ou repetida")
    @ApiResponse(responseCode = "400", description = "E-mail ausente ou inválido, JSON malformado ou campo desconhecido",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "429", description = "Limite de inscrições deste cliente esgotado",
            headers = @Header(name = "Retry-After", required = true,
                    description = "Segundos até a próxima inscrição ser aceita",
                    schema = @Schema(type = "integer", format = "int64", minimum = "0", maximum = INT64_MAX)),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "503", description = "Limite de inscrições indisponível; a inscrição é recusada",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    void join(@RequestBody JoinWaitlistRequest request) {
        waitlistService.join(new EmailAddress(request.email()));
    }

    @GetMapping("/api/admin/waitlist/stats")
    @Operation(operationId = "getWaitlistStats", summary = "Total de inscrições na lista de espera",
            description = "Só ADMIN.")
    WaitlistStatsResponse stats() {
        return new WaitlistStatsResponse(waitlistService.countEntries());
    }

}
