-- A ESPECIE que o rito governa — `template_tramitacao.tipo`.
--
-- CORRIGE a regra "um rito ativo por Casa" que a mig 0076 pressupunha. Aquela regra tratava 3+ ritos
-- como config ambigua; o dominio diz o contrario — 3+ ritos e' o caso NORMAL de qualquer regimento.
-- Numa terca-feira em Fortaleza entram tres materias por tres caminhos diferentes:
--   projeto de lei  -> protocolada -> CCJ -> pauta -> 1o turno -> 2o turno -> autografo -> sancao
--   requerimento    -> protocolada -> pauta -> votacao -> arquivada   (nunca ve' comissao)
--   mocao           -> protocolada -> pauta -> votacao -> publicada   (sem parecer)
-- Com a regra antiga, a Casa que cadastrasse o proprio regimento nao conseguiria protocolar NADA.
-- A partir daqui o rito e' resolvido PELA ESPECIE da materia (db/proposicao/resolver-rito!).
--
-- NULLABLE, e por duas razoes distintas:
--   (a) template de `sujeito = 'parecer'` nao tem especie de PROPOSICAO nenhuma — a coluna nao se
--       aplica a ele, e NOT NULL o obrigaria a mentir;
--   (b) `tipo IS NULL` num template de proposicao e' o RITO GENERICO da Casa: o que vale para as
--       especies que ela nao quis particularizar. E' tambem o que todo acervo existente tem hoje
--       (nenhum rito jamais declarou especie), entao sem NULL este ALTER exigiria backfill — e
--       escolher a especie de um rito ja' em uso e' decisao regimental da Casa, nao de migration.
-- Sem backfill, sem DEFAULT: quem tinha um rito unico continua com ele como generico, e a resolucao
-- automatica (fatia 1) continua achando-o. A especie so' entra quando a Casa declarar.
--
-- O VOCABULARIO NAO VIRA CHECK AQUI, de proposito. As especies ja' estao cravadas em dois lugares
-- (`proposicao_tipo_conhecido` na mig 0013 e `legislativo.logic/tipos`); um terceiro so' multiplicaria
-- o ponto de drift quando uma especie nova entrar. Quem gateia e' `db/tramitacao/criar-template!`,
-- contra `logic/tipos` — a MESMA fonte do resto do modulo, e no save, como `criar-transicao!` ja' faz
-- com o guard. O que o schema NAO pode deixar passar e' o par incoerente, e isso esta' no CHECK abaixo.
ALTER TABLE legislativo.template_tramitacao
  ADD COLUMN IF NOT EXISTS tipo text;
--;;
-- Par incoerente: especie de PROPOSICAO num template que governa PARECER. Nao e' vocabulario de rito
-- (Inv.4) — `sujeito` ja' e' um discriminador estrutural do produto, com CHECK proprio desde a mig 0019;
-- este so' amarra as duas colunas que ja' existem. Uma linha assim seria invisivel para a resolucao
-- (que filtra `sujeito = 'proposicao'`) e ficaria mentindo no catalogo de config da Casa.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'template_tramitacao_tipo_sujeito_ck') THEN
    ALTER TABLE legislativo.template_tramitacao
      ADD CONSTRAINT template_tramitacao_tipo_sujeito_ck
      CHECK (tipo IS NULL OR sujeito = 'proposicao');
  END IF;
END $$;
--;;
-- Hot-path da resolucao: `protocolar!` consulta (ente_id, sujeito, ativo, tipo) em TODA materia nova.
-- PARCIAL em `ativo` porque rito aposentado nunca e' alvo da resolucao automatica — e a tabela e' config
-- de baixa cardinalidade, entao o indice existe pela convencao de acesso, nao por volume.
CREATE INDEX IF NOT EXISTS idx_template_tramitacao_resolucao
  ON legislativo.template_tramitacao (ente_id, sujeito, tipo) WHERE ativo;
