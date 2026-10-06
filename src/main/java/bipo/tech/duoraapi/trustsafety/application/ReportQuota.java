package bipo.tech.duoraapi.trustsafety.application;

import bipo.tech.duoraapi.identity.AccountId;

/** Quantas denúncias cada conta ainda pode fazer, contadas entre todas as réplicas (docs/adr/0006). */
public interface ReportQuota {

    /**
     * Gasta uma denúncia da cota de {@code reporter}.
     *
     * @throws ReportQuotaExceededException se a cota acabou
     * @throws ReportQuotaUnavailableException se não deu para contar; a denúncia não passa (falha fechada)
     */
    void consume(AccountId reporter);

}
