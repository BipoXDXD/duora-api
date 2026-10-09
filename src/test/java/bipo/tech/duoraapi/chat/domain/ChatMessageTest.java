package bipo.tech.duoraapi.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import bipo.tech.duoraapi.RefusalReason;
import bipo.tech.duoraapi.identity.AccountId;

class ChatMessageTest {

    private static final UUID CHAT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final AccountId ANA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final AccountId BIA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final Instant SENT_AT = Instant.parse("2026-11-01T22:10:00Z");

    private final ChatMessage sent = new ChatMessage(CHAT_ID, 4, ANA, new ChatMessageText("oi!"), IDEMPOTENCY_KEY,
            SENT_AT);

    @Test
    void aRetryWithTheSameTextIsTheSameMessage() {
        assertThat(sent.replayFor(new ChatMessageText("oi!"))).isSameAs(sent);
    }

    /** O texto é comparado depois de normalizado: espaço nas pontas não muda a mensagem. */
    @Test
    void aRetryWithTheSameNormalizedTextIsTheSameMessage() {
        assertThat(sent.replayFor(new ChatMessageText("  oi!\n"))).isSameAs(sent);
    }

    @Test
    void aRetryWithAnotherTextIsAConflict() {
        assertThatThrownBy(() -> sent.replayFor(new ChatMessageText("oi?")))
                .isInstanceOfSatisfying(IdempotencyKeyReusedException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(RefusalReason.IDEMPOTENCY_KEY_REUSED))
                .hasMessage("the idempotency key was already used with another text");
    }

    @Test
    void tellsWhoSentIt() {
        assertThat(sent.sentBy(ANA)).isTrue();
        assertThat(sent.sentBy(BIA)).isFalse();
    }

    @Test
    void theSequenceGoesFromOneToThreeHundred() {
        var text = new ChatMessageText("oi!");

        assertThat(new ChatMessage(CHAT_ID, 1, ANA, text, IDEMPOTENCY_KEY, SENT_AT).seq()).isEqualTo(1);
        assertThat(new ChatMessage(CHAT_ID, 300, ANA, text, IDEMPOTENCY_KEY, SENT_AT).seq()).isEqualTo(300);
        assertThatThrownBy(() -> new ChatMessage(CHAT_ID, 0, ANA, text, IDEMPOTENCY_KEY, SENT_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the sequence goes from 1 to 300");
        assertThatThrownBy(() -> new ChatMessage(CHAT_ID, 301, ANA, text, IDEMPOTENCY_KEY, SENT_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotExposeTheTextInToString() {
        var message = new ChatMessage(CHAT_ID, 1, ANA, new ChatMessageText("segredo do encontro"), IDEMPOTENCY_KEY,
                SENT_AT);

        assertThat(message.toString()).doesNotContain("segredo");
    }

}
