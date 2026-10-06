# 0009. Outbox próprio, eventos de tempo real por chave e particionamento por limiar

- **Status:** Aceita; ainda sem código (entra com o primeiro evento da Etapa 2)
- **Data:** 2026-10-05

## Contexto

Na Etapa 2 (plano §3), cada ação de jogo, decisão ou mensagem grava a mudança no PostgreSQL e
avisa os navegadores pelo Azure Web PubSub. Gravar e depois publicar (dual write) perde o aviso se
o processo cair entre os dois passos. O plano pede outbox com tentativas limitadas, atraso
progressivo, coordenação entre réplicas e revalidação dos destinatários antes de publicar. O
produto é 18+ e tem informação privada por usuário (decisões, pistas): o canal de tempo real não
pode carregar o que um participante não pode ver.

Alternativas para a outbox:

| Opção | Prós | Contras |
|---|---|---|
| **Outbox próprio** (`outbox_events` + relay com `SKIP LOCKED`) | Faz o que o plano pede: revalidar destinatários, backoff, falha reprocessável, coordenação entre réplicas. Testável com o PostgreSQL real | Algumas centenas de linhas e os testes delas |
| Event Publication Registry do Spring Modulith | Ciclo de vida e reenvio prontos | Não tem integração com Web PubSub; schema e semântica de outra biblioteca; traz o Modulith inteiro |
| Os dois: registry para listeners internos, outbox para entrega externa | Cada ferramenta no seu papel | Dois mecanismos parecidos, sem listener interno assíncrono que justifique |

Alternativas para o conteúdo do evento de tempo real:

| Opção | Prós | Contras |
|---|---|---|
| **Por chave** (`type`, `sessionId`, `version`), sem dado privado | O cliente busca pela API, que reautoriza a cada leitura; contrato estável | Uma leitura a mais por evento |
| Com os dados (event-carried state) | Sem ida e volta | Dado privado no canal; evento velho pode sobrescrever estado novo |

Alternativas para tabelas que só crescem (`messages`, `outbox_events`):

| Opção | Prós | Contras |
|---|---|---|
| Particionar desde o início | Expurgo por `DROP` de partição | PK composta, chave obrigatória no `WHERE`, criação de partições: complexidade no piloto |
| Particionar só com dor medida | Nada agora | Converter tabela grande exige reescrita e janela de manutenção |
| **Projeção agora, partição no limiar** | A conta custa pouco e o gatilho fica objetivo | A projeção precisa ser revista com dados reais |

## Decisão

- **Outbox próprio.** A transação que muda o estado grava a linha em `outbox_events`. Um relay
  reserva lotes com `FOR UPDATE SKIP LOCKED` e lease (`claim_token`, `attempts`, `next_attempt_at`),
  publica com backoff exponencial e jitter, marca `published_at` só se ainda tiver o lease, e
  revalida os destinatários (um bloqueio posterior, por exemplo) antes de publicar. Os consumidores
  deduplicam por id e versão. O registry do Modulith só entra se surgir um listener interno
  assíncrono entre módulos.
- **Let it crash do relay** ([ADR 0008](0008-imagem-e-let-it-crash.md)): se a thread do relay morrer,
  a liveness vai para `BROKEN` e a plataforma repõe a réplica. Um teste derruba o relay e confere.
- **Eventos de tempo real por chave:** `type`, `sessionId` e `version`, sem dado privado. O cliente
  busca o estado pela API. Dado no evento só para presença pública (quem está conectado), e só se
  o teste de carga no k6 mostrar que a leitura extra pesa.
- **Particionamento:** ao criar `messages` e `outbox_events`, a migration vem com a projeção de
  volume escrita (linhas por dia no piloto e no crescimento previsto) e o limiar que dispara a
  partição. Antes disso, a outbox apaga em lotes pequenos as linhas já publicadas.

O motivo técnico é entrega pelo menos uma vez sem dual write, e autorização refeita a cada
leitura. O motivo de negócio é que o tempo real é o centro do produto e que um vazamento de decisão
ou pista entre participantes quebra a confiança num app de encontros.

## Consequências

- Nenhum código desta ADR existe ainda; ela fixa o desenho para a Etapa 2.
- O relay exige no mínimo uma réplica sempre ativa (plano §6).
- Monitorar a idade do evento pendente mais antigo e o tamanho da outbox; alertar quando subirem.
- Cada evento custa uma leitura a mais na API, a medir no k6 com o B2s e 100 participantes.

## Compliance

Quando o código existir, cada item ganha teste de integração com PostgreSQL real:

- duas réplicas do relay não publicam o mesmo lote (`SKIP LOCKED` + lease);
- relay com lease vencido não marca `published_at`;
- destinatário bloqueado depois do evento não recebe a publicação;
- o JSON publicado tem exatamente as chaves `type`, `sessionId`, `version` (e o id do evento);
- relay derrubado põe a liveness em `BROKEN`;
- linhas publicadas são apagadas pela limpeza.
