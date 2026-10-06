package bipo.tech.duoraapi.trustsafety.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.trustsafety.application.ReportQuotaExceededException;
import bipo.tech.duoraapi.trustsafety.application.ReportQuotaUnavailableException;
import bipo.tech.duoraapi.trustsafety.application.ReportService;
import bipo.tech.duoraapi.trustsafety.domain.InvalidReportException;
import bipo.tech.duoraapi.trustsafety.domain.UnknownAccountException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Denúncias (docs/adr/0015). Criar responde 201 com Location e a denúncia (docs/adr/0005); só quem
 * denunciou a lê, e a de outra pessoa responde 404, igual a um id que não existe. Denunciar não bloqueia:
 * o front chama {@code :block} em seguida, se a pessoa pedir.
 */
@RestController
@Tag(name = "reports", description = "Denúncias para a moderação")
class ReportController {

    static final String PATH = "/api/reports";

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_SCHEMA = "#/components/schemas/ProblemDetail";
    /** Teto do Retry-After: o lint OWASP exige mínimo e máximo; a cota se repõe em menos de um dia. */
    private static final String MAX_RETRY_AFTER_SECONDS = "86400";

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    @PostMapping(PATH)
    @Operation(operationId = "fileReport", summary = "Denuncia outra conta",
            description = "Cria a denúncia no estado OPEN, para a moderação. Denunciar não bloqueia: para isso, "
                    + "chame blockAccount. Cada conta pode fazer 10 denúncias por dia, repostas aos poucos.")
    @ApiResponse(responseCode = "201", description = "A denúncia criada",
            headers = @Header(name = "Location", required = true, description = "Endereço da denúncia criada",
                    schema = @Schema(type = "string", format = "uri", maxLength = 2048)))
    @ApiResponse(responseCode = "400",
            description = "Denúncia de si mesmo, motivo OTHER sem descrição, descrição inválida, "
                    + "JSON malformado ou "
                    + "campo desconhecido",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = "Não há conta com o id denunciado",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "429", description = "Cota diária de denúncias desta conta esgotada",
            headers = @Header(name = "Retry-After", required = true,
                    description = "Segundos até a próxima denúncia ficar disponível",
                    schema = @Schema(type = "integer", format = "int64", minimum = "0",
                            maximum = MAX_RETRY_AFTER_SECONDS)),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "503", description = "Cota indisponível; a denúncia é recusada",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    ResponseEntity<ReportResponse> file(AccountId caller, @Valid @RequestBody FileReportRequest request) {
        var report = reports.file(caller, new AccountId(request.reportedAccountId()), request.reason(),
                request.description());
        var location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(report.id()).toUri();
        return ResponseEntity.created(location).body(ReportResponse.of(report));
    }

    @GetMapping(PATH + "/{id}")
    @Operation(operationId = "getMyReport", summary = "Lê uma denúncia feita pelo usuário",
            description = "Só quem denunciou a lê. A de outra pessoa responde como um id que não existe.")
    @ApiResponse(responseCode = "200", description = "A denúncia")
    @ApiResponse(responseCode = "400", description = "Id que não é UUID",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = "Denúncia inexistente ou de outra pessoa",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    ReportResponse myReport(AccountId caller,
            @Parameter(description = "Id da denúncia",
                    schema = @Schema(type = "string", format = "uuid", minLength = ApiSchemas.UUID_LENGTH,
                            maxLength = ApiSchemas.UUID_LENGTH))
            @PathVariable UUID id) {
        return reports.reportFiledBy(caller, id)
                .map(ReportResponse::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "report not found"));
    }

    @ExceptionHandler(InvalidReportException.class)
    ProblemDetail handleInvalidReport(InvalidReportException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(UnknownAccountException.class)
    ProblemDetail handleUnknownAccount(UnknownAccountException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(ReportQuotaExceededException.class)
    ResponseEntity<ProblemDetail> handleQuotaExceeded(ReportQuotaExceededException exception) {
        long retryAfterSeconds = Math.ceilDiv(exception.retryAfter().toNanos(), NANOS_PER_SECOND);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage()));
    }

    /** Falha fechada (docs/adr/0006): sem contar a cota, a denúncia não passa. */
    @ExceptionHandler(ReportQuotaUnavailableException.class)
    ProblemDetail handleQuotaUnavailable() {
        return ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }

}
