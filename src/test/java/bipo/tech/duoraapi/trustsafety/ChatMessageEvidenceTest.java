package bipo.tech.duoraapi.trustsafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChatMessageEvidenceTest {

    private static final UUID CHAT = UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b");
    private static final UUID EVENT = UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8c");
    private static final Instant SENT_AT = Instant.parse("2026-10-06T12:00:00Z");

    @Test
    void keepsTheCopyOfTheMessage() {
        var evidence = new ChatMessageEvidence(CHAT, EVENT, 2, 7, "mensagem denunciada", SENT_AT);

        assertThat(evidence.chatId()).isEqualTo(CHAT);
        assertThat(evidence.eventId()).isEqualTo(EVENT);
        assertThat(evidence.roundNumber()).isEqualTo(2);
        assertThat(evidence.seq()).isEqualTo(7);
        assertThat(evidence.text()).isEqualTo("mensagem denunciada");
        assertThat(evidence.sentAt()).isEqualTo(SENT_AT);
    }

    /** A cópia é dado sensível: nenhum log que imprima o record mostra o texto. */
    @Test
    void doesNotExposeTheTextInToString() {
        var evidence = new ChatMessageEvidence(CHAT, EVENT, 1, 1, "CANARY-texto", SENT_AT);

        assertThat(evidence.toString()).doesNotContain("CANARY").contains("redacted");
    }

    @Test
    void acceptsTheLongestChatMessage() {
        String longest = "a".repeat(ChatMessageEvidence.MAX_TEXT_LENGTH - 1) + "😀";

        assertThat(new ChatMessageEvidence(CHAT, EVENT, 1, 300, longest, SENT_AT).text()).isEqualTo(longest);
    }

    @Test
    void rejectsAnEmptyText() {
        assertThatThrownBy(() -> new ChatMessageEvidence(CHAT, EVENT, 1, 1, "", SENT_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A mensagem de exceção pode ir para o log: não leva o texto. */
    @Test
    void rejectsATooLongTextWithoutEchoingIt() {
        String text = "CANARY" + "a".repeat(ChatMessageEvidence.MAX_TEXT_LENGTH);

        assertThatThrownBy(() -> new ChatMessageEvidence(CHAT, EVENT, 1, 1, text, SENT_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("CANARY");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 301})
    void rejectsAPositionOutsideTheChat(int seq) {
        assertThatThrownBy(() -> new ChatMessageEvidence(CHAT, EVENT, 1, seq, "oi", SENT_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsARoundBeforeTheFirst() {
        assertThatThrownBy(() -> new ChatMessageEvidence(CHAT, EVENT, 0, 1, "oi", SENT_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresTheIdsTheTextAndTheTime() {
        assertThatThrownBy(() -> new ChatMessageEvidence(null, EVENT, 1, 1, "oi", SENT_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChatMessageEvidence(CHAT, null, 1, 1, "oi", SENT_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChatMessageEvidence(CHAT, EVENT, 1, 1, null, SENT_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChatMessageEvidence(CHAT, EVENT, 1, 1, "oi", null))
                .isInstanceOf(NullPointerException.class);
    }

}
