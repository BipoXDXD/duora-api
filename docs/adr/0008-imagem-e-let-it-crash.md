# 0008. Imagem por Dockerfile multi-stage e queda só em estado irrecuperável

- **Status:** Aceita
- **Data:** 2026-10-05

## Contexto

O primeiro deploy (Azure Container Apps, plano §6 e §9) precisa de uma imagem por commit,
promovida igual entre ambientes. Não havia Dockerfile nem build de imagem no CI. Junto com a
imagem, é preciso decidir como o processo reage a falhas: o Container Apps reinicia réplicas que
caem ou que falham na liveness, mas a API é um monólito, e uma queda derruba tudo que roda nele.

Alternativas para construir a imagem:

| Opção | Prós | Contras |
|---|---|---|
| Buildpacks (`spring-boot:build-image`, Paketo) | Nada para manter; camadas, usuário não root e ajuste de memória da JVM prontos; SBOM | Builder grande e CI lento; flags de JVM só por variável de ambiente; depende do ritmo do Paketo |
| **Dockerfile multi-stage** | Tudo explícito e revisável em PR; fácil de escanear | Base, camadas e flags de memória ficam por nossa conta; a base precisa de atualização (Dependabot) |
| Jib | Rápido, sem Docker daemon | Mais um plugin; sem ajuste automático de memória |

Alternativas para falhas:

| Opção | Prós | Contras |
|---|---|---|
| **Cair só em estado irrecuperável** | A plataforma repõe a réplica quebrada; erro de uma requisição não afeta as outras | Com uma réplica, cada queda é indisponibilidade de segundos |
| Tratar e seguir sempre (padrão do Spring) | Nenhuma queda provocada | Sem heap ou com uma tarefa de fundo morta, a réplica fica viva e inútil |
| Deixar cair em qualquer erro | Recuperação simples | Num monólito, uma requisição ruim derruba todos os usuários da réplica (Release It! 5.6) |

## Decisão

**Imagem:** `Dockerfile` multi-stage. O estágio de build usa `eclipse-temurin:25-jdk-noble`, e o
final só o JRE (`eclipse-temurin:25-jre-noble`), as duas fixadas por digest. O jar é extraído em
camadas (`-Djarmode=tools extract --layers`), da que menos muda para a que mais muda. O processo
roda como o usuário sem privilégios `10001`. O `ENTRYPOINT` está na forma exec, para a JVM ser o
PID 1 e receber o SIGTERM. A heap fica explícita em `-XX:MaxRAMPercentage=75`.

**Falhas:** o processo cai, ou a liveness vai para `BROKEN`, só em estado irrecuperável. Erro de
requisição é tratado e vira resposta HTTP. Hoje isso significa:

- `-XX:+ExitOnOutOfMemoryError`: sem heap, a JVM sai e a plataforma sobe outra réplica;
- quando existir o relay da outbox ([ADR 0009](0009-outbox-e-eventos.md)), a morte da thread do
  relay põe a liveness em `BROKEN`, com teste que derruba o relay.

O motivo técnico é ter a imagem e o comportamento sob falha explícitos e testados no CI. O motivo
de negócio é que uma réplica viva e inútil (sem heap, ou sem publicar eventos) é pior para quem
está numa sala de jogo do que alguns segundos de reinício.

## Consequências

- O `.dockerignore` é uma allowlist (`pom.xml`, `mvnw`, `.mvn/`, `src/main/`); `livros/`, `target/` e
  arquivos locais nunca entram no contexto do build.
- O Dependabot (`.github/dependabot.yml`) propõe a atualização dos digests toda semana.
- O build da imagem não roda os testes: eles ficam no job de build do CI, e a imagem só é publicada
  depois dos dois.
- No Boot 4, graceful shutdown e probes de liveness e readiness já vêm ligados. O prazo padrão do
  Spring (30 s) é igual ao grace period do Container Apps; no deploy, o do Spring precisa ficar
  abaixo do da plataforma.
- Reinícios frequentes precisam de alerta (um supervisor que reinicia demais esconde o problema).
  Fica para a configuração do monitoramento no deploy.
- O que entra na readiness (o banco ou não) será decidido e registrado na configuração das probes do
  deploy.

## Compliance

- Job `Imagem Docker` do CI: constrói a imagem e roda `infra/docker/smoke-test.sh`, que sobe a API
  com um PostgreSQL descartável e falha se o health não ficar `UP`, se o processo rodar como root
  ou se o SIGTERM não terminar em graceful shutdown dentro do prazo.
