-- A mesma materia nao entra duas vezes (ATIVA) na pauta da mesma sessao — para os itens incluidos depois que esta
-- migration roda (o CORTE, explicado no fim).
--
-- ANTES: `POST /sessoes/:id/pauta/itens` aceitava a mesma proposicao N vezes — nenhum indice nem checagem a barrava
-- (a unica UNIQUE da pauta e' a do container 1:1). Dois cliques, ou duas pessoas da Mesa ao mesmo tempo, deixavam a
-- materia duplicada no telao, na pauta publicada e na votacao.
--
-- AGORA: indice UNICO PARCIAL — quem decide a corrida entre dois INSERTs novos e' o banco (uma checagem so' em codigo
-- perde: os dois leem "nao existe" e passam os dois). A checagem em codigo (`pauta/adicionar-item!`) cobre o outro
-- caso, "ja existe um item ANTIGO", que o indice nao enxerga.
--   * `WHERE ativo`: item retirado (`ativo = false`, a unica forma de remocao — nunca DELETE, Inv.10) nao conta;
--     retirar e incluir de novo e' legitimo. Nada reativa um item (ativo so' vai de true para false).
--   * `proposicao_id IS NOT NULL`: leitura/comunicado/homenagem nao tem materia (proposicao_id NULL por CHECK) e
--     podem se repetir a vontade.
--   * reordenar nao toca `proposicao_id`, `ativo` nem `criado_em`: nao colide.
--   * a mesma materia em OUTRA sessao (1a e 2a discussao) e' outra `pauta_sessao_id`: nao colide.
--   * as versoes publicadas (`pauta_sessao_versao.snapshot`) sao jsonb congelado, outra tabela: nao sao tocadas.
--   * a fase NAO entra na chave: a materia e' uma por sessao, em qualquer fase.
--
-- DADO LEGADO. Esta migration NAO MUTA NENHUM DADO: nenhum item antigo e' retirado e nenhuma linha entra em
-- `pauta_alteracao`. Pauta de sessao encerrada ou ja publicada e' registro historico; reescreve-la por um backfill
-- privilegiado (sem RLS, sem autor) seria apagar prova. Em vez disso o indice so' vale para itens CRIADOS DEPOIS do
-- corte (`criado_em >= <instante da migration>`): o --
-- O CORTE E' O INSTANTE EM QUE ESTA MIGRATION RODA EM CADA BANCO, nao uma data escrita aqui. Uma data fixa acoplava
-- a migration ao relogio do deploy: duplicata criada entre a data escrita e o deploy cairia DENTRO do indice e o
-- CREATE UNIQUE INDEX falharia, derrubando o deploy. Com o instante da propria migration, nenhuma linha existente
-- entra no indice (toda linha ja gravada tem `criado_em` anterior), entao a criacao nunca falha. O valor vira
-- literal constante dentro do predicado (`format %L`), que por isso continua IMMUTABLE.
DO $$
BEGIN
  IF to_regclass('sessoes.uq_pauta_item_materia_ativa') IS NULL THEN
    EXECUTE format(
      'CREATE UNIQUE INDEX uq_pauta_item_materia_ativa
         ON sessoes.pauta_item (ente_id, pauta_sessao_id, proposicao_id)
         WHERE ativo AND proposicao_id IS NOT NULL AND criado_em >= %L::timestamptz',
      now());
  END IF;
END $$;
