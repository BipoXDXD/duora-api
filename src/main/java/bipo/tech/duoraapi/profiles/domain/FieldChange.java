package bipo.tech.duoraapi.profiles.domain;

import java.util.Objects;
import java.util.function.Function;

/**
 * O que uma edição parcial faz com um campo: nada (campo ausente no pedido), apagar (null) ou trocar
 * pelo valor. Três estados, porque {@code null} sozinho não distingue os dois primeiros.
 */
public sealed interface FieldChange<T> {

    static <T> FieldChange<T> keep() {
        return new Keep<>();
    }

    static <T> FieldChange<T> clear() {
        return new Clear<>();
    }

    static <T> FieldChange<T> setTo(T value) {
        return new SetTo<>(value);
    }

    /** Converte o valor novo, se houver; manter e apagar continuam como estão. */
    default <R> FieldChange<R> map(Function<? super T, ? extends R> mapper) {
        return switch (this) {
            case Keep<T> _ -> keep();
            case Clear<T> _ -> clear();
            case SetTo<T>(T value) -> setTo(mapper.apply(value));
        };
    }

    record Keep<T>() implements FieldChange<T> {
    }

    record Clear<T>() implements FieldChange<T> {
    }

    record SetTo<T>(T value) implements FieldChange<T> {

        public SetTo {
            Objects.requireNonNull(value, "value");
        }

        /** O valor pode ser dado pessoal (data de nascimento): fora dos logs. */
        @Override
        public String toString() {
            return "SetTo[redacted]";
        }

    }

}
