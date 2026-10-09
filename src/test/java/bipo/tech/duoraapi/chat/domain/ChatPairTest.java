package bipo.tech.duoraapi.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import bipo.tech.duoraapi.identity.AccountId;

class ChatPairTest {

    /** Ids cuja ordem no texto difere da do UUID.compareTo do Java, que compara bits com sinal. */
    private static final AccountId LOW = new AccountId(UUID.fromString("7fffffff-0000-0000-0000-000000000000"));
    private static final AccountId HIGH = new AccountId(UUID.fromString("80000000-0000-0000-0000-000000000000"));
    private static final AccountId OTHER = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000c"));

    @Test
    void thePairHasNoSideAndFollowsTheOrderOfTheUuidType() {
        assertThat(ChatPair.of(HIGH, LOW)).isEqualTo(ChatPair.of(LOW, HIGH));
        assertThat(ChatPair.of(HIGH, LOW).first()).isEqualTo(LOW);
    }

    @Test
    void includesOnlyItsTwoAccounts() {
        var pair = ChatPair.of(LOW, HIGH);

        assertThat(pair.includes(LOW)).isTrue();
        assertThat(pair.includes(HIGH)).isTrue();
        assertThat(pair.includes(OTHER)).isFalse();
    }

    @Test
    void nobodyChatsWithThemselves() {
        assertThatThrownBy(() -> ChatPair.of(LOW, LOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a chat needs two different accounts");
    }

    @Test
    void theConstructorRequiresTheNormalizedOrder() {
        assertThatThrownBy(() -> new ChatPair(HIGH, LOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("first must come before second; use ChatPair.of");
    }

}
