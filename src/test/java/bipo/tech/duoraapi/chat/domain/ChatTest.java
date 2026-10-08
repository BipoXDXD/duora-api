package bipo.tech.duoraapi.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import bipo.tech.duoraapi.RefusalReason;
import bipo.tech.duoraapi.identity.AccountId;

class ChatTest {

    private static final UUID CHAT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final AccountId ANA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final AccountId BIA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
    private static final AccountId CAIO = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000c"));
    private static final ChatKey KEY = new ChatKey(EVENT, 1, ChatPair.of(ANA, BIA));
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final Instant SENT_AT = Instant.parse("2026-11-01T22:10:00Z");
    private static final ChatMessageText HELLO = new ChatMessageText("oi!");
    private static final OpeningConditions OPEN = new OpeningConditions(true, true, false);

    @Test
    void theFirstMessageGetsSequenceOne() {
        var chat = new Chat(CHAT_ID, KEY, 0);

        var message = chat.send(ANA, HELLO, IDEMPOTENCY_KEY, SENT_AT, OPEN);

        assertThat(message).isEqualTo(new ChatMessage(CHAT_ID, 1, ANA, HELLO, IDEMPOTENCY_KEY, SENT_AT));
        assertThat(chat.lastSeq()).isEqualTo(1);
    }

    @Test
    void eachMessageTakesTheNextSequenceWhoeverSendsIt() {
        var chat = new Chat(CHAT_ID, KEY, 7);

        var fromBia = chat.send(BIA, HELLO, IDEMPOTENCY_KEY, SENT_AT, OPEN);
        var fromAna = chat.send(ANA, HELLO, UUID.fromString("00000000-0000-0000-0000-0000000000f2"), SENT_AT, OPEN);

        assertThat(fromBia.seq()).isEqualTo(8);
        assertThat(fromAna.seq()).isEqualTo(9);
        assertThat(chat.lastSeq()).isEqualTo(9);
    }

    /** O motivo nunca aparece: rodada que passou, evento que acabou e bloqueio recebem a mesma recusa. */
    @ParameterizedTest
    @MethodSource("closingConditions")
    void aClosedChatRefusesMessagesWithTheSameReason(OpeningConditions conditions) {
        var chat = new Chat(CHAT_ID, KEY, 3);

        assertThat(chat.acceptsMessages(conditions)).isFalse();
        assertThatThrownBy(() -> chat.send(ANA, HELLO, IDEMPOTENCY_KEY, SENT_AT, conditions))
                .isInstanceOfSatisfying(ChatClosedException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(RefusalReason.CHAT_CLOSED))
                .hasMessage("the chat is closed");
        assertThat(chat.lastSeq()).isEqualTo(3);
    }

    static Stream<Arguments> closingConditions() {
        return Stream.of(
                Arguments.of(Named.of("evento fora do horário", new OpeningConditions(false, true, false))),
                Arguments.of(Named.of("rodada seguinte já começou", new OpeningConditions(true, false, false))),
                Arguments.of(Named.of("bloqueio entre os dois", new OpeningConditions(true, true, true))),
                Arguments.of(Named.of("tudo junto", new OpeningConditions(false, false, true))));
    }

    @Test
    void anOpenChatAcceptsMessages() {
        assertThat(new Chat(CHAT_ID, KEY, 0).acceptsMessages(OPEN)).isTrue();
    }

    @Test
    void theThreeHundredthMessageIsAccepted() {
        var chat = new Chat(CHAT_ID, KEY, 299);

        assertThat(chat.send(ANA, HELLO, IDEMPOTENCY_KEY, SENT_AT, OPEN).seq()).isEqualTo(300);
    }

    /** Um chat cheio é um chat fechado, com a mesma recusa (docs/adr/0021). */
    @Test
    void aFullChatRefusesTheThreeHundredAndFirstMessage() {
        var chat = new Chat(CHAT_ID, KEY, 300);

        assertThat(chat.acceptsMessages(OPEN)).isFalse();
        assertThatThrownBy(() -> chat.send(ANA, HELLO, IDEMPOTENCY_KEY, SENT_AT, OPEN))
                .isInstanceOf(ChatClosedException.class);
        assertThat(chat.lastSeq()).isEqualTo(300);
    }

    @Test
    void onlyThePairSends() {
        var chat = new Chat(CHAT_ID, KEY, 0);

        assertThatThrownBy(() -> chat.send(CAIO, HELLO, IDEMPOTENCY_KEY, SENT_AT, OPEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("only the pair of the round sends messages");
        assertThat(chat.lastSeq()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 301})
    void theLastSequenceStaysWithinTheChatLimit(int lastSeq) {
        assertThatThrownBy(() -> new Chat(CHAT_ID, KEY, lastSeq))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the last sequence goes from 0 to 300");
    }

    @Test
    void theContentIsPurgedADayAfterTheScheduledEnd() {
        assertThat(Chat.purgeAfter(Instant.parse("2026-11-02T01:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-03T01:00:00Z"));
    }

}
