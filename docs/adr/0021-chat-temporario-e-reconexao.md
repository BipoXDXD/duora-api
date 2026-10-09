# 0021. Chat temporário da rodada, transporte de tempo real e reconexão

- **Status:** Aceita em 2026-10-08 pelo usuário: transporte (d) polling e depois (b) SSE, e os defaults de produto da seção 1. Os demais itens de "Pendente com o usuário" seguem abertos. Fatias 1 a 4 implementadas na API em 2026-10-08 (ver "Implementação"); o polling do `duora-web` e as fatias 5 a 7 seguem pendentes.
- **Data:** 2026-10-08
- **Relacionadas:** [ADR 0002](0002-front-web-com-bff.md) (BFF), [ADR 0005](0005-contrato-da-api.md),
  [ADR 0006](0006-rate-limit-no-postgresql.md), [ADR 0007](0007-estilo-por-modulo.md),
  [ADR 0009](0009-outbox-e-eventos.md) (outbox), [ADR 0013](0013-logs-estruturados-e-correlation-id.md),
  [ADR 0014](0014-infraestrutura-do-piloto-na-azure.md) (Azure for Students), [ADR 0015](0015-bloqueio-e-denuncia.md),
  [ADR 0016](0016-eventos-e-inscricoes.md), [ADR 0017](0017-pareamento.md), [ADR 0019](0019-decisao-privada-e-conexoes.md)
- **Pode substituir em parte:** a escolha do Web PubSub como transporte na [ADR 0009](0009-outbox-e-eventos.md)
  e no plano (§1, §3, §6), conforme a decisão 2 abaixo.

## Contexto

A etapa 2 do plano (§9) é "evento, pareamento, um jogo, **chat temporário**, **reconexão** e decisão
privada". Já existem eventos e inscrições ([ADR 0016](0016-eventos-e-inscricoes.md)), rodadas e pares
([ADR 0017](0017-pareamento.md), `matching.Pairings.partnerOf`) e a decisão privada com conexões
([ADR 0019](0019-decisao-privada-e-conexoes.md)). A conversa **persistente** entre pessoas conectadas é da
etapa 3 e fica fora desta ADR.

O que o plano já fixa:

- §3, fluxo de uma ação: "API valida usuário, participação e estado → transação salva alteração + evento
  na outbox → publicador envia atualização → cliente reconcilia". O PostgreSQL guarda a verdade; o canal
  de tempo real "transporta notificações, não decide resultados".
- §3, reconexão: "recuperar estado autorizado pela API; eventos têm identificador e versão. Ignorar
  duplicados e buscar novamente quando houver lacunas."
- §4: "paginação por cursor no chat"; tabela `messages`; nada de ordenar por timestamp entre máquinas.
- §7: "Ao bloquear, impedir novos envios e revogar acesso às conexões/canais afetados"; "não aceitar HTML,
  SVG ou anexos no chat"; "não enviar conversas a logs/analytics".
- §8, caso prioritário: "**bloqueio durante conversa impede novos envios**"; "reconexão preserva o
  progresso"; k6 com 50 salas e 100 participantes.
- [ADR 0009](0009-outbox-e-eventos.md) (aceita, sem código): outbox próprio com relay `SKIP LOCKED`, e
  eventos de tempo real **por chave** (`type`, `sessionId`, `version`, id do evento), sem dado privado.
- [ADR 0014](0014-infraestrutura-do-piloto-na-azure.md): Azure for Students com **US$ 100 de crédito**,
  North Central US, **só homologação**; API em Container Apps (Consumption, 0,5 vCPU e 1 GiB, de 0 a 1
  réplica em homologação; produção prevista com 1 a 3 réplicas). Web PubSub ficou fora da etapa 1.

O módulo novo é `chat`, que o plano (§3) junta a `connections`. Proponho módulo próprio, **core**
([ADR 0007](0007-estilo-por-modulo.md)): regras de abertura, fechamento, bloqueio e ordem são o centro da
experiência, e o `connections` já tem uma responsabilidade clara. O `chat` depende de `matching.Pairings`,
`events.EventRoster` e `trustsafety.Blocking` pelas APIs publicadas, nunca pelas tabelas.

## 1. O que é o chat temporário (produto)

### Perguntas de produto (pendentes com o usuário)

1. **Quem conversa?** Só o par de uma rodada (proposto), ou também quem ficou de fora com alguém da equipe?
2. **Quando abre?** Assim que o sorteio forma o par (proposto), ou só quando o jogo da rodada começa?
3. **Quando fecha para envio?** Ao começar a rodada seguinte ou ao acabar o evento (proposto, porque a
   rodada ainda não tem duração, [ADR 0017](0017-pareamento.md) pendência 4); com duração fixa (10 min?);
   ou só depois que os dois decidirem ([ADR 0019](0019-decisao-privada-e-conexoes.md))?
4. **Depois de fechado, ainda dá para reler?** Proposto: sim, só leitura, até o expurgo.
5. **Quando o conteúdo é apagado?** Proposto: **24 h depois do fim do evento**. Alternativas: no fim do
   evento; quando os dois decidirem; nunca (contra a minimização da LGPD).
6. **Se formarem conexão (interesse mútuo), o histórico passa para a conversa persistente da etapa 3?**
   Proposto: não; a conversa persistente começa vazia. Levar o histórico muda a retenção.
7. **Bloqueio no meio da conversa:** o chat fecha para os dois (proposto). Quem bloqueou ainda relê o
   histórico para denunciar? Proposto: sim, até o expurgo; quem foi bloqueado vê "conversa encerrada",
   igual ao fechamento normal.
8. **Formato:** só texto, até 500 caracteres (proposto). Emojis sim (são texto). Links aparecem como texto,
   sem virar âncora nem prévia. Sem foto, áudio, anexo, "digitando..." ou confirmação de leitura no piloto.
9. **Denúncia de mensagem:** a pessoa denuncia uma mensagem específica (proposto), e a denúncia guarda uma
   cópia da mensagem como evidência, que sobrevive ao expurgo do chat.
10. **"Sua dupla saiu":** o produto ainda não define "sair" de uma rodada (a inscrição não muda depois do
    início, [ADR 0016](0016-eventos-e-inscricoes.md)). Sair é fechar o app? Um botão "encerrar conversa"?

### Default mínimo proposto (provisório, marcado como pendência)

- Um chat por **(evento, rodada, par)**. Existe para os dois assentos que o sorteio juntou; quem ficou de
  fora não tem chat.
- **Aberto** enquanto: o evento está em andamento (`EventRoster`), a rodada é a última do evento (ainda
  não existe a N+1) e não há bloqueio entre os dois (`Blocking.existsBetween`). O estado é **calculado**, não
  guardado, como o "em andamento" do evento ([ADR 0016](0016-eventos-e-inscricoes.md)): nenhum job fecha
  chats e nada fica desatualizado.
- **Fechado:** só leitura. A resposta do envio é a mesma para os três motivos (rodada passou, evento acabou,
  bloqueio), para não revelar o bloqueio ([ADR 0015](0015-bloqueio-e-denuncia.md)).
- **Expurgo:** mensagens apagadas de fato 24 h depois do fim **agendado** do evento (o horário não muda,
  porque o evento não tem edição). Evento cancelado expurga no mesmo prazo: no máximo um dia a mais.
- Texto de 1 a 500 code points, normalizado em NFC e com `strip`, com as mesmas proibições da bio
  (controle, NUL, invisíveis, controles de direção). No máximo **300 mensagens por chat**.

## 2. Transporte em tempo real

O transporte só leva **avisos por chave** ([ADR 0009](0009-outbox-e-eventos.md)): "o chat X tem a versão
N". O conteúdo sempre vem de um `GET` na API, que reautoriza a cada leitura. Por isso o transporte é
substituível e o protocolo de reconexão (seção 4) é o mesmo nos quatro casos; a escolha muda latência,
custo, operação e testes, não a segurança dos dados.

### Fatos da plataforma que pesam na escolha

Consultados em **2026-10-08**:

- **Ingress do Container Apps:** HTTP/1.1, HTTP/2 e WebSocket suportados; "Request time out is 240
  seconds" no ingress padrão, sem configuração. O modo premium tem "idle request timeout" de 4 a 30 min, mas
  exige perfil Dedicated (fora do orçamento). Consequência: stream SSE ou long polling precisam terminar e
  reabrir em menos de 240 s, e uma conexão WebSocket precisa de heartbeat; o comportamento exato
  (timeout total ou ocioso) **precisa ser medido na homologação**.
- **Cobrança do Container Apps:** a réplica só é cobrada pela taxa reduzida de ociosa se "isn't processing
  any HTTP requests" e recebe menos de 1.000 bytes/s. **Um stream SSE, um long poll ou um WebSocket aberto
  contra a API mantêm a réplica na taxa ativa** e impedem a escala a zero da homologação enquanto houver
  aba aberta. Polling curto também mantém a réplica ativa durante o evento, mas a deixa voltar a zero.
- **Preços no North Central US** (Azure Retail Prices API, varejo, US$):
  - Container Apps Consumption: vCPU ativa US$ 0,000024/s, ociosa US$ 0,000003/s; memória
    US$ 0,000003/GiB-s; requisições US$ 0,40 por milhão; cota grátis mensal de 180 mil vCPU-s,
    360 mil GiB-s e 2 milhões de requisições por assinatura. A réplica de homologação (0,5 vCPU, 1 GiB)
    ativa custa **US$ 0,054/h**, e a cota grátis cobre cerca de **100 h ativas por mês**.
  - Web PubSub: **Free** (US$ 0; 20 conexões simultâneas e 20 mil mensagens por dia; 1 unidade);
    **Standard** US$ 1,61 por unidade por dia (**≈ US$ 49/mês**; 1.000 conexões e 1 milhão de mensagens
    por unidade por dia, mensagem contada em blocos de 2 KB de tráfego de saída) e US$ 1,00 por milhão de
    mensagens extras. Trocar Free ↔ Standard troca o IP público do serviço, com **30 a 60 min de
    indisponibilidade** segundo a documentação.
- **Token do Web PubSub:** o navegador abre `wss://<nome>.webpubsub.azure.com/client/hubs/<hub>?access_token=<JWT>`;
  a API `WebSocket` do navegador não envia header, então o token vai **na URL** (o padrão é valer 60 min,
  configurável). As permissões do cliente vêm dos `roles` do token; sem role, o cliente não entra nem
  publica em grupo nenhum, e o servidor gerencia grupos e fecha conexões pela API REST.

### Opções

| Opção | Prós | Contras |
|---|---|---|
| **(a) Azure Web PubSub** (o plano cita) | Conexões fora das réplicas da API: a API continua stateless, escala a zero e faz graceful shutdown sem streams presos. Fan-out entre réplicas resolvido pelo serviço. Grupos, revogação (`removeUserFromAllGroups`, `closeUserConnections`) e SDK JS com reconexão prontos. Escala até 1.000 conexões por unidade sem tocar na API | **Custo:** Free só tem 20 conexões (não comporta o k6 de 100 participantes nem um evento real); Standard ≈ US$ 49/mês, **metade do crédito por mês** (ou US$ 1,61 por dia ligado, com 30–60 min fora do ar a cada troca de tier). Token **na URL** (contraria "credencial nunca na URL"; mitigação: validade de minutos, sem roles, só `userId`). Origem diferente da API: o cookie `__Host-` do BFF não serve, entra um endpoint de negociação, `connect-src` no CSP e uma dependência externa com timeout, retry e circuit breaker. Publicar exige a outbox da ADR 0009 (chamada externa não entra na transação). Sem emulador local: testes com adapter falso e um teste manual contra o serviço. Recurso novo no Bicep e papel `Web PubSub Service Owner` para a identidade da API. Dados passam por mais um operador (LGPD), embora só chaves |
| **(b) SSE no próprio Spring** (`SseEmitter`, `GET /api/me/stream`) | Mesma origem: o cookie de sessão vai sozinho, sem token na URL e sem CORS. `GET` não muda estado, então dispensa CSRF; `SameSite=Lax` não envia o cookie num `EventSource` de outro site. Sem recurso novo: **US$ 0 a mais**, só a réplica ativa enquanto houver aba aberta (US$ 0,054/h em homologação, dentro da cota). `EventSource` reconecta sozinho e manda `Last-Event-ID`. Unidirecional basta: o plano manda as ações pela API. Testável com Testcontainers e um cliente HTTP real | Conexões abertas na réplica: o stream precisa fechar em < 240 s (o cliente reabre), heartbeat, limite de streams por conta e **fechar todos no shutdown** dentro dos 20 s ([ADR 0014](0014-infraestrutura-do-piloto-na-azure.md)). **Fan-out entre réplicas:** o aviso nasce na réplica que gravou e o stream pode estar em outra; a solução proposta é `NOTIFY` do PostgreSQL na mesma transação e um `LISTEN` por réplica (uma conexão fora do pool; o B1ms tem ~50). Impede escala a zero com aba aberta. Cada reabertura toca a sessão (decidir se o stream renova os 30 min de inatividade). Navegador em HTTP/1.1 limita 6 conexões por origem (o ingress fala HTTP/2) |
| **(c) WebSocket no Spring** (STOMP ou puro) | Bidirecional; STOMP traz destinos e assinaturas prontos; mesma origem | O bidirecional não é usado (envio vai pela API). O *simple broker* do STOMP vive em memória e não cruza réplicas: precisa de broker relay (RabbitMQ ou ActiveMQ, serviço novo para operar e pagar) ou do mesmo `NOTIFY`. CSRF no handshake e no `CONNECT` (Spring Security de mensagens) e checagem de `Origin` contra Cross-Site WebSocket Hijacking. Mesmos problemas de conexão longa de (b), com testes mais difíceis (cliente STOMP) |
| **(d) Polling curto** (e long polling como variante) | O mais simples: só `GET` com cursor, que a reconexão já exige. Nada de estado na réplica, fan-out, shutdown especial ou timeout de ingress; escala a zero entre eventos. Testável com MockMvc. Custo: 100 participantes a cada 2 s num evento de 3 h = **540 mil requisições**, dentro dos 2 milhões grátis por mês (cerca de 3 eventos/mês; depois US$ 0,40 por milhão) | Latência de até um intervalo (2 s com a aba visível). Carga no banco: ~50 consultas/s pela PK com 100 participantes, a medir no k6 com o B1ms. Bateria e dados móveis (mitigação: só com a aba visível, intervalo maior em segundo plano). Long polling reduz a latência mas volta a precisar de fan-out entre réplicas e do limite de 240 s, perdendo a simplicidade |

### Efeito no BFF ([ADR 0002](0002-front-web-com-bff.md))

- (b) e (d) passam pela mesma cadeia de sessão de qualquer `GET`: cookie `__Host-DUORA_SESSION`, sem CORS,
  `DenyByDefaultIT` cobre a rota. Envio por `POST` com `X-XSRF-TOKEN`, como as demais mutações.
- (c) exige CSRF no handshake e checagem de `Origin`.
- (a) exige `POST /api/realtime/negotiate` (com CSRF, porque emite credencial) devolvendo a URL com token
  de vida curta; o cookie não chega ao Web PubSub. Logout e bloqueio precisam fechar as conexões no serviço.

### Escala entre réplicas

- (a) resolvido pelo serviço. (d) resolvido pelo banco (cada `GET` lê a verdade).
- (b) e (c): `NOTIFY` transacional + `LISTEN` em cada réplica. O `NOTIFY` só é entregue se a transação
  confirmar, então não há dual write para o aviso. É at-most-once para quem está ouvindo, o que basta para
  um **aviso**: quem perdeu recupera pelo cursor na reconexão. Riscos: o `NOTIFY` serializa os commits que
  notificam (irrelevante no volume do piloto) e uma réplica que escuta sem consumir enche a fila (8 GB) e
  faz os commits falharem; a thread do `LISTEN` segue o let it crash da [ADR 0008](0008-imagem-e-let-it-crash.md).
  Sem PgBouncer em modo transação no caminho (não há, [ADR 0014](0014-infraestrutura-do-piloto-na-azure.md)).
- Sticky sessions não são necessárias em nenhuma opção.

### Complexidade de teste

| Opção | O que precisa |
|---|---|
| (a) | Adapter falso no IT; teste do token (validade, sem roles); um roteiro manual contra o serviço real; outbox com seus 6 testes da ADR 0009 |
| (b) | IT com servidor real (`RANDOM_PORT`) lendo o stream; `NOTIFY` em commit e não em rollback; fan-out com dois listeners; shutdown fecha os streams; reabertura antes de 240 s |
| (c) | Tudo de (b) mais cliente STOMP, CSRF no `CONNECT` e `Origin` |
| (d) | MockMvc, como as demais rotas |

## 3. Persistência e privacidade

### Modelo de dados (proposto)

- `chat (id uuid default uuidv7(), event_id, round_number, first_account_id, second_account_id,
  last_seq int not null default 0, purge_after timestamptz not null, created_at)`; chave natural
  `UNIQUE (event_id, round_number, first_account_id, second_account_id)` e `CHECK (first_account_id <
  second_account_id)`, como a `connection` (que ninguém esteja em dois pares na rodada já é garantido pelo
  `round_seat`, e só quem `Pairings.partnerOf` confirma cria o chat). FKs para `account`
  (`on delete restrict`, como as demais) com índice; sem FK para `round_seat` (a [ADR 0019](0019-decisao-privada-e-conexoes.md)
  explica). Criado **na primeira mensagem ou leitura** (`insert ... on conflict do nothing`), depois de
  `Pairings.partnerOf` confirmar o par; nenhum acoplamento com a criação da rodada.
- `chat_message (chat_id, seq, sender_account_id, body text, idempotency_key uuid, body_fingerprint bytea,
  sent_at)`, PK `(chat_id, seq)`; `UNIQUE (chat_id, sender_account_id, idempotency_key)`; `CHECK` de
  tamanho do texto e `seq` de 1 a 300. `on delete cascade` a partir de `chat` (é o mesmo agregado).
- Volume projetado (pedido da [ADR 0009](0009-outbox-e-eventos.md)): 50 chats × no máximo 300 mensagens =
  15 mil linhas por evento, apagadas em 24 h. Particionar não se aplica no piloto; o limiar fica em 10
  milhões de linhas vivas.
- Adapter com `JdbcClient` e SQL direto, como `trustsafety` e `connections`.

### Retenção, exclusão e LGPD

- Mensagem de app de encontros pode revelar orientação ou vida sexual, **dado pessoal sensível** (LGPD,
  art. 5º, II, e art. 11). Fica no PostgreSQL nos EUA (transferência internacional já registrada na
  [ADR 0014](0014-infraestrutura-do-piloto-na-azure.md)); base legal a definir com apoio jurídico.
- **Expurgo:** job agendado em todas as réplicas, apagando chats com `purge_after < now()` em lotes com
  `FOR UPDATE SKIP LOCKED` (idempotente, sem lock entre réplicas além disso), horário com jitter e métrica
  de chats vencidos ainda não apagados. `delete` de verdade, não `deleted=true` (plano §4).
- **Backups:** mensagem apagada continua nos backups do PostgreSQL até o fim da retenção (7 dias em
  homologação, 14 em produção). Vai para a política de privacidade.
- **Exclusão de conta:** apaga os chats da pessoa antes da conta (as FKs `restrict` obrigam).
- Nenhum IP, user agent ou localização por mensagem. Sem "lida" nem "digitando".

### Moderação e denúncia de mensagem

- `POST /api/events/{eventId}/rounds/{number}/chat/messages/{seq}:report` com `{reason, description}`: o
  `chat` confere que quem denuncia é do par e que a mensagem é **do outro**, e chama uma API publicada nova
  do `trustsafety` (`Reports.fileWithEvidence`), que grava a denúncia e uma **cópia** do texto, do `seq` e
  da hora como evidência. A dependência vai de `chat` para `trustsafety` (que já é usado por `Blocking`),
  sem ciclo. A cota de denúncias é a da [ADR 0015](0015-bloqueio-e-denuncia.md) (10 por dia).
- A retenção da evidência segue a pendência 2 da [ADR 0015](0015-bloqueio-e-denuncia.md); o expurgo do
  chat não a apaga.

### Bloqueio durante a conversa (caso prioritário do plano)

- O envio, na mesma transação: trava a linha do `chat` (`select ... for update`), consulta
  `Blocking.existsBetween` e o estado aberto, e só então grava. Bloqueio já confirmado → recusado.
- **Resíduo:** um bloqueio confirmado **durante** a transação de envio (READ COMMITTED) não é visto, como
  nas [ADRs 0017](0017-pareamento.md) e [0019](0019-decisao-privada-e-conexoes.md). A janela é de
  milissegundos. Fechá-la exigiria um advisory lock do par compartilhado entre `trustsafety` e `chat`, ou
  `SERIALIZABLE` com retry; proposto aceitar e registrar.
- Revogação do canal: com avisos por chave, um aviso que escape não mostra nada, porque a leitura
  reautoriza. Em (a), o bloqueio ainda remove os dois do grupo do chat (defesa em profundidade); em (b), o
  stream deixa de receber avisos daquele chat.

### Limites e rate limit

- Texto de 1 a 500 code points; 300 mensagens por chat (a 301ª recebe `409`, igual a chat fechado).
- **Envio:** 20 mensagens por minuto por conta, repostas aos poucos (uma a cada 3 s), num bucket
  `chat:<conta>` da `rate_limit_bucket` ([ADR 0006](0006-rate-limit-no-postgresql.md)); `429` com
  `Retry-After`; banco indisponível → `503` (falha fechada). A repetição com a mesma `Idempotency-Key`
  também gasta.
- **Leitura:** sem rate limit no início (consulta pela PK; um bucket por `GET` dobraria as escritas do
  polling); medir no k6 e rever. `maxPageSize` de 1 a 100.
- (b): no máximo 3 streams por conta e por réplica; o quarto recebe `429`.

### Sem PII em logs

`toString` redigido no VO do texto e nos DTOs; nada de corpo no DEBUG do Spring MVC; canário no
`SensitiveDataLoggingIT` ([ADR 0013](0013-logs-estruturados-e-correlation-id.md)); o aviso de tempo real
não leva texto. Em (a), sem logs de mensagem do serviço ligados.

## 4. Reconexão

### Contrato proposto

- `GET /api/events/{eventId}/rounds/{number}/chat` → `{chatId, open, lastSeq}`. `404` igual para quem não
  está no par, rodada inexistente e evento inexistente (como o pareamento). O `chatId` é opaco e serve só
  para casar avisos com a tela.
- `GET .../chat/messages?afterSeq=&maxPageSize=` → `{items: [{seq, fromMe, text, sentAt}], nextAfterSeq}`,
  em ordem crescente. `afterSeq` é um cursor **transparente**, exceção consciente ao `pageToken` opaco da
  [ADR 0005](0005-contrato-da-api.md): a sequência é a versão do chat, o cliente precisa dela para detectar
  lacunas, e não autoriza nada (a consulta filtra pelo chat de quem chama). `fromMe` no lugar do id do
  remetente: não há terceiro no chat.
- `POST .../chat/messages` com header `Idempotency-Key` (UUID, obrigatório) e `{text}` → `201` + `Location`
  com a mensagem; mesma chave e mesmo texto → `200` com a **mesma** mensagem; mesma chave e texto diferente
  → `409`; chat fechado → `409`. Corpo estrito (chave desconhecida, `seq`, `sentAt`, remetente → `400`).

### Ordem pela sequência do banco, nunca por timestamp

| Opção | Prós | Contras |
|---|---|---|
| **`last_seq` por chat, incrementado com a linha do chat travada na transação de envio** | A ordem de `seq` é a ordem de commit dentro do chat; sem lacuna (rollback desfaz o incremento), então lacuna no cliente é sempre "perdi algo"; dois envios do mesmo chat se serializam por milissegundos | Uma escrita a mais por mensagem; serializa só o próprio chat (duas pessoas) |
| `bigint generated always as identity` global | Nada a travar | Valor alocado antes do commit: a mensagem 10 pode confirmar depois da 11, e quem leu `afterSeq=11` nunca vê a 10. Lacunas normais (rollback) não distinguem perda |
| `sent_at` | Natural | Proibido pela regra do projeto: empate e relógio não ordenam commits |

**Proposta:** `last_seq` por chat. Com o lock, a idempotência também fica simples: sob o lock, procurar a
chave antes de incrementar é seguro (o lock cobre o chat inteiro), e o `UNIQUE` segura o resto.

### Protocolo do cliente (igual nos quatro transportes)

1. Ao abrir a tela, ao reconectar o transporte, ao voltar a aba para frente e ao receber `409` no envio:
   `GET .../chat` e `GET .../messages?afterSeq=<maior seq visto>` até `nextAfterSeq` nulo.
2. Aviso `{id, type: "chat.message.created", sessionId: chatId, version: seq}`: se `version <=` maior seq
   visto, ignora (duplicado); senão, faz o passo 1. Lacuna e aviso novo levam ao mesmo `GET`: não há caso
   especial.
3. Envio: o cliente gera a `Idempotency-Key` ao criar o rascunho e a reutiliza em todo reenvio
   (rede caiu, timeout, aba recarregada com rascunho pendente). A mensagem aparece como "enviando" até o
   `201`/`200`; o `seq` da resposta a posiciona.
4. At-least-once em todo o caminho: avisos podem repetir ou se perder; o cursor resolve os dois.

## 5. Outbox e notificações

- **(d) polling:** o chat não precisa de aviso; nada de outbox na primeira fatia.
- **(b) SSE:** o aviso do chat sai por `NOTIFY` na transação de envio; não precisa de outbox, porque a
  entrega é interna e o estado é recuperável pelo cursor. Isso **diverge** da [ADR 0009](0009-outbox-e-eventos.md),
  que manda todo aviso de tempo real pela outbox: precisa de decisão (pendência 3). A outbox continua para
  entregas **externas ou que não podem se perder** (e-mail, push, Web PubSub).
- **(a) Web PubSub:** outbox obrigatória, como na [ADR 0009](0009-outbox-e-eventos.md), com revalidação dos
  destinatários (bloqueio posterior) antes de publicar.
- **Notificações de domínio** no mesmo canal, todas por chave e com o estado lido na API:
  - `connection.formed` para os dois, quando o segundo "sim" cria a conexão
    ([ADR 0019](0019-decisao-privada-e-conexoes.md) pendência 5). Só nesse caso: um aviso a quem decidiu
    "sim" sem conexão vazaria a decisão do outro. O cliente rebusca `GET /api/me/connections`.
  - `event.cancelled` para os inscritos, que rebuscam o evento. A lista de destinatários vem da API
    publicada do `events` na hora de publicar.
  - `pairing.ended` ("sua dupla saiu") só depois da pergunta de produto 10.
  - Por e-mail (Communication Services), se o produto pedir: sempre pela outbox.

## 6. STRIDE inicial

Dado sensível: conteúdo de conversa íntima entre duas pessoas, e quem conversa com quem. Os testes das
linhas de envio e leitura existem desde as fatias 1 e 2, e os de denúncia e expurgo desde as fatias 3 e 4 (lista
em "Implementação"); os de stream, Web PubSub e avisos são das fatias 5 e 6:

| Ameaça | Mitigação | Teste a escrever |
|---|---|---|
| Spoofing: CSRF envia mensagem pela sessão web | Token CSRF em todo `POST` | `ChatIT.aWebSessionWithoutCsrfTokenCannotSend` |
| Spoofing: outro site abre o stream (b) ou um WebSocket (c) com o cookie da vítima | `SameSite=Lax`; `Origin` na allowlist no stream e no handshake | `ChatStreamIT.aStreamOpenedFromAnotherOriginIsRefused` |
| Spoofing: token do Web PubSub vazado pela URL é reusado (a) | Validade de 5 min, sem roles, `userId` = conta; grupos só pelo servidor | `WebPubSubTokenTest.tokenHasNoRolesAndExpiresInFiveMinutes` |
| Tampering: corpo com `seq`, `sentAt`, remetente ou chat | DTO estrito; servidor define tudo | `ChatFieldErrorsIT.unknownFieldsAreRejectedWithoutWriting` |
| Tampering: reenvio duplica a mensagem | `Idempotency-Key` por (remetente, chave) + fingerprint | `ChatIT.aRetriedSendWithTheSameKeyRecordsOneMessage`, `sameKeyWithAnotherTextIsAConflict`, `concurrentSendsWithTheSameKeyRecordOne`, `anotherAccountWithTheSameKeyGetsItsOwnMessage` |
| Tampering: envios simultâneos perdem mensagem no cursor | `last_seq` sob lock do chat | `ChatIT.concurrentSendsGetConsecutiveSequenceNumbers`, `aReaderAfterEachCommitNeverSkipsAMessage`; `ChatSchemaIT.aSequenceNumberIsUniqueInTheChat` |
| Repudiation: quem disse o quê, depois do expurgo | Remetente e hora gravados pelo servidor; cópia na denúncia, sem FK para o chat | `ChatReportIT.theReportKeepsTheMessageAfterThePurge` |
| Information disclosure: terceiro lê ou escreve no chat (BOLA) | Rota sem id de pessoa; par por `Pairings.partnerOf`; `404` igual | `ChatIT.someoneOutsideThePairCannotReadNorSend` (corpo sem texto) |
| Tampering: denunciar a própria mensagem ou a de outro chat | Só mensagem do outro, no chat de quem denuncia | `ChatReportIT.onlyThePartnersMessageCanBeReported` |
| Spoofing: CSRF denuncia pela sessão web | Token CSRF em todo `POST` | `ChatReportIT.aWebSessionWithoutCsrfTokenCannotReport` |
| Tampering: corpo da denúncia com conta, mensagem ou estado | DTO estrito (`reason`, `description`); o resto vem da rota e do servidor | `ChatReportIT.invalidReportIsRejectedWithoutWriting` |
| Denial of service: denúncias em massa pela rota do chat | Mesma cota de `POST /api/reports` (10/dia por conta), entre réplicas; `429` + `Retry-After` | `ChatReportIT.theReportQuotaIsSharedWithAccountReports` |
| Denial of service: expurgo segura locks ou cresce sem limite | Um comando por lote, `batchSize × maxBatchesPerRun` por execução, configuração validada na subida; métrica do que falta | `ChatPurgeIT.aRunStopsAtItsBatchLimit`, `theBacklogMetricCountsExpiredChats`, `RequiredChatPurgeSettingsIT` |
| Information disclosure: texto no log | `toString` redigido; canário | `SensitiveDataLoggingIT.chatMessageNeverReachesTheLog`, `ChatMessageTextTest.doesNotExposeTheTextInToString`, `ChatReportIT.theReportedMessageNeverReachesTheLog`, `ChatMessageEvidenceTest.doesNotExposeTheTextInToString` |
| Information disclosure: canal de tempo real leva conteúdo | Aviso só por chave, conjunto de chaves exato | `ChatHintTest.aHintCarriesOnlyIdTypeSessionIdAndVersion` |
| Information disclosure: aviso entregue a quem não é do chat (fan-out) | Stream autorizado na abertura; filtro por chat de quem chama | `ChatStreamIT.aStreamReceivesOnlyHintsOfTheCallersChats` |
| Information disclosure: aviso revela decisão alheia | `connection.formed` só quando a conexão existe | `ConnectionHintIT.aYesWithoutConnectionSendsNoHint` |
| Information disclosure: o bloqueado descobre o bloqueio | Chat fechado responde igual por qualquer motivo | `ChatIT.aBlockedChatLooksLikeARoundThatEnded` |
| Information disclosure: conversa guardada além do prazo | Expurgo com `delete` real, em lotes com `skip locked` em todas as réplicas | `ChatPurgeIT.messagesArePurgedAfterTheRetention`, `twoReplicasPurgeWithoutConflict`, `aChatLockedByAnotherReplicaIsSkippedWithoutWaiting`, `aRunStopsAtItsBatchLimit` |
| Denial of service: rajada de mensagens | 20/min por conta entre réplicas; `429` + `Retry-After`; falha fechada | `ChatRateLimitIT.sendsAboveTheLimitAreRejectedWithRetryAfterAndWithoutWriting`, `sendIsRefusedWhenTheLimitCannotBeCounted` |
| Denial of service: texto grande ou inválido vira `500` | 1 a 500 code points, NFC, caracteres proibidos → `400` | `ChatMessageTextTest`, `ChatIT.invalidTextIsRejectedWithoutWriting` (borda 500/501, `\u0000`, invisíveis) |
| Denial of service: chat cresce sem limite | 300 mensagens por chat | `ChatIT.aFullChatRefusesNewMessages` |
| Denial of service: streams presos esgotam a réplica ou travam o deploy (b) | 3 streams por conta; stream fecha antes de 240 s; streams fechados no shutdown | `ChatStreamIT.aFourthStreamIsRefused`, `streamsCloseBeforeTheIngressTimeout`, `streamsAreClosedOnShutdownWithinTheGracePeriod` |
| Elevation of privilege: envio depois do bloqueio | `Blocking.existsBetween` na transação, sob o lock do chat | `ChatIT.aBlockEitherWayStopsNewMessages` |
| Elevation of privilege: envio depois da rodada ou do evento | Estado calculado na transação | `ChatIT.aChatClosesWhenTheNextRoundStarts`, `aChatClosesWhenTheEventEnds` |
| Elevation of privilege: rota nova pública por engano | Negar por padrão | `DenyByDefaultIT` (rotas novas na enumeração) |

## 7. Recomendação

**Bottom line.** Recomendo **(d) polling curto agora e (b) SSE com `NOTIFY` como segundo passo**, deixando
o Web PubSub para quando o k6 ou um evento real mostrarem conexões ou latência que a API não sustenta.

- **Motivo:** o protocolo de reconexão (cursor `afterSeq`, sequência por chat, `Idempotency-Key`) é
  obrigatório em qualquer opção e já entrega o chat funcionando por polling, sem infraestrutura nova, sem
  outbox e testável com MockMvc. O SSE depois só acrescenta um aviso "busque de novo", na mesma origem do
  BFF, sem token na URL e sem custo de serviço. O Web PubSub Standard consumiria cerca de metade do crédito
  de US$ 100 por mês, e o Free (20 conexões) não sustenta nem o teste de carga do plano.
- **Custo principal:** latência de até 2 s no chat enquanto for só polling e ~50 consultas/s no B1ms com 100
  participantes (a medir). Com SSE, o custo passa a ser código de ciclo de vida de stream (240 s,
  shutdown, `LISTEN` fora do pool) e a réplica na taxa ativa enquanto houver aba aberta.
- **Quando rever:** mais de ~1.000 streams por réplica, `LISTEN/NOTIFY` aparecendo no tempo de commit, ou o
  jogo exigindo latência que o SSE não dá. Nessa hora, (a) entra atrás do mesmo aviso por chave, com a
  outbox da [ADR 0009](0009-outbox-e-eventos.md).

### Plano de implementação em fatias finas

Cada fatia é um PR, com testes primeiro.

| # | Fatia | Pronto quando |
|---|---|---|
| 1 | **Tracer bullet:** migration `chat`/`chat_message`; `GET .../chat`, `GET`/`POST .../chat/messages` com `afterSeq`, `last_seq` sob lock e o estado aberto mínimo (par existe, evento em andamento, rodada é a última); `duora-web` faz polling a cada 2 s com a aba visível | Dois contextos do Playwright trocam mensagens em homologação; `ChatIT` (par, BOLA, ordem concorrente, leitura após cada commit); `DenyByDefaultIT`; spec OpenAPI, Spectral, `oasdiff` e Schemathesis verdes |
| 2 | **Envio seguro:** `Idempotency-Key` com fingerprint, texto validado, 300 por chat, rate limit, bloqueio e fechamento com resposta única | Todos os testes de Tampering, Elevation e DoS da tabela STRIDE das linhas de envio passam e falham com a mitigação desligada |
| 3 | **Denúncia de mensagem** com evidência (`trustsafety.Reports.fileWithEvidence`) | `ChatReportIT` verde; cota compartilhada com a ADR 0015; texto fora do log |
| 4 | **Expurgo** com job entre réplicas e métrica | `ChatPurgeIT` verde; métrica de chats vencidos exposta; README com o prazo |
| 5 | **Aviso em tempo real** (transporte escolhido na decisão 2): (b) `GET /api/me/stream` + `NOTIFY` transacional + `LISTEN` por réplica; o polling vira fallback com intervalo longo | Aviso só no commit (não no rollback); dois listeners recebem; streams fecham antes de 240 s e no shutdown; verificação manual em homologação do timeout real do ingress e da escala a zero |
| 6 | **Notificações de domínio** no mesmo canal: `connection.formed`, `event.cancelled` | `ConnectionHintIT` (sem aviso para "sim" sem conexão); conjunto de chaves exato; outbox só se houver entrega externa |
| 7 | **Carga:** k6 com 50 chats e 100 participantes em homologação | Latência p95 do envio ao aviso, consultas/s no B1ms, conexões e custo do dia registrados; decisão de manter (b) ou subir para (a) registrada aqui |

## Decidido pelo usuário (2026-10-08)

- **Produto:** os defaults propostos na seção 1 valem para a primeira versão. O significado de "dupla saiu"
  continua indefinido, porque o produto ainda não define sair de uma rodada.
- **Transporte:** (d) polling e depois (b) SSE. O plano (§1, §3, §6) e a [ADR 0009](0009-outbox-e-eventos.md),
  que citam o Web PubSub, precisam ser atualizados para refletir isso.

## Implementação

### Fatias 1 e 2 (2026-10-08, API)

Feitas na API: tabelas `chat` e `chat_message` (V12), o módulo `chat` (core) e as quatro rotas abaixo. Do
"Pronto quando" da fatia 1 falta o lado do `duora-web` (polling a cada 2 s e o Playwright em homologação).

| Rota | Operação | Resposta |
|---|---|---|
| `GET /api/events/{eventId}/rounds/{number}/chat` | `getMyRoundChat` | `{chatId, open, lastSeq}` |
| `GET .../chat/messages?afterSeq=&maxPageSize=` | `listMyRoundChatMessages` | `{items: [{seq, fromMe, text, sentAt}], nextAfterSeq}` |
| `GET .../chat/messages/{seq}` | `getMyRoundChatMessage` | `{seq, fromMe, text, sentAt}` |
| `POST .../chat/messages` + `Idempotency-Key` | `sendRoundChatMessage` | `201` + `Location`, `200` na repetição, `409` com `reason` |

Testes da seção 6 que existem e foram vistos falhando com a mitigação desligada (bloqueio, rodada atual,
rate limit, escopo da chave por remetente, lock do chat e busca da chave anterior):
`ChatIT.aWebSessionWithoutCsrfTokenCannotSend`, `ChatFieldErrorsIT.unknownFieldsAreRejectedWithoutWriting`,
`ChatIT.aRetriedSendWithTheSameKeyRecordsOneMessage`, `sameKeyWithAnotherTextIsAConflict`,
`concurrentSendsWithTheSameKeyRecordOne`, `anotherAccountWithTheSameKeyGetsItsOwnMessage`,
`concurrentSendsGetConsecutiveSequenceNumbers` (mais `manyConcurrentSendsGetConsecutiveSequenceNumbers`: com
dois envios, a disputa pelo lock nem sempre acontece, e o teste de dois passou sem o lock),
`aReaderAfterEachCommitNeverSkipsAMessage`, `ChatSchemaIT.aSequenceNumberIsUniqueInTheChat`,
`ChatIT.someoneOutsideThePairCannotReadNorSend`, `SensitiveDataLoggingIT.chatMessageNeverReachesTheLog`,
`ChatMessageTextTest.doesNotExposeTheTextInToString`, `ChatIT.aBlockedChatLooksLikeARoundThatEnded`,
`ChatRateLimitIT.sendsAboveTheLimitAreRejectedWithRetryAfterAndWithoutWriting`,
`sendIsRefusedWhenTheLimitCannotBeCounted`, `ChatMessageTextTest`, `ChatIT.invalidTextIsRejectedWithoutWriting`,
`aFullChatRefusesNewMessages`, `aBlockEitherWayStopsNewMessages`, `aChatClosesWhenTheNextRoundStarts`,
`aChatClosesWhenTheEventEnds` e `DenyByDefaultIT` (rotas novas na enumeração).

Decisões tomadas na implementação, sem mudar o que foi aceito:

1. **Sem coluna de fingerprint.** A chave de idempotência mora na própria linha da mensagem, com o mesmo
   expurgo, então o reenvio compara o texto gravado (normalizado, NFC e sem espaço nas pontas) com o pedido. Um
   hash do mesmo texto na mesma linha não protegeria nada a mais. Mesmo efeito do fingerprint: mesmo texto →
   `200` com a mesma mensagem; outro texto → `409` `IDEMPOTENCY_KEY_REUSED`.
2. **Escopo da chave:** `(chat, remetente, chave)`, o `UNIQUE` da seção 3. A mesma chave vinda do par é outro
   envio e nunca devolve a mensagem alheia.
3. **A repetição vale depois do fechamento:** com a mesma chave e texto, a resposta é `200` com a mensagem
   gravada mesmo com o chat já fechado; o cliente que perdeu a resposta descobre que a mensagem foi.
4. **Chat cheio é chat fechado:** com 300 mensagens, `open` vira `false` e o envio recebe o mesmo `409`
   `CHAT_CLOSED`, para a leitura e o envio não se contradizerem.
5. **Motivos novos** no `RefusalReason` ([ADR 0020](0020-motivo-das-recusas-no-problem-detail.md)):
   `CHAT_CLOSED` e `IDEMPOTENCY_KEY_REUSED`, ambos `409`. O oasdiff acusa a adição ao enum em todo `409`; está
   registrada no changelog como compatível pela política da ADR 0020.
6. **APIs publicadas:** o horário vem de `events.EventCalendar.periodOf` (nova), e não do `EventRoster`, que
   carregaria a lista de inscritos a cada leitura do polling; a última rodada vem de `Pairings.latestRoundOf`
   (nova). O chat continua sem ler tabela de outro módulo.
7. **O `GET .../chat` cria o chat** na primeira leitura (`insert ... on conflict do nothing`), como a seção 3
   pede, para devolver o `chatId`. É idempotente e não muda nada visível.
8. **`GET .../chat/messages/{seq}`** existe para o `Location` do `201` apontar para algo legível.
9. **Paginação:** `afterSeq` de 0 a 300 (padrão 0) e `maxPageSize` de 1 a 100 (padrão 50).
10. **Ordem das recusas no envio:** caminho, `Idempotency-Key` e corpo inválidos dão `400` antes de gastar o
    limite; depois o limite é gasto, inclusive por quem não está no par (`404`) e na repetição.
11. **Espera pelo lock do chat:** teto de 2 s (`lock_timeout`), depois `503` com `Retry-After: 1`, como a
    decisão da [ADR 0019](0019-decisao-privada-e-conexoes.md).
12. **`purge_after`** já é gravado (fim agendado + 24 h); o job que apaga é a fatia 4, abaixo.
13. **Quebra de linha** é aceita no texto, como na bio; os outros controles e invisíveis, não.
14. A `Idempotency-Key` é convertida como os ids de caminho (`UUID.fromString`), que aceita formas não
    canônicas curtas; a spec declara 36 caracteres.

### Fatias 3 e 4 (2026-10-08, API)

Feitas na API: a denúncia de mensagem com evidência (tabela `report_message_evidence`, V13, do `trustsafety`) e
o expurgo agendado (índice `chat_purge_after_idx`, V14). Do "Pronto quando" da fatia 4, a métrica é
`duora.chat.purge.backlog` (gauge, em chats) e o README traz o prazo.

| Rota | Operação | Resposta |
|---|---|---|
| `POST .../chat/messages/{seq}:report` + `{reason, description}` | `reportRoundChatMessage` | `201` + `Location: /api/reports/{id}` e a denúncia (`ChatMessageReport`); `400` para a própria mensagem; `404` sem mensagem na posição ou fora do par; `429`/`503` da cota de denúncias |

| Configuração (`duora.chat.purge.*`) | Padrão | Validação na subida |
|---|---|---|
| `interval`: espera depois do fim da execução anterior | `PT10M` | pelo menos 1 s |
| `jitter`: desvio sorteado a cada execução, de zero até ele | `PT2M` | não negativo |
| `batch-size`: chats por comando | `100` | de 1 a 10.000 |
| `max-batches-per-run`: lotes por execução | `50` | positivo |

Testes da seção 6 destas fatias vistos falhando com a mitigação desligada (checagem do autor, `toString`
redigido do DTO, `skip locked`, comparação estrita com `purge_after` e teto de lotes):
`ChatReportIT.onlyThePartnersMessageCanBeReported`, `theReportedMessageNeverReachesTheLog`,
`ChatPurgeIT.aChatLockedByAnotherReplicaIsSkippedWithoutWaiting`, `messagesArePurgedAfterTheRetention` e
`aRunStopsAtItsBatchLimit`. Também existem e passam: `ChatReportIT.theReportKeepsTheMessageAfterThePurge`,
`aWebSessionWithoutCsrfTokenCannotReport`, `invalidReportIsRejectedWithoutWriting`,
`theReportQuotaIsSharedWithAccountReports`, `whoBlockedCanStillReport`, `aClosedChatCanStillBeReportedUntilThePurge`,
`ChatPurgeIT.twoReplicasPurgeWithoutConflict`, `chatsNotYetExpiredAreKept`, `theBacklogMetricCountsExpiredChats`,
`ChatPurgeSchedulingIT`, `RequiredChatPurgeSettingsIT`, `ReportsWithEvidenceIT`, `ChatMessageEvidenceTest`,
`JitteredDelayTriggerTest` e `DenyByDefaultIT` (rota nova na enumeração). O `twoReplicasPurgeWithoutConflict`
passa mesmo sem `skip locked` (a segunda réplica espera e não acha o que a primeira apagou): ele prova que nada é
apagado duas vezes e que nenhuma réplica falha; quem prova que uma réplica não espera a outra é o
`aChatLockedByAnotherReplicaIsSkippedWithoutWaiting`.

Decisões tomadas na implementação, sem mudar o que foi aceito:

1. **Rota:** a ação `:report` sobre a mensagem, como a seção 3 propõe e no estilo da [ADR 0005](0005-contrato-da-api.md),
   e não `POST /api/reports` com uma referência à mensagem: o `trustsafety` teria de ler o chat (ciclo entre
   módulos) ou confiar numa cópia mandada pelo cliente. A ação cria um recurso de outro módulo, então responde como
   um Create: `201`, `Location` em `/api/reports/{id}` (legível por `getMyReport`) e os mesmos campos dele, num
   schema próprio (`ChatMessageReport`), sem o texto da mensagem.
2. **API publicada do `trustsafety`:** `Reports.fileWithEvidence(reporter, reported, reason, description,
   ChatMessageEvidence)`, que devolve `FiledReport`. `ReportReason` e `ReportStatus` saíram de `trustsafety.domain`
   para a raiz do pacote, porque o chat reusa a lista de motivos, e `Reports.DESCRIPTION_MAX_LENGTH` publica o teto
   do relato para a spec da rota nova. A [ADR 0015](0015-bloqueio-e-denuncia.md) registra o mesmo.
3. **Evidência:** tabela própria `report_message_evidence`, uma linha por denúncia (PK = `report_id`,
   `on delete cascade` a partir de `report`), com `chat_id`, `event_id`, `round_number`, `seq`, `body` e `sent_at`,
   sem FK para chat ou evento (o chat some no expurgo). O remetente é a conta denunciada, sem coluna repetida. A
   denúncia e a cópia são gravadas num comando só (duas inserções em CTEs): as duas ou nenhuma, sem transação aberta
   pelo serviço.
4. **Ordem das recusas:** par, posição e autor são conferidos antes da cota, então o `404` e o `400` da mensagem
   não a gastam. Em `POST /api/reports` a conta inexistente gasta, para limitar quem adivinha ids; aqui não há id a
   adivinhar, e a leitura do chat, sem cota, já responde o mesmo. Depois vêm as regras do relato e a cota, na ordem
   de `fileReport` (só denúncia válida gasta).
5. **A própria mensagem é `400`**, entrada inválida como a auto-denúncia da [ADR 0015](0015-bloqueio-e-denuncia.md),
   e não `403` nem `409`: a mensagem é sempre de quem a enviou, e a resposta não revela nada que a pessoa não saiba.
6. **Sem transação no serviço do chat:** a mensagem não muda depois de gravada, e a cota é contada em outra conexão.
   Se o expurgo apagar o chat entre a leitura e a gravação, a cópia é gravada assim mesmo, que é o objetivo dela.
7. **Denunciar de novo a mesma mensagem** cria outra denúncia, como em `POST /api/reports` (sem `Idempotency-Key`,
   [ADR 0015](0015-bloqueio-e-denuncia.md)); a cota limita.
8. **Bloqueio:** as duas pessoas continuam denunciando depois de um bloqueio, porque as duas continuam lendo até o
   expurgo (fatias 1 e 2); quem foi bloqueado não descobre o bloqueio por aqui.
9. **`GET .../messages/{seq}` não aceita `:`** no mapeamento (`{seq:[^:]+}`), como as ações do ADMIN: `GET` na ação
   responde `405`, e não `400`. A spec não muda.
10. **Corte do expurgo:** `purge_after < agora`, estrito; no instante de `purge_after` o chat ainda existe, e um
    microssegundo depois sai. O instante é lido uma vez por execução, do `Clock` injetado.
11. **Lote:** `delete from chat where id in (select id ... where purge_after < :now order by purge_after limit :n
    for update skip locked)`, um comando por lote; as mensagens saem pela FK `on delete cascade`. Sem ShedLock nem
    advisory lock: os locks de linha bastam, e uma réplica parada no meio só deixa um lote para a próxima. A
    execução para no primeiro lote incompleto ou em `max-batches-per-run`.
12. **Agendamento:** `SchedulingConfigurer` com um `Trigger` próprio (`JitteredDelayTrigger`): a próxima execução é
    o fim da anterior (ou a subida) mais `interval` e mais um desvio sorteado a cada vez, de zero a `jitter`. O
    `@Scheduled` do Spring não tem jitter, e dormir dentro da tarefa prenderia a única thread do agendador. Um erro
    numa execução vai para o log pelo agendador, e a próxima tenta de novo; não há retry dentro dela.
13. **Métrica e log:** o gauge conta os chats vencidos na leitura da métrica (pelo índice de `purge_after`), e o log
    de cada execução traz só números (apagados, lotes, restantes), nunca ids, pares ou texto.
14. **Testes sem o agendador:** a configuração de teste põe `interval` em um dia, para um contexto em cache com o
    relógio de teste adiantado não apagar chats de outro teste; o `ChatPurgeIT` chama o expurgo direto, e o
    `ChatPurgeSchedulingIT` confere que a tarefa está registrada com o trigger com jitter.
15. **Depois do expurgo,** `GET .../chat` ainda recria um chat vazio e fechado (o `insert ... on conflict do nothing`
    das fatias 1 e 2), com outro `chatId`, que o expurgo seguinte apaga; nenhum conteúdo volta. A lista de mensagens
    vem vazia, e a mensagem e a denúncia respondem `404`. Mudar isso (responder `404` depois do prazo) muda o
    contrato das fatias 1 e 2 e fica como pendência abaixo.
16. **Limites da cópia:** `ChatMessageEvidence` aceita de 1 a 500 caracteres e posição de 1 a 300, os mesmos do chat,
    repetidos no `trustsafety` (que não pode ler o chat); `ChatMessageTest.everyMessageFitsInTheReportEvidence`
    quebra se o chat passar a aceitar mais.

## Pendente com o usuário (decisões críticas)

1. **Aviso de chat sem outbox** (por `NOTIFY` transacional) em (b), contrariando a regra da
   [ADR 0009](0009-outbox-e-eventos.md) para avisos internos e recuperáveis.
2. **Resíduo do bloqueio concorrente** (milissegundos) aceito, ou fechado com lock compartilhado.
3. **Stream e sessão:** a reabertura do SSE renova os 30 min de inatividade da sessão ([ADR 0002](0002-front-web-com-bff.md))?
   Proposto: não; só requisições do usuário renovam (exige não tocar a sessão no stream).
4. **Base legal e política de privacidade** para conversa como dado sensível, com apoio jurídico.
5. **Retenção da evidência de mensagem:** segue a das denúncias, ainda pendente
   ([ADR 0015](0015-bloqueio-e-denuncia.md), pendência 2; a [ADR 0023](0023-exclusao-de-conta-e-retencao.md) propõe
   até a resolução e 2 anos depois de encerrada). Hoje nenhuma denúncia nem cópia é apagada, e só a moderação, que
   ainda não existe, as leria.
6. **Chat depois do expurgo:** manter o chat vazio recriado pela leitura (decisão 15 das fatias 3 e 4) ou responder
   `404` em todas as rotas do chat depois de `purge_after`. Proposto: `404`, que só troca o `200` vazio por um
   erro já documentado; entra quando o `duora-web` tratar o fim do chat.

## Consequências

- Módulo novo `chat` (core), dependente de `matching.Pairings`, `events.EventRoster` e
  `trustsafety.Blocking`; `trustsafety` ganha `Reports.fileWithEvidence` e publica `ReportReason` e
  `ReportStatus`. O `matching` pode precisar
  publicar "qual é a última rodada" (ou `Pairings` ganha esse dado).
- O cursor `afterSeq` transparente é uma exceção registrada à [ADR 0005](0005-contrato-da-api.md).
- O polling soma requisições à cota grátis do Container Apps; acompanhar o número por evento.
- Se (b) for escolhido, cada réplica abre uma conexão a mais no PostgreSQL (fora do Hikari) e o shutdown
  passa a fechar streams; a ADR 0008 ganha mais uma thread vigiada pela liveness.
- Nada muda na infraestrutura com (b), (c) ou (d); com (a), o Bicep ganha o Web PubSub e um papel RBAC.
- Cada réplica roda o expurgo a cada 10 a 12 min; com o volume do piloto (até 50 chats por evento), uma
  execução apaga tudo num lote. O alerta sobre `duora.chat.purge.backlog` (subindo por mais de uma hora) entra
  com o monitoramento da [ADR 0014](0014-infraestrutura-do-piloto-na-azure.md).

## Compliance

Esta ADR é proposta: os testes da seção 6 são o critério de aceite das fatias, e cada um precisa ser visto
falhando com a mitigação desligada. A fronteira entre módulos entra no `ArchitectureTest`
(`coreDomainIsFrameworkFree`, `coreApplicationTalksToInfrastructureThroughPorts`,
`modulesUseOnlyPublishedApisOfOtherModules`). Contrato pela [ADR 0012](0012-contrato-openapi.md).

## Fontes (consultadas em 2026-10-08)

- Azure Retail Prices API, `serviceName eq 'Web PubSub'` e `'Azure Container Apps'`, `armRegionName eq
  'northcentralus'`: <https://prices.azure.com/api/retail/prices>
- Limites do Web PubSub (Free: 20 conexões e 20 mil mensagens/dia; Standard: 1.000 conexões e 1 milhão
  de mensagens por unidade por dia):
  <https://learn.microsoft.com/azure/azure-resource-manager/management/azure-subscription-service-limits#azure-web-pubsub-limits>
- Modelo de cobrança do Web PubSub (unidade por dia, mensagem = 2 KB de saída):
  <https://learn.microsoft.com/azure/azure-web-pubsub/concept-billing-model>
- Troca de tier com 30–60 min de indisponibilidade:
  <https://learn.microsoft.com/azure/azure-web-pubsub/howto-scale-manual-scale>
- Token do cliente na query string ou no header, roles e grupos:
  <https://learn.microsoft.com/azure/azure-web-pubsub/concept-client-protocols#authorization>;
  validade padrão de 60 min: <https://learn.microsoft.com/azure/azure-web-pubsub/chat-howto-authenticate#token-lifetime-and-refresh>
- Ingress do Container Apps (WebSocket, HTTP/2, timeout de 240 s):
  <https://learn.microsoft.com/azure/container-apps/ingress-overview#http>; ingress premium (idle timeout
  configurável): <https://learn.microsoft.com/azure/container-apps/ingress-environment-configuration#premium-ingress-mode>
- Cobrança do Container Apps (condições da taxa ociosa, cotas grátis):
  <https://learn.microsoft.com/azure/container-apps/billing#consumption-plan>
- LGPD, arts. 5º, 11 e 33 a 36: <https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm>
