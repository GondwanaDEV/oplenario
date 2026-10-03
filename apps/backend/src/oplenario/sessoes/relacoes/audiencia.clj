(ns oplenario.sessoes.relacoes.audiencia
  "Funcao de RELACAO da AUDIENCIA PUBLICA (ADR-0021 A4): a prova que a regra de metas fiscais do motor procura.

  `audiencia_publica_realizada(finalidade, competencia)` responde se a Casa REALIZOU a audiencia daquela finalidade
  no quadrimestre que termina na competencia (04, 08, 12 -> `AAAA-Q1|Q2|Q3`, `logic.audiencia/referencia-do-quadrimestre`).
  Realizada = a sessao esta' `encerrada` ou `arquivada` E tem ata publicada (`sessoes.ata`, qualquer versao) — a
  audiencia marcada, aberta ou sem ata nao prova nada ao TCE. Competencia que nao fecha quadrimestre: falso. So' a
  finalidade `metas_fiscais` tem referencia (CHECK da mig 182); LDO, LOA e PPA nao tem prazo no motor (`[GAP]` por LOM)
  e respondem falso.

  Camada de RELACAO (espelha `relacoes/presenca`): HoneySQL direto, `(fn tx arg…)` com a `tx` do tenant primeiro (FORCE
  RLS isola a Casa; `ente` nao e' argumento do DSL). A assinatura tipada vive em `motor/catalogo` (FUNCOES-RELACAO); o
  assert de costura do boot casa a fn com ela."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.sessoes.logic.audiencia :as logic-aud]))

(set! *warn-on-reflection* true)

(def ^:private estados-realizada (vec (sort logic-aud/estados-realizada)))

(defn audiencia-publica-realizada?
  "Houve audiencia publica de `finalidade` no quadrimestre que termina na `competencia` ({:ano :mes}), encerrada e com
  ata publicada? Booleano."
  [tx finalidade competencia]
  (boolean
   (when-let [referencia (logic-aud/referencia-do-quadrimestre competencia)]
     (jdbc/execute-one! tx
       (sql/format {:select [[1 :ok]]
                    :from [[:sessoes.audiencia :a]]
                    :join [[:sessoes.sessao :s] [:and [:= :s.ente_id :a.ente_id] [:= :s.id :a.sessao_id]]]
                    :where [:and [:= :a.finalidade finalidade] [:= :a.referencia referencia]
                            [:in :s.estado estados-realizada]
                            [:exists {:select [1] :from [[:sessoes.ata :t]]
                                      :where [:and [:= :t.ente_id :a.ente_id] [:= :t.sessao_id :a.sessao_id]]}]]
                    :limit 1})))))

(def relacoes
  {"audiencia_publica_realizada" audiencia-publica-realizada?})
