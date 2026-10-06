package bipo.tech.duoraapi.trustsafety.application;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.trustsafety.domain.NewReport;
import bipo.tech.duoraapi.trustsafety.domain.Report;
import bipo.tech.duoraapi.trustsafety.domain.ReportDescription;
import bipo.tech.duoraapi.trustsafety.domain.ReportReason;
import bipo.tech.duoraapi.trustsafety.domain.ReportRepository;

/**
 * Denunciar outra conta e ler as próprias denúncias. Quem chama passa a conta autenticada. A denúncia é
 * gravada num comando só, então não há transação própria; a cota usa outra conexão, e não precisa
 * esperar dentro de uma transação aberta.
 */
@Service
public class ReportService {

    private final ReportRepository repository;
    private final ReportQuota quota;
    private final Clock clock;

    public ReportService(ReportRepository repository, ReportQuota quota, Clock clock) {
        this.repository = repository;
        this.quota = quota;
        this.clock = clock;
    }

    /**
     * Só denúncia válida gasta a cota; a que aponta para conta inexistente gasta, o que também limita
     * quem tenta adivinhar ids.
     *
     * @param descriptionText o relato livre, opcional salvo com {@link ReportReason#OTHER}
     * @throws bipo.tech.duoraapi.trustsafety.domain.InvalidReportException se a denúncia está fora das regras
     * @throws bipo.tech.duoraapi.trustsafety.domain.UnknownAccountException se a conta denunciada não existe
     * @throws ReportQuotaExceededException se a cota da conta acabou
     */
    public Report file(AccountId reporter, AccountId reported, ReportReason reason, String descriptionText) {
        var description = ReportDescription.fromText(descriptionText).orElse(null);
        var report = new NewReport(reporter, reported, reason, description, clock.instant());
        quota.consume(reporter);
        return repository.add(report);
    }

    public Optional<Report> reportFiledBy(AccountId reporter, UUID reportId) {
        return repository.findFiledBy(reporter, reportId);
    }

}
