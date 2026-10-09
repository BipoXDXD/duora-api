-- O que a API pediu ao banco durante o cenário (pg_stat_statements, zerado antes do k6). Uma linha por
-- comando: chamadas|média em ms|total em ms|texto. Fora as consultas do próprio amostrador.
select calls, round(mean_exec_time::numeric, 3), round(total_exec_time::numeric), left(regexp_replace(query, '\s+', ' ', 'g'), 110)
from pg_stat_statements
where dbid = (select oid from pg_database where datname = 'duora')
  and query !~* 'pg_stat_(activity|statements|database)'
order by calls desc
limit 25;
