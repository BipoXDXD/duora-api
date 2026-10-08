package bipo.tech.duoraapi.chat.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Os chats e as mensagens gravados (docs/adr/0021). A unicidade do chat por par e rodada, da sequência e da
 * Idempotency-Key é do banco; a sequência é atribuída com o chat travado.
 */
public interface ChatRepository {

    /** Limita quanto a transação atual espera por um lock, até o fim dela. Chame antes de {@link #lock}. */
    void limitLockWait();

    /**
     * Cria o chat do par na rodada, se ainda não existe. Se outra transação estiver criando o mesmo, espera
     * por ela; se ela confirmar, não grava nada.
     */
    void addIfAbsent(ChatKey key, Instant purgeAfter, Instant createdAt);

    Optional<Chat> find(ChatKey key);

    /**
     * O chat com a linha travada até o fim da transação: os envios ao mesmo chat passam um de cada vez, e cada
     * um enxerga a última sequência já confirmada pelo anterior.
     */
    Optional<Chat> lock(ChatKey key);

    Optional<ChatMessage> findBySenderAndIdempotencyKey(UUID chatId, AccountId sender, UUID idempotencyKey);

    /** Grava a mensagem e avança a última sequência do chat até a dela. Chame com o chat travado. */
    void record(ChatMessage message);

    /** Até {@code limit} mensagens com sequência maior que {@code afterSeq}, em ordem crescente. */
    List<ChatMessage> findAfter(UUID chatId, int afterSeq, int limit);

    Optional<ChatMessage> findMessage(UUID chatId, int seq);

}
