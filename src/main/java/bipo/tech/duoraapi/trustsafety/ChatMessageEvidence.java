package bipo.tech.duoraapi.trustsafety;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Cópia de uma mensagem do chat no instante em que foi denunciada (docs/adr/0021): a moderação a lê depois que o
 * chat for expurgado. Quem a enviou é a conta denunciada. O texto é dado sensível: o toString não o mostra, e as
 * mensagens de erro também não.
 *
 * @param chatId o chat de onde veio, só como referência: ele some no expurgo
 * @param roundNumber a rodada do evento, a partir de 1
 * @param seq a posição da mensagem no chat, de 1 a 300
 * @param text o texto como foi gravado no chat, de 1 a {@link #MAX_TEXT_LENGTH} caracteres
 * @param sentAt quando a mensagem foi gravada no chat
 */
public record ChatMessageEvidence(UUID chatId, UUID eventId, int roundNumber, int seq, String text, Instant sentAt) {

    /** O mesmo teto da mensagem do chat, em code points: muda junto com ele (V13). */
    public static final int MAX_TEXT_LENGTH = 500;

    /** O mesmo teto de mensagens por chat. */
    public static final int MAX_SEQ = 300;

    public ChatMessageEvidence {
        Objects.requireNonNull(chatId, "chatId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(sentAt, "sentAt");
        if (roundNumber < 1) {
            throw new IllegalArgumentException("the round number starts at 1");
        }
        if (seq < 1 || seq > MAX_SEQ) {
            throw new IllegalArgumentException("the sequence goes from 1 to " + MAX_SEQ);
        }
        int length = text.codePointCount(0, text.length());
        if (length < 1 || length > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("the text has from 1 to " + MAX_TEXT_LENGTH + " characters");
        }
    }

    @Override
    public String toString() {
        return "ChatMessageEvidence[chatId=" + chatId + ", eventId=" + eventId + ", roundNumber=" + roundNumber
                + ", seq=" + seq + ", sentAt=" + sentAt + ", text=redacted]";
    }

}
