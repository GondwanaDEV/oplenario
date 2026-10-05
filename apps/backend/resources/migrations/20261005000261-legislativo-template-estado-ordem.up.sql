-- Documenta `legislativo.template_estado.ordem` onde o schema e' lido. So' COMMENT: nao toca dado nenhum.
--
-- (1) Muda dado existente? Nao. COMMENT ON COLUMN so' mexe no catalogo do Postgres; nenhum template, estado ou
--     proposicao e' lido nem escrito.
-- (2) Pode falhar conforme o dado ou o relogio? Nao. Nao ha' predicado, indice, constraint nem data.
-- (3) Enxerga todas as Casas? Nao le linha nenhuma, entao a RLS (FORCE) nao entra.
--
-- Por que NAO ha' UNIQUE (template, ordem): o DEFAULT e' 0 e os ritos ja' gravados (semente, fixtures) tem varias
-- etapas em 0; um indice unico falharia no deploy ou exigiria reescrever dado de Casa. A unicidade e' conferida no
-- save (`db/tramitacao/criar-estado!`, regra pura `logic/rito/ordem-repetida`) e a leitura da faixa continua
-- defensiva: so' usa a ordem declarada quando ela distingue todas as etapas, senao cai na topologia das transicoes.
COMMENT ON COLUMN legislativo.template_estado.ordem IS
  'Posicao da ETAPA (estado nao terminal) na linha do rito, do estado inicial ao ultimo passo. 0 = nao declarada (default): a faixa Onde esta a materia cai na ordem topologica das transicoes. > 0 = declarada, e UNICA entre as etapas do template (conferido em criar-estado!, nao pelo banco). Desfecho (terminal) nao entra na linha: sua ordem so serve para listar. Ordem repetida e ignorada pela leitura (logic/rito).';
