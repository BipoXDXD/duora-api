# Pendências com o usuário

Lista consolidada, em 2026-10-08, de tudo que as ADRs, a auditoria de segurança e o README de infra deixam para o
usuário decidir. Cada linha aponta a origem; o texto completo (opções, prós e contras) mora lá. Quando uma
decisão for tomada, atualize a ADR de origem com uma nota datada e a linha daqui.

Status: **aberta** (nada decidido), **parcial** (parte resolvida, o resto aberto), **resolvida**.

## Por onde começar

Ordem sugerida, porque cada item destrava os seguintes:

1. **Custo da ADR 0014** (Infraestrutura, 1): sem isso não há ambiente para o piloto.
2. **ADR 0023, exclusão de conta e retenção** (Segurança e LGPD, 1): oito ADRs deixaram essa pendência, e a ADR 0023 as fecha
   com 10 decisões suas e 8 perguntas para o apoio jurídico.
3. **ADR 0024, primeiro jogo** (Produto, 27): 11 decisões; é o que falta para fechar a etapa 2 do plano.
4. **Auditoria, pendência 1** (Segurança e LGPD, 5): limite por conta em `:block`, `:unblock` e `PATCH` do perfil.

## Produto

| # | Pendência | Origem | Status |
|---|---|---|---|
| 1 | Verificação real de idade (documento, selfie, provedor), em que momento, e o que acontece com quem não passa | [0011](adr/0011-conta-e-perfil.md) (1) | aberta |
| 2 | Correção da data de nascimento, hoje travada (`409`): canal de suporte ou revisão manual | [0011](adr/0011-conta-e-perfil.md) (4) | aberta |
| 3 | Moderação: quem modera, estados da denúncia (em análise, procedente...), ações (aviso, suspensão, banimento); hoje só existe `OPEN` e nada lê a fila | [0015](adr/0015-bloqueio-e-denuncia.md) (1) | aberta |
| 4 | Efeito do bloqueio nos outros módulos: perfil e presença no mesmo evento (pareamento, conexão e chat já respeitam) | [0015](adr/0015-bloqueio-e-denuncia.md) (5) | parcial |
| 5 | Cobrança de evento (reserva com prazo, reembolso), prevista para a etapa 4 | [0016](adr/0016-eventos-e-inscricoes.md) (1) | aberta |
| 6 | Elegibilidade além de perfil completo e 18+ (região × local, faixa etária, preferências, faltas) | [0016](adr/0016-eventos-e-inscricoes.md) (2) | aberta |
| 7 | Local e formato do evento (presencial, online, endereço) | [0016](adr/0016-eventos-e-inscricoes.md) (3) | aberta |
| 8 | Lista de espera, prazo para cancelar a inscrição e política de falta | [0016](adr/0016-eventos-e-inscricoes.md) (4) | aberta |
| 9 | Aviso aos inscritos quando o evento é cancelado (depende de outbox e notificações) | [0016](adr/0016-eventos-e-inscricoes.md) (5) | aberta |
| 10 | Edição de evento publicado (horário, capacidade) | [0016](adr/0016-eventos-e-inscricoes.md) (6) | aberta |
| 11 | Mostrar vagas restantes ou "lotado" antes de tentar | [0016](adr/0016-eventos-e-inscricoes.md) (7) | aberta |
| 12 | Critérios de compatibilidade do pareamento (gênero, orientação, idade, interesses, região) | [0017](adr/0017-pareamento.md) (1) | aberta |
| 13 | Presença: check-in no início ou a cada rodada (hoje todos os inscritos entram no sorteio) | [0017](adr/0017-pareamento.md) (2) | aberta |
| 14 | Quem sobra: fica de fora (implementado), trio ou par com a equipe | [0017](adr/0017-pareamento.md) (3) | aberta |
| 15 | Duração e encerramento da rodada; disparo manual (provisório) ou agendado | [0017](adr/0017-pareamento.md) (4) | aberta |
| 16 | Bloqueio ou denúncia durante a rodada: avisar e repor o par (o chat já fecha) | [0017](adr/0017-pareamento.md) (5) | parcial |
| 17 | O que a pessoa vê do par (nome, foto, nada) e se o ADMIN vê quem formou par com quem | [0017](adr/0017-pareamento.md) (6) | aberta |
| 18 | Elegibilidade no momento do sorteio (conta suspensa, inscrição cancelada, perfil incompleto) | [0017](adr/0017-pareamento.md) (7) | aberta |
| 19 | Evento cancelado durante a rodada: encerrar os pares e avisar | [0017](adr/0017-pareamento.md) (8) | aberta |
| 20 | "Sua dupla saiu": o produto não define o que é sair de uma rodada | [0017](adr/0017-pareamento.md) (9), [0021](adr/0021-chat-temporario-e-reconexao.md) (pergunta 10) | aberta |
| 21 | Decisão mutável até um prazo, em vez de final | [0019](adr/0019-decisao-privada-e-conexoes.md) (1) | aberta |
| 22 | Prazo para decidir sobre o par (hoje vale enquanto a rodada existir) | [0019](adr/0019-decisao-privada-e-conexoes.md) (2), [auditoria](security-audit-2026-10.md) (3) | aberta |
| 23 | Bloqueio de quem já está conectado: apagar, esconder ou manter (hoje mantém) | [0019](adr/0019-decisao-privada-e-conexoes.md) (3) | aberta |
| 24 | O que a conexão mostra além do id e da data (nome, foto) | [0019](adr/0019-decisao-privada-e-conexoes.md) (4) | aberta |
| 25 | Aviso de "vocês se conectaram" e "desconectar" (e o que isso faz com o chat) | [0019](adr/0019-decisao-privada-e-conexoes.md) (5, 6) | aberta |
| 26 | Chat temporário: produto da seção 1 da ADR (defaults aceitos em 2026-10-08) | [0021](adr/0021-chat-temporario-e-reconexao.md) | resolvida |
| 27 | Primeiro jogo: qual (A, B ou C), quem escolhe por rodada, presença, "já respondeu", cronômetro, baralho, retenção das respostas, fim do jogo e decisão privada (decisões 1 a 8) | [0024](adr/0024-primeiro-jogo-e-modulo-experiences.md) | aberta |

## Segurança e LGPD

| # | Pendência | Origem | Status |
|---|---|---|---|
| 1 | Exclusão de conta (tombstone, janela de arrependimento, o que apagar do par, chat de quem saiu, runbook ou Graph, prazos, conta inativa, reautenticação, exportação) e as 8 perguntas ao apoio jurídico (bases legais, art. 12, Marco Civil art. 15, transferência internacional) | [0023](adr/0023-exclusao-de-conta-e-retencao.md); fecha as pendências 2 da [0011](adr/0011-conta-e-perfil.md), 2 da [0015](adr/0015-bloqueio-e-denuncia.md), 8 da [0016](adr/0016-eventos-e-inscricoes.md), 7 e 8 da [0019](adr/0019-decisao-privada-e-conexoes.md) | aberta |
| 2 | Suspeita de menor: fluxo de proteção, o que guardar de quem tentou com data de menor, base legal | [0011](adr/0011-conta-e-perfil.md) (3), [0015](adr/0015-bloqueio-e-denuncia.md) (3) | aberta |
| 3 | Retenção de denúncias e evidências, e de bloqueios (desbloquear apaga o histórico) | [0015](adr/0015-bloqueio-e-denuncia.md) (2, 4) | aberta |
| 4 | Retenção das decisões privadas (dado sensível sem uso depois da conexão) | [0019](adr/0019-decisao-privada-e-conexoes.md) (7) | aberta |
| 5 | Rate limit em `PATCH /api/me/profile`, `:block` e `:unblock` (recomendação: só `:block` e `:unblock`, cerca de 60 por hora) | [auditoria](security-audit-2026-10.md) (1) | aberta |
| 6 | Ids de outra pessoa nos logs de DEBUG (`ConnectionsResponse`, `PairingResponse`, `BlockedAccountsResponse`) e a regra "relação entre pessoas não vai para o log" na ADR 0013 | [auditoria](security-audit-2026-10.md) (2) | aberta |
| 7 | `404` de conta inexistente em `:block` e `POST /api/reports` (oráculo de existência): aceitar ou responder igual | [auditoria](security-audit-2026-10.md) (4) | aberta |
| 8 | Expiração absoluta da sessão web (hoje só 30 min de inatividade) | [auditoria](security-audit-2026-10.md) (5) | aberta |
| 9 | Brute force e lockout do login ficam no Entra: conferir o Smart Lockout no tenant | [auditoria](security-audit-2026-10.md) (6) | aberta |
| 10 | Acrescentar a linha "decisão no log" ao STRIDE da ADR 0019 | [auditoria](security-audit-2026-10.md) (7) | aberta |
| 11 | Chat: base legal e política de privacidade para conversa como dado sensível | [0021](adr/0021-chat-temporario-e-reconexao.md) (4) | aberta |
| 12 | Chat: aceitar o resíduo do bloqueio concorrente (milissegundos) ou fechá-lo com lock compartilhado | [0021](adr/0021-chat-temporario-e-reconexao.md) (2) | aberta |
| 13 | Chat: a reabertura do SSE renova os 30 min de inatividade da sessão? (proposto: não) | [0021](adr/0021-chat-temporario-e-reconexao.md) (3) | aberta |
| 14 | O módulo `chat` entra na próxima rodada de auditoria de segurança | [auditoria](security-audit-2026-10.md) (nota de 2026-10-08) | aberta |

## Infraestrutura e custo

| # | Pendência | Origem | Status |
|---|---|---|---|
| 1 | Aprovar o custo mensal estimado antes do primeiro `apply` | [0014](adr/0014-infraestrutura-do-piloto-na-azure.md) (1) | aberta |
| 2 | Região e residência dos dados | [0014](adr/0014-infraestrutura-do-piloto-na-azure.md) (2) | resolvida (North Central US, revisão de 2026-10-06); falta confirmar o PostgreSQL 18 no primeiro `what-if` |
| 3 | Antes de produção, migrar para assinatura própria fora do tenant da universidade | [0014](adr/0014-infraestrutura-do-piloto-na-azure.md) (revisão de 2026-10-06) | aberta |
| 4 | Segredo do cliente web e redirect URIs por ambiente | [0014](adr/0014-infraestrutura-do-piloto-na-azure.md) (3) | aberta |
| 5 | Domínio próprio e Static Web Apps ligados ao BFF (o cookie `SameSite=Lax` depende disso); o Bicep não cria o SWA | [0014](adr/0014-infraestrutura-do-piloto-na-azure.md) (4), [infra/azure](../infra/azure/README.md) | aberta |
| 6 | Conferir no primeiro deploy `DUORA_TRUSTED_PROXIES`, TLS `verify-full` e as actions do papel `Duora deployer` | [0014](adr/0014-infraestrutura-do-piloto-na-azure.md) (5) | aberta |
| 7 | Autenticação do banco pelo Entra com identidade gerenciada (elimina as duas senhas) | [0014](adr/0014-infraestrutura-do-piloto-na-azure.md) (6) | aberta |
| 8 | Ligar a telemetria (OTLP e Application Insights) e conferir stack traces longos e amostragem no Container Apps | [0014](adr/0014-infraestrutura-do-piloto-na-azure.md) (7), [0013](adr/0013-logs-estruturados-e-correlation-id.md) | aberta |
| 9 | Conexões de produção: produção estoura as 35 conexões do B1ms numa troca de revisão; reduzir o pool (8), limitar a 2 réplicas ou subir para B2s (cerca de US$ 77 a mais por mês) | [0014](adr/0014-infraestrutura-do-piloto-na-azure.md) (atualização de 2026-10-08, 1) | aberta |
| 10 | CPU e regra de escala: o k6 mediu 1 vCPU, a homologação roda com 0,5; rodar o k6 na homologação | [0014](adr/0014-infraestrutura-do-piloto-na-azure.md) (atualização de 2026-10-08, 2), [0022](adr/0022-teste-de-carga-com-k6.md) (1) | aberta |
| 11 | `connectionTimeout` de 30 s do Hikari e tamanho do pool (o teto do limitador já subiu para 3 s) | [0016](adr/0016-eventos-e-inscricoes.md) (9), [0022](adr/0022-teste-de-carga-com-k6.md) (2) | parcial |
| 12 | Aquecimento da JVM antes da readiness | [0022](adr/0022-teste-de-carga-com-k6.md) (3) | aberta |
| 13 | Cenários de carga que faltam (chat, reconexão, outbox, restauração do banco, sorteio de 200 candidatos) | [0022](adr/0022-teste-de-carga-com-k6.md) (4) | aberta |

## Contrato e arquitetura

| # | Pendência | Origem | Status |
|---|---|---|---|
| 1 | Aviso do chat por `NOTIFY` fora da outbox, contra a regra da ADR 0009 para avisos internos | [0021](adr/0021-chat-temporario-e-reconexao.md) (1), [0009](adr/0009-outbox-e-eventos.md) | aberta |
| 2 | Forma da jogada (`PUT` por chave de negócio ou `POST .../actions` com `Idempotency-Key`), lock pessimista no lugar da versão otimista do plano, e a consulta "dupla ativa" no `matching` | [0024](adr/0024-primeiro-jogo-e-modulo-experiences.md) (9, 10, 11) | aberta |
| 3 | Adotar o jqwik (o spike de 2026-10-07 funcionou no JUnit 6); o PIT não tem spike | [0017](adr/0017-pareamento.md) (10), [0003](adr/0003-estilo-de-testes.md) | aberta |
| 4 | Transporte do chat: polling e depois SSE, Web PubSub só se o k6 exigir | [0021](adr/0021-chat-temporario-e-reconexao.md) | resolvida em 2026-10-08 |

## ADRs ainda provisórias ou propostas

Decididas sem o usuário ou só propostas; vale ratificar ou ajustar, mesmo sem pendência específica.

| ADR | Status |
|---|---|
| [0011](adr/0011-conta-e-perfil.md), [0015](adr/0015-bloqueio-e-denuncia.md), [0016](adr/0016-eventos-e-inscricoes.md), [0017](adr/0017-pareamento.md), [0019](adr/0019-decisao-privada-e-conexoes.md), [0022](adr/0022-teste-de-carga-com-k6.md) | Aceitas, provisórias, implementadas |
| [0012](adr/0012-contrato-openapi.md), [0013](adr/0013-logs-estruturados-e-correlation-id.md), [0014](adr/0014-infraestrutura-do-piloto-na-azure.md), [0018](adr/0018-erros-de-campo-no-problem-detail.md), [0020](adr/0020-motivo-das-recusas-no-problem-detail.md) | Propostas ou aceitas para revisão; 0012, 0013, 0018 e 0020 já estão implementadas, a 0014 não foi aplicada |
| [0021](adr/0021-chat-temporario-e-reconexao.md) | Aceita em 2026-10-08 (transporte e defaults de produto); API das fatias 1 e 2 na `main`, o resto pendente |
| [0023](adr/0023-exclusao-de-conta-e-retencao.md), [0024](adr/0024-primeiro-jogo-e-modulo-experiences.md) | Propostas, sem código |

## Resolvidas desde 2026-10-05

| O quê | Por | Onde |
|---|---|---|
| Região da Azure e residência dos dados | revisão de 2026-10-06 | [0014](adr/0014-infraestrutura-do-piloto-na-azure.md) |
| Alerta de reinícios e o que entra na readiness | #10 | [0008](adr/0008-imagem-e-let-it-crash.md), [0014](adr/0014-infraestrutura-do-piloto-na-azure.md) |
| Front distinguir `403` de perfil e `409` de evento (motivo da recusa) | #24 | [0020](adr/0020-motivo-das-recusas-no-problem-detail.md) |
| Rodada atual no evento (`currentRound`) | #24 | [0017](adr/0017-pareamento.md) |
| Limite por conta nas rotas de inscrição, rodada e decisão | #22, #25 | [0016](adr/0016-eventos-e-inscricoes.md), [0017](adr/0017-pareamento.md), [0019](adr/0019-decisao-privada-e-conexoes.md) |
| Lista de eventos do ADMIN | #38 | [0016](adr/0016-eventos-e-inscricoes.md) |
| `roles` em `GET /api/me` | #38 | [0002](adr/0002-front-web-com-bff.md) |
| Teto do limitador de 1 s para 3 s | #35 | [0006](adr/0006-rate-limit-no-postgresql.md), [0016](adr/0016-eventos-e-inscricoes.md) |
| Decisão e nome do Entra no log de DEBUG (dois bugs da auditoria) | #37 | [auditoria](security-audit-2026-10.md) |
