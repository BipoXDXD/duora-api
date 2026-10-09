package bipo.tech.duoraapi.chat.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * O chat temporário do par de uma rodada (docs/adr/0021). Guarda só a última sequência: a próxima mensagem
 * leva a seguinte, e quem grava trava o chat até o commit, então a ordem da sequência é a ordem de commit e
 * não há lacunas. Aberto ou fechado não é guardado: depende do evento, da rodada e do bloqueio no instante.
 */
public final class Chat {

    /** Mensagens por chat, contando as das duas pessoas. */
    public static final int MAX_MESSAGES = 300;

    /** Por quanto tempo o conteúdo fica depois do fim agendado do evento, para reler e denunciar. */
    public static final Duration RETENTION_AFTER_EVENT = Duration.ofHours(24);

    private final UUID id;
    private final ChatKey key;
    private int lastSeq;

    /** @param lastSeq a sequência da última mensagem gravada, ou 0 sem nenhuma */
    public Chat(UUID id, ChatKey key, int lastSeq) {
        this.id = Objects.requireNonNull(id, "id");
        this.key = Objects.requireNonNull(key, "key");
        if (lastSeq < 0 || lastSeq > MAX_MESSAGES) {
            throw new IllegalArgumentException("the last sequence goes from 0 to " + MAX_MESSAGES);
        }
        this.lastSeq = lastSeq;
    }

    /** Quando o conteúdo de um chat do evento pode ser apagado: o horário de fim não muda depois de criado. */
    public static Instant purgeAfter(Instant eventEndsAt) {
        return eventEndsAt.plus(RETENTION_AFTER_EVENT);
    }

    /** Aberto: evento em andamento, rodada atual, sem bloqueio entre os dois e com espaço para mais uma. */
    public boolean acceptsMessages(OpeningConditions conditions) {
        return conditions.allowSending() && lastSeq < MAX_MESSAGES;
    }

    /**
     * Grava a próxima mensagem do chat.
     *
     * @throws ChatClosedException se o chat não aceita mensagens, pelo motivo que for
     * @throws IllegalArgumentException se quem envia não é do par: quem chama confere o par antes
     */
    public ChatMessage send(AccountId sender, ChatMessageText text, UUID idempotencyKey, Instant sentAt,
            OpeningConditions conditions) {
        if (!key.pair().includes(sender)) {
            throw new IllegalArgumentException("only the pair of the round sends messages");
        }
        if (!acceptsMessages(conditions)) {
            throw new ChatClosedException();
        }
        lastSeq++;
        return new ChatMessage(id, lastSeq, sender, text, idempotencyKey, sentAt);
    }

    public UUID id() {
        return id;
    }

    public ChatKey key() {
        return key;
    }

    public int lastSeq() {
        return lastSeq;
    }

}
