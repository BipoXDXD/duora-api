package bipo.tech.duoraapi.chat.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Uma mensagem gravada. A posição no chat é a sequência, que segue a ordem de commit, e nunca o horário
 * (docs/adr/0021). O toString não mostra o texto, porque o do {@link ChatMessageText} é redigido.
 *
 * @param seq de 1 a {@link Chat#MAX_MESSAGES}, sem lacunas no chat
 * @param idempotencyKey a chave do envio, única por remetente no chat
 */
public record ChatMessage(UUID chatId, int seq, AccountId sender, ChatMessageText text, UUID idempotencyKey,
        Instant sentAt) {

    public ChatMessage {
        Objects.requireNonNull(chatId, "chatId");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(sentAt, "sentAt");
        if (seq < 1 || seq > Chat.MAX_MESSAGES) {
            throw new IllegalArgumentException("the sequence goes from 1 to " + Chat.MAX_MESSAGES);
        }
    }

    /**
     * O reenvio com a mesma chave é a mesma mensagem, mesmo que o chat tenha fechado depois; com outro texto,
     * é outro envio usando a chave errada.
     *
     * @throws IdempotencyKeyReusedException se o texto pedido não é o gravado
     */
    public ChatMessage replayFor(ChatMessageText requested) {
        if (!text.equals(requested)) {
            throw new IdempotencyKeyReusedException();
        }
        return this;
    }

    public boolean sentBy(AccountId account) {
        return sender.equals(account);
    }

}
