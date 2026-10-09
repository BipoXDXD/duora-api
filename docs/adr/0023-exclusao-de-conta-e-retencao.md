# 0023. Exclusão de conta, direitos do titular e retenção de dados

- **Status:** Proposta — aguarda decisão do usuário. Nenhum código desta ADR existe. Prazos, bases legais e
  interpretações da lei estão marcados como **(default proposto)** ou **(jurídico)**: são pontos de partida,
  não decisões.
- **Data:** 2026-10-08
- **Relacionadas:** [ADR 0001](0001-autenticacao-entra-external-id.md) (Entra External ID),
  [ADR 0002](0002-front-web-com-bff.md) (sessão do BFF), [ADR 0005](0005-contrato-da-api.md),
  [ADR 0006](0006-rate-limit-no-postgresql.md), [ADR 0007](0007-estilo-por-modulo.md),
  [ADR 0009](0009-outbox-e-eventos.md) (outbox), [ADR 0011](0011-conta-e-perfil.md) (pendência 2),
  [ADR 0013](0013-logs-estruturados-e-correlation-id.md) (logs), [ADR 0014](0014-infraestrutura-do-piloto-na-azure.md)
  (backups e Log Analytics), [ADR 0015](0015-bloqueio-e-denuncia.md) (pendências 2 e 4),
  [ADR 0016](0016-eventos-e-inscricoes.md) (pendência 8), [ADR 0017](0017-pareamento.md),
  [ADR 0019](0019-decisao-privada-e-conexoes.md) (pendências 7 e 8), [ADR 0021](0021-chat-temporario-e-reconexao.md)
  (expurgo do chat, base legal)

## Contexto

Sete ADRs deixaram a mesma pendência: o que acontece com os dados de uma pessoa quando ela sai do Duora, e por
quanto tempo cada dado fica guardado enquanto ela está. Toda tabela que aponta para `account` usa
`on delete restrict` de propósito ([ADR 0011](0011-conta-e-perfil.md)): apagar a linha da conta falha até que cada
módulo apague antes o que é seu, em vez de uma cascata que o código não vê. Hoje nada apaga nada; só o
`AccountTables` dos testes de integração esvazia as tabelas na ordem das FKs.

O produto é um app de encontros 18+. Saber que alguém se inscreveu num encontro, por quem se interessou e o que
escreveu no chat pode revelar vida sexual, o que a LGPD trata como **dado pessoal sensível** (art. 5º, II, e
art. 11). Os dados ficam nos EUA (banco, logs e backups pela [ADR 0014](0014-infraestrutura-do-piloto-na-azure.md),
identidade pela [ADR 0001](0001-autenticacao-entra-external-id.md)), uma transferência internacional (arts. 33 a 36)
ainda sem instrumento definido.

Forças que puxam em direções diferentes:

- **Minimização e eliminação** (LGPD arts. 6º, III, 16 e 18, VI): apagar o que não tem mais finalidade.
- **Dados do outro lado:** decisões, conexões, assentos e conversas são de duas pessoas. Apagar tudo de A mexe
  em dados de B; manter tudo de B mantém referências a A.
- **Moderação:** denúncias são evidência. Apagar a conta não pode servir para fugir de uma denúncia.
- **Integridade:** o par recíproco de `round_seat` é garantido por FK; a semente da rodada só reproduz o sorteio
  com a lista original.
- **Fronteira entre módulos:** nenhum módulo lê nem escreve tabela de outro ([ADR 0011](0011-conta-e-perfil.md),
  `ArchitectureTest`), e `identity` não pode depender dos módulos que dependem dele (`modulesAreFreeOfCycles`).
- **Fora do banco:** a conta no Entra External ID, a sessão do BFF, os logs no Log Analytics e os backups com
  PITR não obedecem a um `delete`.

## 1. Inventário de dados pessoais

Estado de `main` (migrations V1 a V11) mais o chat proposto na [ADR 0021](0021-chat-temporario-e-reconexao.md)
(branch `feat/chat`). "Sensível" segue o art. 11; "sensível por contexto" é o dado comum que, num app de
encontros, revela vida sexual e recebe o mesmo cuidado (como já fazem as ADRs 0016, 0019 e 0021).

| Módulo | Tabela / lugar | Dado | Sensibilidade | Finalidade | Quem lê | Retenção hoje |
|---|---|---|---|---|---|---|
| `waitlist` | `waitlist_entry` | e-mail, data | Comum | Avisar do lançamento | ADMIN (só a contagem) | Para sempre |
| `identity` | `account` | emissor + `oid` (pseudônimo do Entra), `created_at` | Comum, pseudônimo | Ligar o token à pessoa | `identity` (resolver da conta) | Para sempre |
| `profiles` | `profile` | nome de exibição, data de nascimento, bio, UF | Comum; data de nascimento prova 18+; bio é texto livre e pode trazer qualquer coisa | Apresentação e elegibilidade 18+ | A própria pessoa; `events` pergunta só se está completo | Para sempre |
| `trustsafety` | `account_block` | quem bloqueou quem, data | Sensível por contexto | Proteção de quem bloqueia | Quem bloqueou; outros módulos só pela pergunta "existe bloqueio?" | Até desbloquear (o desbloqueio apaga) |
| `trustsafety` | `report` | denunciante, denunciado, motivo, relato livre (até 1000), data | Sensível: o relato pode citar terceiros, assédio, conteúdo sexual, suspeita de menor | Moderação | Quem denunciou (por id); moderação ainda não existe | Para sempre |
| `events` | `registration` | quem se inscreveu em qual encontro, data | Sensível por contexto | Capacidade e sorteio | A própria pessoa; ADMIN só a contagem; `matching` pelo `EventRoster` | Para sempre |
| `matching` | `round_seat` | par de cada pessoa em cada rodada | Sensível por contexto | Mostrar o par; impedir par repetido | A própria pessoa (o próprio par); `connections` pelo `Pairings` | Para sempre |
| `matching` | `round` | semente, horário | Não pessoal | Reproduzir o sorteio | ADMIN | Para sempre |
| `connections` | `round_decision` | interesse sim/não por outra pessoa | **Sensível** ([ADR 0019](0019-decisao-privada-e-conexoes.md)) | Formar conexão por interesse mútuo | Só quem decidiu | Para sempre |
| `connections` | `connection` | par conectado, data | Sensível por contexto | Contato depois do encontro | As duas pessoas | Para sempre |
| `chat` (proposto) | `chat`, `chat_message` | conversa da rodada | **Sensível** ([ADR 0021](0021-chat-temporario-e-reconexao.md)) | Conversa durante o encontro | As duas pessoas | 24 h após o fim do evento (decidido) |
| `trustsafety` (proposto) | evidência da denúncia de mensagem | cópia da mensagem denunciada | **Sensível** | Moderação | Moderação | Pendente ([ADR 0015](0015-bloqueio-e-denuncia.md), pendência 2) |
| `config` | `rate_limit_bucket` | chave com IP (`join-waitlist:<ip>`) ou id da conta (`report:`, `decision:`, `registration:`) | Comum (IP é dado pessoal) | Rate limit | Ninguém | Até a reposição + 1 min (máx. ~1 dia) |
| BFF | `SPRING_SESSION*` | `sub` do Entra como principal; atributos com o `OidcUser` (claims do ID token, como nome) e o access token | Comum; credencial | Sessão web | A própria aplicação | 30 min sem uso |
| Logs | stdout → Log Analytics | IP (`X-Forwarded-For` nos logs HTTP do Container Apps), rota com ids (`/api/accounts/{id}:block`), `traceId`; o filtro de exceção registra método e URI | Comum, pseudônimo | Operação e incidentes | Quem opera | 30 dias |
| Backups | PostgreSQL Flexible Server | cópia de tudo acima | Igual à origem | Recuperação | Quem opera | 7 dias (homologação), 14 (produção), com PITR |
| Entra External ID | tenant `duoraapp` (EUA) | e-mail, nome, credencial ou vínculo social, logs de entrada | Comum; credencial | Autenticação | Microsoft (operador) e quem administra o tenant | Até apagar o usuário no tenant |

Não há e-mail, nome do Entra, IP, localização precisa nem user agent nas tabelas de domínio; `event` não tem
dado pessoal (nem quem criou).

## 2. Direitos do titular (LGPD, art. 18)

| Direito | O que a API já oferece | O que falta |
|---|---|---|
| I. Confirmação e II. acesso | Leituras avulsas: `GET /api/me`, `/api/me/profile`, `/api/me/blocked-accounts`, `/api/me/connections`, `/api/me/registrations` (só eventos que não acabaram), a própria decisão e o próprio par por rodada, a própria denúncia por id | Uma resposta só com tudo (art. 19: formato simplificado imediato ou declaração completa em até 15 dias); inscrições passadas; lista das próprias decisões e denúncias |
| III. Correção | `PATCH /api/me/profile` (nome, bio, UF) | Data de nascimento travada (`409`): canal de correção ([ADR 0011](0011-conta-e-perfil.md), pendência 4) |
| IV. Anonimização, bloqueio ou eliminação do desnecessário | Nada | Retenção por categoria (seção 4) |
| V. Portabilidade | Nada | Exportação em JSON legível por máquina (a mesma do acesso) |
| VI. Eliminação | Nada | Exclusão de conta (seção 3); saída da fila de espera por e-mail |
| VII. Informação sobre compartilhamento | Nada na API | Política de privacidade: Microsoft como operador (Entra, Azure), transferência aos EUA |
| VIII e IX. Consentimento e revogação | Não há registro de consentimento (`consent_records` do plano §4 nunca entrou) | Depende da base legal (**jurídico**): se o dado sensível estiver sob consentimento (art. 11, I), revogar impede o pareamento e o chat, e na prática vira exclusão parcial ou total |

**Exportação proposta:** `GET /api/me/export` devolve um JSON com conta (`createdAt`), perfil, bloqueios feitos,
denúncias feitas (com o relato), inscrições (todas), pares por rodada, decisões próprias e conexões. Ficam de
fora, de propósito, os **bloqueios e denúncias recebidos** e as decisões dos outros: entregá-los revelaria dado
de terceiros e quebraria a regra da [ADR 0015](0015-bloqueio-e-denuncia.md) de que ninguém descobre quem o
bloqueou (art. 18 não alcança dado pessoal de outra pessoa; o limite exato é **jurídico**). Cada módulo monta a
sua parte por uma interface publicada (mesma inversão da seção 3); a rota exige reautenticação recente, como a
exclusão, e tem rate limit por conta (poucas por dia), porque é cara e concentra tudo de uma pessoa.

## 3. Exclusão de conta

### 3.1 Estratégia de dados

| Opção | Prós | Contras |
|---|---|---|
| (a) **Hard delete em cascata ordenada**, apagando também a linha de `account` | Nada sobra; simples de explicar | O par recíproco de `round_seat` obriga a apagar ou alterar o assento de B (B passaria a "ter ficado de fora", o que é falso); a semente deixa de reproduzir o sorteio; as denúncias contra A teriam de sumir ou ganhar FK anulável; ordem das FKs vira acoplamento entre módulos |
| (b) **Soft delete + expurgo assíncrono** (`deleted_at`, apagar depois de N dias) | Janela para desfazer (arrependimento, conta invadida); exclusão em segundo plano | O dado continua lá durante a janela e todo módulo precisa filtrar a conta "apagada" em cada consulta, ou ela vaza; `deleted=true` sozinho não conclui a exclusão (plano §4); mais estados |
| (c) **Apagar o que é de A e manter um *tombstone* da conta** (pseudonimização da referência) | Os dados próprios de A saem na hora; a linha de `account` fica sem emissor nem sujeito, então nada a liga a uma pessoa; referências de B (assento, denúncia) continuam íntegras sem mexer nas FKs; o sorteio continua reproduzível; o tombstone sai sozinho quando nada mais o referencia | O id antigo continua existindo; B, que conheceu A no encontro, ainda liga o id a uma pessoa (resíduo de reidentificação, abaixo); precisa de regra por tabela (o que sai, o que fica) |

**Recomendação: (c)**, com estas regras por tabela (todas **default proposto**):

| Dado | Na exclusão de A | Por quê |
|---|---|---|
| `profile` de A | Apagado | É o que identifica A para os outros |
| `registration` de A | Apagadas (todas) | Libera vaga nos futuros; as passadas não têm mais finalidade para A |
| `round_seat` de A e o recíproco de B | **Mantidos**, apontando para o tombstone | Histórico de B, par recíproco e semente íntegros; saem pela retenção das rodadas (seção 4) |
| `round_decision` de A | Apagadas | Dado sensível de A |
| `round_decision` de B **sobre** A | Apagadas | Sem finalidade: a conexão com A nunca mais se forma. Minimização pelo controlador (art. 16); B perde a leitura dessa decisão (`404`). **Decisão do usuário** (alternativa: manter até a retenção das decisões) |
| `connection` entre A e B | Apagada | Não serve mais a B; some da lista sem aviso (a pessoa "sumiu") |
| `account_block` feito por A | Apagado | Protegia A, que não existe mais |
| `account_block` feito por B contra A | Apagado | O tombstone não entra mais em evento nem chat, então o bloqueio não protege nada; a lista de B perde o item. Ver "evasão" para a exceção |
| `report` feito por A | **Mantido**, com o denunciante como tombstone | É evidência contra B; o relato pode conter dado de A (**jurídico**: base de retenção) |
| `report` contra A | **Mantido** pela retenção das denúncias | Evita fugir da moderação apagando a conta (seção 5) |
| `chat` de A | **Fecha na hora**, mas as mensagens ficam até o expurgo normal (até 24 h após o fim do evento) | Se A assediou B e apagou a conta, B ainda pode denunciar a mensagem; o expurgo já vem em no máximo ~36 h (evento de até 12 h + 24 h) |
| `rate_limit_bucket` com o id de A | Não apagado; expira em até um dia | Apagar exigiria SQL por prefixo numa tabela do Bucket4j; o prazo é curto e documentado |
| Sessões web de A | Apagadas (`findByPrincipalName` do Spring Session, pelo `sub`) | Encerra todas as abas, não só a atual |
| `account` de A | Vira tombstone: `issuer` e `subject` nulos, `erased_at` preenchido | Ver 3.3 |

### 3.2 Quem apaga o quê, sem um módulo tocar tabela alheia

| Opção | Prós | Contras |
|---|---|---|
| (i) **SPI no `identity`**: `identity.AccountErasure` (interface publicada), implementada por cada módulo; o serviço de exclusão chama todas **na mesma transação** e depois transforma a conta em tombstone | Um banco só ([ADR 0007](0007-estilo-por-modulo.md); Arquitetura 10: o que precisa de ACID fica junto): ou tudo sai, ou nada sai; sem órfão; síncrono e testável com PostgreSQL real; mesma inversão que `events.RoundProgress` já usa, sem ciclo | Uma transação maior (algumas centenas de linhas no piloto); módulo novo precisa lembrar de implementar (fitness function abaixo) |
| (ii) **Evento de domínio pela outbox** ([ADR 0009](0009-outbox-e-eventos.md)): `AccountErasureRequested`; cada módulo consome, apaga e confirma; `identity` fecha a conta quando todos confirmarem | Desacoplado; serve se um módulo virar serviço separado | A outbox ainda não existe; estados intermediários (metade apagada) por design; rastrear confirmações por módulo; saga para um caso que cabe numa transação local, o que a Arquitetura 10 desaconselha |
| (iii) `identity` chama a API publicada de cada módulo | Direto | Ciclo `identity` ↔ todos os módulos, que o `ArchitectureTest` recusa |

**Recomendação: (i) para o banco, e uma fila durável só para o que está fora dele.** A transação da exclusão:

1. trava a linha da conta (`select ... for update`); se já for tombstone, termina com sucesso (idempotente);
2. chama cada `AccountErasure.eraseDataOf(AccountId)`; cada implementação apaga só as próprias tabelas, pelo
   próprio repositório;
3. transforma a conta em tombstone e grava `erased_identity` (3.3);
4. grava uma linha em `account_erasure_task` para cada passo externo (Entra; fotos no Blob quando existirem),
   na mesma transação, como numa outbox mínima;
5. no commit, apaga as sessões web e escreve no log a linha `account.erased` só com o id da conta e o `traceId`.

Com o tombstone, a linha de `account` não é apagada, então as FKs `restrict` não disparam e a ordem dos módulos
não importa (nenhuma das tabelas apagadas referencia outra). Se um dia o tombstone sair da estratégia, trocar as
FKs para `no action deferrable initially deferred` mantém a garantia no commit sem impor ordem.

Os passos externos são processados por um job (`FOR UPDATE SKIP LOCKED`, tentativas limitadas com backoff e
jitter, métrica de pendentes e da idade do mais antigo). "Já não existe" no destino (Graph `404`) conta como
sucesso: o estado final pedido já vale.

**Idempotência e reprocesso:** a exclusão repetida (clique duplo, retry do front, duas abas) encontra o tombstone
e responde igual; cada `eraseDataOf` é um `delete ... where account_id = ?`, idempotente por natureza; as tarefas
externas são idempotentes pelo destino. Uma falha no meio desfaz tudo (rollback) e a pessoa tenta de novo.

### 3.3 Tombstone, identidade e tokens antigos

- `account` ganha `erased_at timestamptz`; `issuer` e `subject` passam a anuláveis com
  `CHECK ((erased_at is null) = (issuer is not null and subject is not null))`. A `UNIQUE (issuer, subject)`
  continua valendo para contas vivas (nulos não colidem).
- **Token antigo reabrindo conta:** depois da exclusão, um access token ainda válido (até ~1 h) ou uma sessão
  em outra réplica chegaria ao `CurrentAccountArgumentResolver`, não acharia conta pelo emissor + `oid` e abriria
  **uma conta nova** na hora. Proposta: tabela `erased_identity (identity_hash bytea primary key, erased_at)`,
  com `identity_hash = HMAC-SHA256(segredo do Key Vault, issuer || subject)`; o resolver recusa com `401` um token
  cujo `iat` seja anterior a `erased_at` para aquele hash. A linha sai depois de 24 h (default proposto: acima da
  vida de qualquer token emitido antes da exclusão), salvo a exceção de moderação (seção 5).
- **Voltar depois:** com login novo (`iat` posterior), a mesma pessoa abre uma conta nova e vazia. Nada é
  restaurado.
- **Limpeza do tombstone:** um job tenta apagar, uma por vez, as contas com `erased_at` antigo; violação de FK
  (`23503`) quer dizer que ainda há referência (assento, denúncia) e a conta fica para a próxima rodada. Assim o
  `identity` não lê tabela de ninguém.
- **Resíduo de reidentificação aceito:** B conheceu A no encontro e pode ligar o id do tombstone a uma pessoa
  real enquanto o assento existir. Para o Duora, sem emissor, sujeito, perfil e com logs que expiram em 30 dias,
  o id deixa de ser ligável a alguém por meios próprios (art. 12). Se isso basta para tratar o tombstone como
  anonimizado é **jurídico**.

### 3.4 Contrato

- `DELETE /api/me` → `204`. Repetir também `204`. Sem id na rota: só dá para apagar a própria conta.
- **Reautenticação recente:** a sessão precisa de um login feito há no máximo 10 minutos (default proposto),
  pela claim `auth_time` do ID token. Sem ela, `403` com `reason` `REAUTHENTICATION_REQUIRED`
  ([ADR 0020](0020-motivo-das-recusas-no-problem-detail.md)); o front refaz o login com `prompt=login` (ou
  `max_age`) e repete. Confirmar que o Entra External ID emite `auth_time`; se não emitir, o BFF guarda o instante
  do último login na sessão. A porta bearer recusa com o mesmo `403` até existir um cliente móvel que saiba
  reautenticar.
- CSRF obrigatório na sessão web; corpo vazio (qualquer chave → `400`).
- Rate limit por conta (poucas tentativas por hora), como as demais operações caras.
- A resposta não diz nada sobre denúncias retidas; a política de privacidade diz o que fica e por quanto tempo.

### 3.5 Fora do banco

- **Entra External ID.** Se a conta no Entra não for apagada, o e-mail e o nome continuam lá e a pessoa
  consegue entrar de novo (abrindo uma conta nova vazia).

  | Opção | Prós | Contras |
  |---|---|---|
  | Job da API chama o Microsoft Graph (`DELETE /users/{oid}`), por `account_erasure_task` | Automático; prazo curto e medido | A credencial da API passa a poder apagar **qualquer** usuário do tenant (permissão de aplicação, provavelmente `User.DeleteRestore.All`; confirmar); credencial de outro tenant (o External ID não é o tenant da assinatura; federar a managed identity ou usar segredo/certificado); o usuário apagado fica na lixeira do diretório por até 30 dias (confirmar no External ID) |
  | **Runbook manual** com script em `infra/entra/` (a lista vem das tarefas pendentes) | Nenhum privilégio novo na API; volume do piloto é pequeno | Depende de alguém rodar; prazo legal vira disciplina operacional; precisa de alerta para tarefa pendente há mais de N dias |
  | Não apagar no Entra | Nada a fazer | Dado de identidade fica com o operador sem finalidade; contraria a eliminação |

  **Recomendação:** runbook manual no piloto, com a tarefa registrada na mesma transação e alerta se ficar
  pendente mais de 7 dias (default proposto); automatizar pelo Graph quando o volume ou o prazo pedirem, numa ADR
  própria por causa do privilégio.
- **Logs.** A aplicação não grava nome, e-mail nem texto livre (canários do `SensitiveDataLoggingIT`), mas os logs
  HTTP do Container Apps têm IP e rota com ids. Não são apagados por pedido: expiram em 30 dias no Log Analytics,
  o que vai para a política de privacidade. O Marco Civil (Lei 12.965/2014, art. 15) obriga provedor de aplicação
  constituído como pessoa jurídica com fins econômicos a guardar registros de acesso por 6 meses; se o Duora se
  enquadrar, a retenção de 30 dias está **curta** e esses registros não podem sair na exclusão (**jurídico**).
- **Backups e PITR.** O dado apagado continua nos backups por até 7 (homologação) ou 14 dias (produção) e não dá
  para apagá-lo seletivamente. Risco real: um restore para antes da exclusão **ressuscita** a conta. Mitigação: o
  log `account.erased` (id da conta e instante, sem PII) fica 30 dias no Log Analytics, mais que a janela do PITR;
  o runbook de restore relê esses ids e reaplica a exclusão (idempotente) antes de reabrir o tráfego. Prazo e
  restore vão para a política de privacidade.
- **Fila de espera.** `waitlist_entry` não tem conta: saída por e-mail (link com token de uso único, quando houver
  envio de e-mail) ou pelo canal do encarregado. Até lá, apagar à mão por pedido.

## 4. Retenção por categoria

Prazos são **default proposto** até a decisão do usuário e do jurídico. O gatilho é o fim do evento (`ends_at`)
quando o dado nasce de um encontro.

| Categoria | Prazo proposto | Base do prazo | Quem apaga |
|---|---|---|---|
| Conta e perfil | Enquanto a conta existir | Finalidade do serviço | Exclusão pela pessoa; inatividade é **pergunta** (abaixo) |
| Fila de espera | Até 30 dias após o lançamento, ou 12 meses, o que vier antes | Finalidade esgotada | Job do `waitlist` |
| Inscrições | 90 dias após o fim do evento | Disputas e política de falta (no-show) da [ADR 0016](0016-eventos-e-inscricoes.md) | Job do `events` |
| Rodadas e assentos | 90 dias após o fim do evento | Investigação de denúncia sobre o encontro | Job do `matching`, pela API publicada do `events` que diz quais eventos acabaram antes de um instante |
| Decisões | 7 dias após o fim do evento (ou após o prazo de decisão, quando existir) | Sem uso depois da conexão formada ([ADR 0019](0019-decisao-privada-e-conexoes.md), pendência 7) | Job do `connections` |
| Conexões | Enquanto as duas contas existirem | Finalidade do produto | Exclusão de qualquer lado; "desconectar" (pendência 6 da 0019) |
| Chat | 24 h após o fim do evento | Decidido na [ADR 0021](0021-chat-temporario-e-reconexao.md) | Job do `chat` |
| Bloqueios | Enquanto quem bloqueou existir | Proteção, sem prazo | Desbloqueio ou exclusão |
| Denúncias e evidências | Abertas: até a resolução. Encerradas: 2 anos (**jurídico**; o prazo de reparação civil é de 3 anos, CC art. 206, § 3º, V) | Moderação e defesa em processo | Job do `trustsafety` |
| Hash de identidade de conta excluída com denúncia (seção 5) | Igual ao da denúncia | Evasão de moderação | Job do `identity` |
| `erased_identity` sem denúncia | 24 h | Tokens emitidos antes da exclusão | Job do `identity` |
| Tombstone da conta | Até nada mais o referenciar | Integridade | Job do `identity` (tentativa com `23503`) |
| `account_erasure_task` concluída | 30 dias | Prova de que o passo externo rodou | Job do `identity` |
| Rate limit | Até a reposição + 1 min | Já implementado ([ADR 0006](0006-rate-limit-no-postgresql.md)) | `ExpiredRateLimitBucketCleaner` |
| Sessão web | 30 min sem uso | Já implementado ([ADR 0002](0002-front-web-com-bff.md)) | Spring Session |
| Logs | 30 dias, ou 6 meses se o Marco Civil se aplicar (**jurídico**) | Operação; obrigação legal | Log Analytics |
| Backups | 7 / 14 dias | [ADR 0014](0014-infraestrutura-do-piloto-na-azure.md) | Azure |

**Evidência por cópia:** em vez de reter assentos ou chats por causa de uma denúncia aberta (o que faria cada
módulo perguntar ao `trustsafety` antes de apagar), a denúncia guarda no ato uma cópia do que precisa, como a
[ADR 0021](0021-chat-temporario-e-reconexao.md) já propõe para a mensagem. Cada módulo apaga no seu prazo, sem
consultar ninguém.

**Mecanismo:** um job por módulo, cada um apagando só as próprias tabelas, agendado com jitter em todas as
réplicas. Lotes pela PK (`delete ... where (chave) in (select chave ... where vencido limit N for update skip
locked)`), porque o `delete` por predicado é idempotente e o `SKIP LOCKED` evita que duas réplicas disputem as mesmas linhas;
nenhum lock com timeout é necessário (Sistemas distribuídos 14: lock no banco e processamento idempotente).
Cada job expõe a métrica de linhas vencidas ainda não apagadas, com alerta quando passar de um dia de atraso. O
`ExpiredRateLimitBucketCleaner` já segue esse desenho.

## 5. STRIDE

| Ameaça | Mitigação | Teste a escrever |
|---|---|---|
| Spoofing: terceiro apaga a conta de A (CSRF, XSS, sessão esquecida aberta, token vazado) | Rota sem id; CSRF; reautenticação recente (`auth_time` ≤ 10 min); porta bearer recusada | `AccountErasureIT.webSessionWithoutCsrfTokenCannotErase`, `staleLoginMustReauthenticateBeforeErasing`, `bearerTokenCannotErase`, `anErasureOnlyEverTouchesTheCallersOwnAccount`; `DenyByDefaultIT` |
| Tampering: corpo com `accountId` ou opções extras | Corpo vazio; chave desconhecida → `400` sem apagar | `AccountErasureIT.bodyWithAnyKeyIsRejectedWithoutErasing` |
| Tampering/Elevation: token emitido antes da exclusão reabre a conta | `erased_identity` com `iat` < `erased_at` → `401` | `AccountErasureIT.tokenIssuedBeforeTheErasureDoesNotReopenAnAccount`, `aLoginAfterTheErasureOpensANewEmptyAccount` |
| Repudiation: quem apagou e quando | `erased_at` no tombstone; log `account.erased` com id e `traceId` | `AccountErasureIT.erasureIsLoggedWithTheAccountIdOnly` (sem PII, canário) |
| Information disclosure: reidentificação pelo tombstone | Emissor e sujeito nulos; perfil apagado; HMAC com segredo, nunca o `oid` em claro; nada pessoal no log | `AccountErasureIT.theTombstoneKeepsNoIdentity`, `ErasedIdentityTest.storesOnlyAKeyedHash` |
| Information disclosure: exportação entrega dado de terceiros | Exportação sem bloqueios e denúncias recebidos nem decisões alheias; conjunto de chaves fixo | `AccountExportIT.exportHasExactlyTheCallersOwnData`, `exportDoesNotRevealWhoBlockedOrReportedTheCaller` |
| Information disclosure: os outros descobrem a exclusão de forma diferente de outros sumiços | Conexão some sem aviso; chat fechado responde igual a qualquer fechamento | `AccountErasureIT.theConnectionDisappearsFromThePartnersList`, `ChatIT.aChatWithAnErasedAccountLooksLikeAnyClosedChat` |
| Exclusão parcial deixando órfãos | Uma transação para todo o banco; tarefas externas gravadas na mesma transação | `AccountErasureIT.anErasureIsAllOrNothing` (falha forçada na última implementação → nada apagado), `repeatingAnErasureIsHarmless`, `ErasureTaskJobIT.retriesUntilTheDestinationConfirms`, `aMissingUserCountsAsDone` |
| Exclusão parcial: módulo novo esquece de apagar | Fitness function: toda FK para `account` no `information_schema` tem uma `AccountErasure` responsável; varredura das colunas `uuid` depois da exclusão | `ArchitectureTest.everyTableReferencingAccountHasAnEraser`, `AccountErasureIT.nothingButTheAllowedReferencesRemain` |
| Exclusão desfeita por restore de backup | Runbook relê `account.erased` do Log Analytics e reaplica | `AccountErasureReplayIT.replayingAnErasureAfterARestoreIsIdempotent`; ensaio manual do runbook registrado |
| Evasão de moderação: apagar a conta para fugir de denúncia, ou assediar e sumir antes da denúncia | Denúncias contra A ficam pela retenção; se houver denúncia aberta ou recente, o `erased_identity` de A fica pelo prazo da denúncia, e uma conta nova com a mesma identidade é ligada ao caso; o chat fica até o expurgo normal para B denunciar | `ReportRetentionIT.reportsAgainstAnErasedAccountRemainForModeration`, `AccountErasureIT.aReturningIdentityOfAReportedAccountIsLinkedToTheCase`, `ChatReportIT.aMessageFromAnErasedAccountCanStillBeReportedUntilThePurge` |
| Denial of service: exclusões em laço ou concorrentes seguram conexões | Rate limit por conta; `lock_timeout` na linha da conta; repetição idempotente | `AccountErasureIT.concurrentErasuresEraseOnce`, `AccountErasureRateLimitIT` |
| Elevation of privilege: credencial que apaga usuários no Entra | Runbook manual no piloto; automação só com ADR própria | Item de revisão no runbook (não automatizável hoje) |
| Retenção falha em silêncio | Métrica de vencidos por job; alerta | `RetentionJobIT.<módulo>DeletesOnlyExpiredRows` (on/off point no prazo), `twoReplicasPurgeWithoutConflict`, `overdueRowsAreMeasured` |

## 6. Recomendação

**Bottom line:** apagar na hora, numa transação só, tudo o que é da pessoa, por uma interface que cada módulo
implementa (`identity.AccountErasure`), e manter a conta como tombstone sem identidade enquanto assentos e
denúncias de outras pessoas a referenciarem. O Entra fica num runbook manual registrado na mesma transação.
**Motivo:** é a única opção sem estado intermediário nem órfão que respeita as fronteiras dos módulos e não
apaga o histórico de B nem a evidência da moderação; cabe no monólito de um banco sem a outbox, que ainda não
existe. **Custo principal:** uma regra por tabela (o que sai, o que fica) que todo módulo novo precisa seguir,
cobrada pela fitness function, e o resíduo de reidentificação pelo tombstone enquanto ele existir.

### Plano em fatias

| # | Fatia | Pronto quando |
|---|---|---|
| 1 | **Tracer bullet da exclusão:** migration (`erased_at`, `erased_identity`, `account_erasure_task`), `identity.AccountErasure` implementada por `profiles`, `trustsafety`, `events` e `connections` (o `matching` mantém tudo); `DELETE /api/me` com CSRF e reautenticação; sessões apagadas; tela no `duora-web` | Testes de Spoofing, Tampering, Repudiation e "tudo ou nada" da tabela STRIDE verdes e vistos falhando com a mitigação desligada; `DenyByDefaultIT`; spec OpenAPI, Spectral, `oasdiff` e Schemathesis verdes |
| 2 | **Fitness function** de cobertura das FKs e varredura pós-exclusão | `everyTableReferencingAccountHasAnEraser` falha ao criar uma tabela de teste com FK sem eraser; `nothingButTheAllowedReferencesRemain` verde |
| 3 | **Exportação** `GET /api/me/export` por interface publicada em cada módulo | Conjunto exato de chaves; nada de terceiros; reautenticação e rate limit testados |
| 4 | **Chat** (quando o módulo entrar): fechamento na exclusão e expurgo normal | Testes de chat da tabela STRIDE verdes |
| 5 | **Passos externos:** job das tarefas, métrica e alerta, runbook do Entra em `infra/entra/` | `ErasureTaskJobIT` verde; runbook executado uma vez em homologação com um usuário de teste |
| 6 | **Retenção:** um job por módulo com os prazos aprovados e métrica de vencidos; limpeza de tombstones | `RetentionJobIT` de cada módulo com on/off point no prazo e duas réplicas |
| 7 | **Moderação × exclusão:** hash retido para conta denunciada e ligação na volta (depende da moderação, ADR 0015 pendência 1) | Testes de evasão verdes |
| 8 | **Operação e documentação:** runbook de restore com reaplicação das exclusões, política de privacidade (prazos, backups, logs, operadores, transferência), README | Restore ensaiado em servidor novo com uma exclusão reaplicada; README e política publicados |

## 7. Perguntas

### Para o usuário (produto e arquitetura)

1. Aceita a estratégia (c), tombstone com dados próprios apagados na hora, em vez de (a) hard delete total ou (b)
   soft delete com janela?
2. Janela de arrependimento: exclusão imediata (recomendado) ou desativação por N dias antes de apagar?
3. Decisões de B sobre A: apagar na exclusão de A (recomendado) ou manter até a retenção das decisões?
4. Bloqueios recebidos por A: apagar (recomendado) ou manter no tombstone?
5. Chat de quem apagou a conta: manter até o expurgo normal para o par poder denunciar (recomendado) ou apagar na
   hora?
6. Entra: runbook manual no piloto (recomendado) ou automação pelo Graph já, aceitando a credencial que apaga
   usuários?
7. Os prazos da seção 4 (inscrições e rodadas 90 dias, decisões 7 dias, fila de espera 12 meses) servem como
   ponto de partida?
8. Conta inativa: apagar depois de N meses sem login (exige um canal de aviso, que hoje não existe) ou manter
   até a pessoa pedir?
9. Reautenticação: 10 minutos está bom? A porta bearer fica sem exclusão até existir app móvel?
10. A exportação entra junto com a exclusão (fatia 3) ou só depois do piloto?

### Para o apoio jurídico

1. Bases legais por finalidade, em especial para dado sensível (art. 11): consentimento destacado ou outra
   hipótese para inscrição, decisão, conexão e chat? Isso decide se "revogar o consentimento" existe como operação.
2. O tombstone (id sem emissor, sujeito nem perfil) conta como dado anonimizado (art. 12) mesmo que o par do
   encontro ainda consiga ligá-lo a uma pessoa?
3. Retenção de denúncias e evidências depois da exclusão de quem denunciou ou foi denunciado: base (arts. 7º, VI
   e IX; 11, II, "d" e "g"; 16) e prazo.
4. O Marco Civil, art. 15 (registros de acesso por 6 meses), se aplica ao Duora no piloto e depois? Se sim, a
   retenção de logs sobe e esses registros não saem na exclusão.
5. Prazo para atender a exclusão e a exportação (art. 19 fala em 15 dias para a declaração completa) e se o
   backup de até 14 dias com restore controlado é aceitável.
6. Até onde vai o direito de acesso quando o dado envolve terceiros (bloqueios e denúncias recebidos)?
7. Suspeita de menor ([ADR 0011](0011-conta-e-perfil.md), pendência 3): reter algo de quem tentou se cadastrar
   com data de menor ou de conta denunciada como `SUSPECTED_MINOR` que se excluiu?
8. Transferência internacional (arts. 33 a 36) para Entra e Azure nos EUA: instrumento e o que vai para a
   política de privacidade; registro das operações (art. 37) e encarregado (art. 41).

## Consequências

- Todo módulo que guarda dado de uma conta passa a implementar `identity.AccountErasure` (e a interface de
  exportação), e a fitness function quebra o build se esquecer.
- `account.issuer` e `account.subject` deixam de ser `not null`; o resolver da conta ganha a consulta ao
  `erased_identity`.
- A linha de `account` pode existir sem pessoa: quem mostrar outra pessoa (resumo do perfil) precisa tratar a
  conta sem perfil como "conta excluída".
- Nasce uma fila durável (`account_erasure_task`) antes da outbox da [ADR 0009](0009-outbox-e-eventos.md); se a
  outbox entrar, as tarefas podem migrar para ela.
- Novo segredo no Key Vault (chave do HMAC), com boot falhando sem ele.
- Restore de backup ganha um passo obrigatório (reaplicar exclusões).
- Pendências fechadas por esta ADR quando aceita: 0011 (2), 0015 (2, em parte), 0016 (8), 0019 (7 e 8) e a
  parte de exclusão da 0021.

## Compliance

Esta ADR é proposta. Os testes da seção 5 são o critério de aceite das fatias; cada um precisa ser visto
falhando com a mitigação desligada. Fronteira entre módulos: `ArchitectureTest`
(`modulesUseOnlyPublishedApisOfOtherModules`, `modulesAreFreeOfCycles` e a nova
`everyTableReferencingAccountHasAnEraser`). Contrato pela [ADR 0012](0012-contrato-openapi.md).

## Fontes (consultadas em 2026-10-08)

- LGPD (Lei 13.709/2018), arts. 5º, 6º, 7º, 11, 12, 16, 18, 19, 33 a 37 e 41:
  <https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm>
- Marco Civil da Internet (Lei 12.965/2014), art. 15:
  <https://www.planalto.gov.br/ccivil_03/_ato2011-2014/2014/lei/l12965.htm>
- Código Civil, art. 206, § 3º, V: <https://www.planalto.gov.br/ccivil_03/leis/2002/l10406compilada.htm>
- A confirmar na documentação da Microsoft antes da fatia 5: permissão do Graph para apagar usuários, lixeira de
  usuários apagados no External ID e presença de `auth_time` no ID token.
