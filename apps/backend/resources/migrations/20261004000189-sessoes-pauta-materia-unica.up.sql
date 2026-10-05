-- A mesma materia nao entra duas vezes (ATIVA) na pauta da mesma sessao.
--
-- ANTES: `POST /sessoes/:id/pauta/itens` aceitava a mesma proposicao N vezes — nenhum indice nem checagem a barrava
-- (a unica UNIQUE da pauta e' a do container 1:1). Dois cliques, ou duas pessoas da Mesa ao mesmo tempo, deixavam a
-- materia duplicada no telao, na pauta publicada e na votacao.
--
-- AGORA: indice UNICO PARCIAL — a garantia mora no banco, nao so' em checagem de codigo (uma checagem em codigo
-- perde a corrida: dois INSERTs concorrentes leem "nao existe" e passam os dois).
--   * `WHERE ativo`: item retirado (`ativo = false`, a unica forma de remocao — nunca DELETE, Inv.10) nao conta;
--     retirar e incluir de novo e' legitimo. Nada reativa um item (ativo so' vai de true para false), entao nao ha'
--     UPDATE que ressuscite uma duplicata.
--   * `proposicao_id IS NOT NULL`: leitura/comunicado/homenagem nao tem materia (proposicao_id NULL por CHECK) e
--     podem se repetir a vontade.
--   * reordenar nao toca `proposicao_id` nem `ativo`: nao colide.
--   * a mesma materia em OUTRA sessao (1a e 2a discussao) e' outra `pauta_sessao_id`: nao colide.
--   * as versoes publicadas (`pauta_sessao_versao.snapshot`) sao jsonb congelado, outra tabela: nao sao tocadas.
--   * a fase NAO entra na chave: a materia e' uma por sessao, em qualquer fase.
--
-- DADO JA' DUPLICADO. O CREATE UNIQUE INDEX morreria numa base que ja' tem duplicata, e a migration derrubaria o
-- deploy. Decisao: NAO apagar nada. Em cada grupo (Casa, pauta, materia) com mais de um item ativo, o MAIS ANTIGO
-- (criado_em, id) fica; os demais sao RETIRADOS pelo caminho que o proprio sistema usa para retirar (`ativo = false`,
-- a linha continua la') e cada retirada vira uma linha em `pauta_alteracao` (append-only, tipo `exclusao`, com a
-- justificativa e o item mantido em `detalhe`). Nao e' silencioso: a pauta publicada que tinha a duplicata passa a
-- aparecer como "alterada desde a publicacao" (`alterada-desde-a-publicacao?`), que e' o sinal honesto, e o total
-- retirado sai em RAISE NOTICE no log do migrate. Esta retirada NAO e' desfeita pelo `.down.sql`.
--
-- `pauta_item` e `pauta_alteracao` tem FORCE ROW LEVEL SECURITY e a policy compara `ente_id` com o GUC
-- `app.ente_id`, que uma migration nao seta: com FORCE ligado o backfill leria e gravaria ZERO linha em silencio
-- (mesmo cuidado da mig 0067/0182). O par NO FORCE/FORCE abaixo e' obrigatorio; o migratus roda a migration numa
-- transacao, entao uma falha no meio nao deixa o FORCE desligado.
ALTER TABLE sessoes.pauta_item NO FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.pauta_alteracao NO FORCE ROW LEVEL SECURITY;
--;;
DO $$
DECLARE
  retiradas integer;
BEGIN
  WITH ordenado AS (
    SELECT ente_id, id, pauta_sessao_id,
           first_value(id) OVER w AS mantido_id,
           row_number() OVER w AS posicao
      FROM sessoes.pauta_item
     WHERE ativo AND proposicao_id IS NOT NULL
    WINDOW w AS (PARTITION BY ente_id, pauta_sessao_id, proposicao_id ORDER BY criado_em, id)
  ), retirados AS (
    UPDATE sessoes.pauta_item i
       SET ativo = false, atualizado_em = now(), lock_version = i.lock_version + 1
      FROM ordenado o
     WHERE o.posicao > 1 AND i.ente_id = o.ente_id AND i.id = o.id
    RETURNING i.ente_id, i.id, i.pauta_sessao_id, o.mantido_id
  )
  INSERT INTO sessoes.pauta_alteracao
         (ente_id, pauta_sessao_id, pauta_item_id, tipo, justificativa, detalhe, efetivado_em)
  SELECT ente_id, pauta_sessao_id, id, 'exclusao',
         'Materia repetida na mesma pauta: o item mais antigo foi mantido (migration 20261004000189).',
         jsonb_build_object('motivo', 'materia_duplicada', 'item_mantido', mantido_id::text,
                            'migration', '20261004000189'),
         now()
    FROM retirados;
  GET DIAGNOSTICS retiradas = ROW_COUNT;
  RAISE NOTICE 'pauta_item: % item(ns) duplicado(s) retirado(s) da pauta (ativo = false); nenhuma linha apagada.', retiradas;
END $$;
--;;
ALTER TABLE sessoes.pauta_alteracao FORCE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.pauta_item FORCE ROW LEVEL SECURITY;
--;;
CREATE UNIQUE INDEX IF NOT EXISTS uq_pauta_item_materia_ativa
  ON sessoes.pauta_item (ente_id, pauta_sessao_id, proposicao_id)
  WHERE ativo AND proposicao_id IS NOT NULL;
