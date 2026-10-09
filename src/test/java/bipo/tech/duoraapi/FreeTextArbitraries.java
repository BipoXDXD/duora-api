package bipo.tech.duoraapi;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;

/**
 * Textos livres para as propriedades dos value objects de texto: pedaços que a normalização (NFC, quebra de linha
 * do Windows, espaço nas pontas) muda, misturados com pedaços que ela mantém. Os caracteres proibidos ficam de
 * fora, porque os exemplos de cada value object já os cobrem e eles fariam o jqwik descartar quase todo texto.
 */
public final class FreeTextArbitraries {

    private static final String[] PIECES = {
        "a", "Z", "7", "!", "<b>", " ", "\n", "\r\n",
        "é", "e", "́", // "e" seguido do acento combinante compõe "é"
        "Å", // sinal de angström: a NFC troca por "Å"
        "ᄀ", "ᅡ", "ᆨ", // jamos do hangul: a NFC compõe uma sílaba
        "👩", "‍", "💻", // mulher, ZWJ e notebook: emoji composto
    };

    private FreeTextArbitraries() {
    }

    /** Até {@code maxPieces} pedaços de 1 ou 2 caracteres, em parágrafos. */
    public static Arbitrary<String> paragraphs(int maxPieces) {
        return Arbitraries.of(PIECES).list().ofMaxSize(maxPieces).map(pieces -> String.join("", pieces));
    }

}
