# 0004. Identificadores UUIDv7 e unicidade com coluna anulável

- **Status:** Aceita
- **Data:** 2026-10-05

## Contexto

A `waitlist_entry` nasceu com `bigint generated always as identity`. As próximas tabelas (perfis,
eventos, sessões de jogo, conexões) terão ids expostos em URL, como `POST /connections/{id}:block`
([ADR 0005](0005-contrato-da-api.md)). Um id sequencial na URL revela volume de cadastro e
convida a enumerar recursos. O PostgreSQL 18 gera UUIDv7 nativamente (`uuidv7()`).

Alternativas consideradas para a chave primária:

| Opção | Prós | Contras |
|---|---|---|
| `bigint identity` interno + UUID público em outra coluna | Chave de 8 bytes em toda FK e índice; o id interno nunca vaza | Duas chaves por tabela e mapeamento em toda leitura; fácil expor o id errado por engano |
| UUIDv4 (`gen_random_uuid()`) como PK | Opaco; gerado em qualquer lugar | Inserção em posição aleatória do índice B-tree, que fragmenta e piora o cache (PostgreSQL Mistakes 5.4) |
| **UUIDv7 (`uuidv7()`) como PK e id público** | Uma chave só; ordenado no tempo, então insere no fim do índice como uma sequence; pode ser gerado fora do banco | 16 bytes em toda FK e índice; o prefixo revela o instante de criação |

Para unicidade composta com coluna opcional (uma denúncia que aponta para mensagem **ou** perfil,
por exemplo), o PostgreSQL trata dois NULL como diferentes, e a `UNIQUE` deixa passar duplicatas.

## Decisão

- **PK `uuid default uuidv7()`**, que também é o id exposto na API. Nada de `bigint` exposto nem
  UUIDv4 por reflexo.
- Quando a entidade nasce pelo JPA, o id é gerado em Java também como v7 (`@UuidGenerator` com
  `VERSION_7`), para que `equals`/`hashCode` por id funcionem antes do `persist`. O default do banco
  atende inserts por SQL, como o `insertIfAbsent` da waitlist.
- O id é identificador, não controle de acesso: toda leitura continua filtrando por dono.
- **`UNIQUE ... NULLS NOT DISTINCT`** quando o NULL significa ausência e duas ausências devem
  colidir. Nunca valor sentinela no lugar do NULL. Todo upsert sobre essa chave tem teste com NULL.

O motivo técnico é uma chave só, ordenada e segura de expor. O motivo de negócio é não revelar,
pela URL, quantos usuários, eventos ou conexões o Duora tem.

## Consequências

- `V3__waitlist_entry_uuidv7_id.sql` troca a PK da waitlist; as linhas existentes recebem ids na
  própria migration.
- FKs e índices ocupam o dobro de uma `bigint`. No volume do piloto, isso não pesa.
- O instante de criação fica legível no id (`uuid_extract_timestamp`). Se algum recurso tiver
  criação sensível, ele usa `gen_random_uuid()` e registra o motivo.
- Ordenar por id equivale a ordenar por criação apenas entre ids do mesmo gerador; para ordem de
  negócio, use a coluna de data.

## Compliance

- `WaitlistEntryRepositoryIT.insertIfAbsentAssignsTimeOrderedUuidId` confere que o id é versão 7.
- `FlywayMigrationIT` sobe o contexto com `ddl-auto=validate`, então a entidade precisa bater com o
  tipo `uuid` da coluna.
- Revisão de migration: PK nova é `uuid default uuidv7()`; `UNIQUE` composta com coluna anulável
  traz `NULLS NOT DISTINCT` ou o motivo de não trazer.
