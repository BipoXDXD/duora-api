package bipo.tech.duoraapi.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import bipo.tech.duoraapi.FreeTextArbitraries;

class ChatMessageTextPropertiesTest {

    /**
     * O repositório recria o texto a partir do valor gravado: o que foi aceito uma vez precisa ser aceito de novo e
     * sem mudar, ou a mensagem gravada deixa de abrir.
     */
    @Property
    void anAcceptedTextIsAcceptedAgainUnchanged(@ForAll("texts") String typed) {
        Optional<ChatMessageText> accepted = accepted(typed);
        Assume.that(accepted.isPresent());
        ChatMessageText text = accepted.orElseThrow();

        assertThat(new ChatMessageText(text.value())).isEqualTo(text);
    }

    @Provide
    Arbitrary<String> texts() {
        return FreeTextArbitraries.paragraphs(ChatMessageText.MAX_LENGTH);
    }

    private static Optional<ChatMessageText> accepted(String typed) {
        try {
            return Optional.of(new ChatMessageText(typed));
        } catch (InvalidChatMessageException rejected) {
            return Optional.empty();
        }
    }

}
