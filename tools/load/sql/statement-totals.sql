-- Totais do pg_stat_statements: comandos de verdade (sem BEGIN/COMMIT/ROLLBACK) e controle de transação.
-- Uma linha: comandos|controle de transação.
select coalesce(sum(calls) filter (where query !~* '^(begin|commit|rollback)'), 0),
       coalesce(sum(calls) filter (where query ~* '^(begin|commit|rollback)'), 0)
from pg_stat_statements
where dbid = (select oid from pg_database where datname = 'duora')
  and query !~* 'pg_stat_(activity|statements|database)'
  and query !~* 'pg_stat_statements_reset';
