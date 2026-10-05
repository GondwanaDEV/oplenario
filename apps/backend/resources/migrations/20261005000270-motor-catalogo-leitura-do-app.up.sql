-- O gatilho de compliance (ADR-0021) roda no request, como o role de runtime, e le o catalogo do motor: o template
-- vigente de cada regra, os feriados da contagem de prazo e o prazo de dominio (builtin `prazo_vigente`, usado pela
-- regra `remessa_mensal_sim` — lido quando o gatilho reavalia a obrigacao de uma remessa aceita pelo TCE). A migration 9 deixou essas tabelas de DOMINIO (sem
-- ente_id, sem RLS) sem grant ao app "ate a F2 ler de fato"; o grant nunca veio, e em producao o gatilho falhava em
-- toda leitura do painel com `permission denied for table template_compliance` (logado e engolido: nenhuma
-- obrigacao era avaliada). Privilegio minimo: SO' LEITURA, e so' das tres tabelas que o runtime le. O catalogo e'
-- de todas as Casas; quem grava nele e' o `migrate`, como o dono (`gatilho-compliance/garantir-catalogo!`).
GRANT SELECT ON motor.template_compliance TO oplenario_app;
--;;
GRANT SELECT ON motor.calendario_feriado TO oplenario_app;
--;;
GRANT SELECT ON motor.prazo_dominio_vigente TO oplenario_app;
