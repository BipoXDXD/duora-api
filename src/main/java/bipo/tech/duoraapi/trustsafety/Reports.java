package bipo.tech.duoraapi.trustsafety;

import org.springframework.stereotype.Component;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.trustsafety.application.ReportService;
import bipo.tech.duoraapi.trustsafety.domain.Report;
import bipo.tech.duoraapi.trustsafety.domain.ReportDescription;

/**
 * API publicada do trustsafety para os outros módulos (docs/adr/0021): denunciar uma conta por algo que ela
 * escreveu em outro módulo, guardando uma cópia como evidência. Quem chama já conferiu que a conta denunciada é a
 * autora do que é copiado; a denúncia segue as regras e a cota de {@code POST /api/reports} (docs/adr/0015).
 */
@Component
public class Reports {

    /** Teto do relato, em caracteres: quem documenta o campo na própria rota repete o mesmo limite. */
    public static final int DESCRIPTION_MAX_LENGTH = ReportDescription.MAX_LENGTH;

    /**
     * A capacidade do limite de denúncias em application.properties (por dia). As descrições da spec, aqui e na
     * rota do chat, citam este número, porque uma anotação só aceita constante; o RateLimitDescriptionsTest falha
     * se ele divergir.
     */
    public static final int DEFAULT_DAILY_LIMIT = 10;

    private final ReportService reports;

    Reports(ReportService reports) {
        this.reports = reports;
    }

    /**
     * Grava a denúncia e a cópia da mensagem juntas: as duas ou nenhuma. Só denúncia válida gasta a cota de quem
     * denuncia. Não participa de transação de quem chama: a cota é contada antes, em outra conexão.
     *
     * @param description o relato livre, opcional salvo com {@link ReportReason#OTHER}
     * @throws bipo.tech.duoraapi.InvalidFieldException se a denúncia está fora das regras (400 com o campo)
     * @throws bipo.tech.duoraapi.config.RateLimitExceededException se a cota de denúncias acabou
     * @throws bipo.tech.duoraapi.config.RateLimitUnavailableException se não deu para contar a cota
     */
    public FiledReport fileWithEvidence(AccountId reporter, AccountId reported, ReportReason reason,
            String description, ChatMessageEvidence evidence) {
        Report report = reports.fileWithEvidence(reporter, reported, reason, description, evidence);
        return new FiledReport(report.id(), report.reported(), report.reason(),
                report.description() == null ? null : report.description().value(), report.status(),
                report.createdAt());
    }

}
