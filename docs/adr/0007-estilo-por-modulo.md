# 0007. Estilo de arquitetura por tipo de módulo

- **Status:** Aceita
- **Data:** 2026-10-05

## Contexto

O Duora é um monólito modular (plano §3). Os módulos têm complexidade muito diferente: a waitlist
é CRUD, enquanto pareamento, minijogos, conexões e moderação concentram as regras do produto. A
waitlist tem `@Entity` e um repositório Spring Data dentro de `domain`, e o `ArchitectureTest` só
proibia `domain` de depender de `api`. Os próximos módulos (`profiles`, `trustsafety`) precisam
de uma regra clara antes do primeiro arquivo.

Alternativas consideradas:

| Opção | Prós | Contras |
|---|---|---|
| **Estilo pela classificação do subdomínio (Learning DDD)** | Disciplina onde há regra de negócio; CRUD sem cerimônia; a waitlist já está conforme | Dois estilos no repositório; cada módulo precisa ser classificado |
| Ports & adapters em todos os módulos | Uma regra só, literal | Interface e adapter a mais em todo CRUD; a waitlist precisaria de refactor sem ganho |
| Spring Data e JPA permitidos no domínio de todos os módulos | O mais barato | O core fica preso ao framework justamente onde a regra de negócio mora e precisa de testes sem Spring |

## Decisão

Cada módulo é classificado antes de ganhar código:

| Tipo | Módulos | Estilo |
|---|---|---|
| **Core** | `experiences`, `matching`, `connections`, `trustsafety` | Ports & adapters. `domain` sem Spring, Spring Data, web, Jackson nem SDKs; anotações `jakarta.persistence` toleradas (entidade e modelo de persistência são o mesmo objeto). `application` fala com banco e serviços externos por portas; as implementações ficam em `adapter` |
| **Supporting** | `waitlist`, `identity`, `profiles`, `notifications` | Camadas simples: `api` → `application` → `domain`, com Spring Data no `domain` |
| **Infraestrutura** | `config` | Composition root e infraestrutura compartilhada (segurança, sessão, relógio, rate limit), sem regra de negócio |

Em todos, `domain` não depende de `api`, `application` nem `adapter`, e `application` não depende
de `api` nem `adapter`.

O motivo técnico é aplicar a regra da dependência onde ela se paga: o domínio do core testado sem
Spring ([ADR 0003](0003-estilo-de-testes.md)). O motivo de negócio é gastar o tempo de um dev solo
nas regras que diferenciam o produto, e não em cerimônia no CRUD.

## Consequências

- A classificação mora em `ArchitectureTest`. Módulo novo fora das listas quebra o build até ser
  classificado aqui e lá.
- Um supporting que ganhar regras de verdade (por exemplo, `profiles` com elegibilidade complexa)
  é reclassificado como core, com refactor e nova revisão desta ADR.
- Comunicação entre módulos: um módulo só depende da API publicada de outro, que são as classes na
  raiz do pacote do módulo (como `identity.AccountId`); as camadas são internas. A regra entrou com
  `identity` e `profiles` ([ADR 0011](0011-conta-e-perfil.md)). O acesso a tabelas de outro módulo
  continua sem verificação automática: fica na revisão de PR e de migration.
- O `GET /api/me` morava em `config` até existir o módulo `profiles`; foi para `profiles/api` junto
  com ele ([ADR 0011](0011-conta-e-perfil.md)). Nenhum controller entra em `config`, salvo o
  `ProblemDetailErrorController`, que é infraestrutura de erro compartilhada por todas as rotas.

## Compliance

`ArchitectureTest`:

- `everyClassBelongsToAClassifiedModule`: toda classe está num módulo classificado ou em `config`;
- `domainDependsOnNoOuterLayer` e `applicationDoesNotDependOnDelivery`: direção das camadas em todos
  os módulos;
- `coreDomainIsFrameworkFree` e `coreApplicationTalksToInfrastructureThroughPorts`: o estilo do
  core;
- `modulesUseOnlyPublishedApisOfOtherModules`: entre módulos, só a API publicada (`ArchitectureRulesTest`
  confere que a regra pega a violação).
