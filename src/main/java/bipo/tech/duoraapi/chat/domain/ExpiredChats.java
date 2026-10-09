package bipo.tech.duoraapi.chat.domain;

import java.time.Instant;

/**
 * Os chats cujo conteúdo já pode ser apagado: {@code purge_after} antes do instante dado (docs/adr/0021). Apagar
 * um chat apaga as mensagens dele; as cópias denunciadas ficam no trustsafety.
 */
public interface ExpiredChats {

    /**
     * Apaga de fato até {@code limit} chats vencidos, os mais antigos primeiro, pulando os que outra transação
     * segura agora (outra réplica no meio de um lote, ou um envio): esses ficam para a próxima vez. Idempotente:
     * repetir depois de uma falha só apaga o que ainda existe.
     *
     * @return quantos chats saíram
     */
    int deleteBatch(Instant now, int limit);

    /** Quantos chats vencidos ainda existem. */
    long count(Instant now);

}
