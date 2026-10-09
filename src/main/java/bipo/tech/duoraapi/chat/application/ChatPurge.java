package bipo.tech.duoraapi.chat.application;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import bipo.tech.duoraapi.chat.domain.ExpiredChats;

/**
 * O expurgo do chat (docs/adr/0021): 24 h depois do fim agendado do evento, o chat e as mensagens são apagados de
 * fato (plano, seção 4). Roda em todas as réplicas ao mesmo tempo sem combinar nada entre elas: cada lote pula o
 * que outra réplica já segura. O log e a métrica levam só contagens, nunca conteúdo nem quem conversou.
 */
@Service
public class ChatPurge {

    /** Chats vencidos que ainda não saíram: se o expurgo parar ou não der conta, sobe sem parar. */
    public static final String BACKLOG_METRIC = "duora.chat.purge.backlog";

    private static final Logger log = LoggerFactory.getLogger(ChatPurge.class);

    private final ExpiredChats expired;
    private final Clock clock;

    public ChatPurge(ExpiredChats expired, Clock clock) {
        this.expired = expired;
        this.clock = clock;
    }

    /**
     * Apaga os chats vencidos em lotes de {@code batchSize}, um comando por lote (locks curtos), até um lote vir
     * incompleto ou chegar a {@code maxBatches}: o que sobrar fica para a próxima execução. O instante de corte é
     * lido uma vez, no começo.
     *
     * @return quantos chats esta execução apagou
     */
    public int purgeExpired(int batchSize, int maxBatches) {
        if (batchSize < 1 || maxBatches < 1) {
            throw new IllegalArgumentException("the batch size and the number of batches must be positive");
        }
        Instant now = clock.instant();
        int purged = 0;
        int batches = 0;
        int deleted;
        do {
            deleted = expired.deleteBatch(now, batchSize);
            purged += deleted;
            batches++;
        } while (deleted == batchSize && batches < maxBatches);
        log.info("Purged {} expired chats in {} batches; {} expired chats left", purged, batches, expired.count(now));
        return purged;
    }

    /** Quantos chats vencidos ainda não saíram, agora. */
    public long expiredBacklog() {
        return expired.count(clock.instant());
    }

}
