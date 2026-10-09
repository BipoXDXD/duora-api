# 0025. Mutation testing com PIT

- **Status:** Aceita em 2026-10-09 com a opção B (só manual). O workflow semanal (A) fica como degrau
  seguinte, quando o domínio estabilizar.
- **Data:** 2026-10-08
- **Relacionadas:** [ADR 0003](0003-estilo-de-testes.md), [ADR 0012](0012-contrato-openapi.md)
  (o fuzzing semanal de contrato é o precedente de job agendado)

## Contexto

As regras do projeto pedem mutation testing em código crítico, e o Java e Spring 13 exige um spike antes de
adotar PIT porque o Spring Boot 4 usa JUnit 6 e o suporte não estava confirmado. O spike respondeu: **o PIT
funciona neste projeto**, sem configuração especial.

Ambiente do spike: Spring Boot 4.1.1 (JUnit Jupiter 6.0.3), Java 25, Maven, `-Xlint:all` com
`failOnWarning`, `pitest-maven` 1.30.0 e `pitest-junit5-plugin` 1.2.3. O plugin de JUnit 5 do PIT roda sobre a
engine Jupiter 6 sem erro, inclusive `@ParameterizedTest` e `@MethodSource`. O PIT não toca o build padrão
(profile `mutation`, fora do `./mvnw verify`) e o `-Xlint` não incomoda, porque o PIT só usa as classes que o
`test-compile` já gerou. Os `*IT` (Failsafe, Testcontainers) ficam fora: o PIT roda só os `*Test`, sem Docker.

## Medição

Escopo: as classes de domínio puras de `matching`, `connections`, `profiles` e `chat` (25 classes, 265
mutantes), contra os testes rápidos desses pacotes (20 classes de teste).

| | Antes dos testes novos | Depois |
|---|---|---|
| Mutantes mortos | 237 de 265 (89%) | 253 de 265 (95%) |
| Sem cobertura | 7 | 0 |
| Cobertura de linha das classes mutadas | 96% | 98% |
| Tempo da análise (4 threads, máquina de desenvolvimento) | 1 min 51 s | 1 min 25 s |

O tempo total do comando é um pouco maior, de 1 min 35 s a 2 min 17 s, pela compilação. Os 20 testes são
rápidos (o mais lento leva 79 ms), então o custo é quase todo de reexecutar a bateria uma vez por mutante
(68 execuções por mutante em média). O custo cresce com mutantes × testes que cobrem a classe, e não com o
tamanho do projeto: estender o profile a `experiences`, `waitlist` e `trustsafety` é linear. Estender a
código coberto só por `*IT` não é viável sem Docker em cada mutante, então fica fora de escopo.

Score por classe depois dos testes novos (mortos ou timeout / gerados): 18 das 25 classes em 100%;
`MaximumMatching` 48/49; `Profile` 24/25; `RoundPairing` 18/20; `ConnectionPair` 10/11; `ChatPair` 9/10;
`Pair` 6/7; `PriorityMatching` 32/37.

### Os 10 sobreviventes mais interessantes da primeira rodada

| Onde | Mutante | Veredito |
|---|---|---|
| `ChatKey.java:16` | `roundNumber < 1` com a fronteira trocada e negada | **Teste faltando.** Nenhum teste rejeitava a rodada 0. Corrigido (`ChatKeyTest`) |
| `RoundSummary.java:10,16` | Construtor e `of` sem nenhuma cobertura | **Teste faltando.** A classe inteira estava sem teste rápido. Corrigido (`RoundSummaryTest`) |
| `Bio.java:43`, `DisplayName.java:34`, `ChatMessageText.java:47`, `FieldChange.java:48` | `toString` devolve `""` | **Teste fraco.** Os testes só afirmavam `doesNotContain(segredo)`, que `""` satisfaz. Agora afirmam também `contains("redacted")` |
| `Chat.java:66,70` | `id()` e `key()` devolvem `null` | **Teste faltando.** Nenhum teste lia os dois. Corrigido (`ChatTest`) |
| `MaximumMatching.java:120` | Remove `markPath` ao contrair um blossom | **Teste faltando.** O algoritmo de Edmonds não tinha teste próprio e os grafos aleatórios do sorteio não exercitavam os blossoms. Corrigido (`MaximumMatchingTest`: blossoms conhecidos, todos os grafos de 6 vértices e 3000 grafos aleatórios de 10 vértices, contra busca exaustiva) |
| `MaximumMatching.java:84` | Remove `Arrays.fill(inTree, false)` | Morto pelos mesmos testes |
| `PriorityMatching.java:53` | Lambda do grafo auxiliar devolve `false` para dois vértices extras | **Teste faltando.** Corrigido (`PriorityMatchingTest`: cobertura lexicográfica contra enumeração de todos os emparelhamentos) |
| `Profile.java:103` | `version()` devolve `0` | Sobrevive. O campo é do `@Version` do JPA e só se vê num `*IT` (a edição concorrente com `If-Match`). Fora do alcance de um teste rápido |
| `RoundPairing.java:51` | `person < partner[person]` com a fronteira trocada ou negada | **Equivalente.** `Pair.of` normaliza a ordem, então o par sai igual de qualquer ponta |
| `Pair.java:34`, `ConnectionPair.java:43`, `ChatPair.java:35` | `compareTo(...) < 0` vira `<= 0` | **Equivalente.** O caso de igualdade (mesma conta duas vezes) é rejeitado antes pelo construtor |

Sobreviventes que restam (12 de 265), nenhum indicando teste faltando de verdade: os 5 de `RoundPairing`/`Pair`/
`ConnectionPair`/`ChatPair` acima (equivalentes), `Profile.version` (só em `*IT`), `MaximumMatching.java:121`
(a segunda chamada simétrica a `markPath`; só o blossom aninhado a distingue e 3000 grafos de 10 vértices não
a pegaram) e 5 de `PriorityMatching.java:46,47,78,79,82`, todos sobre quantos vértices extras o grafo auxiliar
leva. Vértices a mais só custam tempo; faltar um muda o resultado, mas nenhum grafo de até 9 vértices
conseguiu provocar isso. Não vale perseguir.

## Alternativas

| Opção | Prós | Contras |
|---|---|---|
| **A. Workflow semanal no CI** (como o fuzzing de contrato), sobre o domínio puro | Pega teste fraco que a cobertura de linha não vê; zero minutos no PR; o relatório HTML fica como artefato; o score vira número acompanhado | Estimativa de 3 a 4 minutos de runner por semana, com setup do JDK e do cache (sobre os pacotes de hoje); precisa de um limiar (`mutationThreshold`) para falhar, e limiar sobre equivalentes pede manutenção; mais um workflow para manter |
| **B. Só manual**, pelo profile, antes de mexer em domínio crítico | Custo zero de CI e de manutenção; já funciona hoje; o spike achou 6 lacunas reais numa rodada (`ChatKey`, `RoundSummary`, `Chat`, o `toString` de quatro tipos, `MaximumMatching` e `PriorityMatching`), então o uso pontual já rende | Esquecível, sem histórico; a lista de pacotes no `pom.xml` envelhece |
| **C. Não adotar** | Nenhum custo | Perde um sinal que a cobertura de linha não dá: 7 mutantes sem cobertura e 4 `toString` com teste fraco estavam num domínio com 96% de cobertura de linha |

Não considerei PIT no PR: o runner compartilhado e o tempo por commit não compensam num projeto solo.

## Decisão

O usuário escolheu a opção B em 2026-10-09. A análise que levou à recomendação segue abaixo.

### Recomendação

**Opção B agora, com a A como degrau seguinte.** Mantém o profile (já commitado, fora do build padrão) e usa
só quando o domínio crítico muda: sorteio, conexões, chat e perfil. O spike mostrou que o custo é baixo (menos
de 2 minutos) e que o retorno existe (6 lacunas de teste), mas o projeto ainda muda rápido e
um workflow semanal com limiar nasceria cheio de equivalentes para triar. Quando o domínio estabilizar, ligar o
workflow semanal com `mutationThreshold` um pouco abaixo do score atual (95%, por exemplo 92%), sem exigir 100%.

**Bottom line:** adote o PIT como ferramenta manual (B), porque funciona com o JUnit 6 e achou lacunas reais
por menos de 2 minutos; o custo principal é a disciplina de lembrar de rodar, e a lista de pacotes do
`pom.xml` pede revisão a cada módulo novo.

## Consequências

- O profile `mutation` fica no `pom.xml`: `pitest-maven` 1.30.0 e `pitest-junit5-plugin` 1.2.3 só nele,
  em propriedades do pom. O Dependabot do repositório não cobre Maven, então as versões são revisadas à mão.
- O `./mvnw clean verify` não roda PIT e não ganhou dependência.
- Um módulo de domínio novo entra na lista `targetClasses` e `targetTests` do profile.
- Se o PIT passar a falhar com um JUnit mais novo, a saída é fixar o `junit-platform` do profile ou abrir uma
  issue no `pitest-junit5-plugin`; o spike não achou esse problema com o JUnit 6.0.3.

## Compliance

Como rodar: seção "Mutation testing" do README. Nenhuma fitness function; o profile é opt-in e não protege
nenhuma fronteira de arquitetura.
