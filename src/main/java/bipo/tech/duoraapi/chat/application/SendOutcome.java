package bipo.tech.duoraapi.chat.application;

import java.util.Objects;

import bipo.tech.duoraapi.chat.domain.ChatMessage;

/** A mensagem enviada e se ela foi gravada agora ou é a repetição de um envio com a mesma Idempotency-Key. */
public record SendOutcome(ChatMessage message, boolean created) {

    public SendOutcome {
        Objects.requireNonNull(message, "message");
    }

}
