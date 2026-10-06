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

/**
 * Denúncias (docs/adr/0015). Criar responde 201 com Location e a denúncia (docs/adr/0005); só quem
 * denunciou a lê, e a de outra pessoa responde 404, igual a um id que não existe. Denunciar não bloqueia:
 * o front chama {@code :block} em seguida, se a pessoa pedir.
 */
@RestController
class ReportController {

    static final String PATH = "/api/reports";

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    @PostMapping(PATH)
    ResponseEntity<ReportResponse> file(AccountId caller, @Valid @RequestBody FileReportRequest request) {
        var report = reports.file(caller, new AccountId(request.reportedAccountId()), request.reason(),
                request.description());
        var location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(report.id()).toUri();
        return ResponseEntity.created(location).body(ReportResponse.of(report));
    }

    @GetMapping(PATH + "/{id}")
    ReportResponse myReport(AccountId caller, @PathVariable UUID id) {
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
