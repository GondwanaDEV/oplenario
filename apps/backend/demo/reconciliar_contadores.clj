(ns reconciliar-contadores
  "Reconcilia `shared.sequencial` com a numeracao que JA' EXISTE nas tabelas de modulo, para uma Casa.

  Por que existe (achado da T2 grupo B — `docs/16-ledger-prontidao.md`): o contador gapless e as linhas
  numeradas sao um PAR de invariante. Quando o contador se perde e as linhas ficam, `proximo!` volta a 1,
  colide na UNIQUE `(ente, ano, sequencial)` e a colisao aborta a transacao inteira — revertendo o proprio
  incremento junto. O contador nunca ultrapassa a colisao: **toda escrita numerada daquele escopo devolve
  500 para sempre, e nao se cura sozinha**. Foi o que aconteceu com a Casa da demo (um fixture da suite
  truncava `shared.sequencial` sem escopo — hoje barrado por `oplenario.kernel.sequencial-lint-test`):
  8 escopos de proposicao numerados ate 15, zero contadores, e as tres portas de entrada do cidadao
  (e-SIC, LGPD, ouvidoria) em 500 para qualquer submissao.

  E por que a semente sozinha nao resolvia: `semear!` e' idempotente POR PULAR (o gate le `ja-semeada?` e
  RELE em vez de reescrever). Ela nunca reprotocola, entao nunca reconstroi o contador. Rodar
  `semear-tudo.sh` num banco nesse estado terminava com exit 0 e a Casa continuava quebrada.

  Este ns e' DEMO/DEV (mora em `demo/`, fora de `src/`), e por isso pode conhecer as tabelas dos modulos
  diretamente — o kernel nao pode, e por isso `sequencial/reconciliar!` recebe o piso como parametro em
  vez de ir buscar. Cada entrada de `escopos` e' uma consulta que devolve `[escopo piso]` para o ente.

  NAO inventa numeracao e NAO abaixa contador: `reconciliar!` usa GREATEST. Rodar duas vezes e' no-op."
  (:require [next.jdbc :as jdbc]
            [oplenario.kernel.sequencial :as sequencial]
            [oplenario.kernel.tenancy :as tenancy]))

(set! *warn-on-reflection* true)

(def ^:private consultas
  "Uma consulta por FAMILIA de escopo. Cada uma devolve linhas `{:escopo <str> :piso <int>}` para o ente.
  O texto do escopo espelha, literalmente, o que o `sequencial/proximo!` daquele `db/` monta — se um
  deles mudar, esta string tem de mudar junto (e o teste `demo.reconciliar-contadores-test` reprova)."
  [;; legislativo/db/proposicao.clj:29 — (str tipo ":" ano)
   ["select tipo || ':' || ano as escopo, max(sequencial) as piso
     from legislativo.proposicoes where ente_id = ? group by tipo, ano"]
   ;; legislativo/db/protocolo_geral.clj:24 — (str "protocolo_geral:" ano)
   ["select 'protocolo_geral:' || ano as escopo, max(numero) as piso
     from legislativo.protocolo_geral where ente_id = ? group by ano"]
   ;; legislativo/db/autografo.clj:23 — (str "autografo:" ano)
   ["select 'autografo:' || ano as escopo, max(numero) as piso
     from legislativo.autografo where ente_id = ? group by ano"]
   ;; legislativo/db/norma.clj:31 — (str "norma:" tipo-norma ":" ano)
   ["select 'norma:' || tipo_norma || ':' || ano as escopo, max(numero) as piso
     from legislativo.norma where ente_id = ? group by tipo_norma, ano"]
   ;; participacao/db/pedido_esic.clj:25
   ["select 'pedido_esic:' || ano as escopo, max(sequencial) as piso
     from participacao.pedido_esic where ente_id = ? group by ano"]
   ;; participacao/db/recurso_esic.clj:26
   ["select 'recurso_esic:' || ano as escopo, max(sequencial) as piso
     from participacao.recurso_esic where ente_id = ? group by ano"]
   ;; participacao/db/solicitacao_titular.clj:26
   ["select 'solicitacao_titular:' || ano as escopo, max(sequencial) as piso
     from participacao.solicitacao_titular where ente_id = ? group by ano"]
   ;; participacao/db/manifestacao_ouvidoria.clj:29
   ["select 'manifestacao_ouvidoria:' || ano as escopo, max(sequencial) as piso
     from participacao.manifestacao_ouvidoria where ente_id = ? group by ano"]
   ;; sessoes/db/sessao.clj:33 — logic/escopo-numeracao = (str "sessao:" sessao-legislativa-id ":" tipo)
   ["select 'sessao:' || sessao_legislativa_id || ':' || tipo_sessao as escopo,
            max(numero_sequencial) as piso
     from sessoes.sessao where ente_id = ? group by sessao_legislativa_id, tipo_sessao"]])

(defn pisos
  "Todos os `{:escopo :piso}` observados nas tabelas de modulo para `ente`. Leitura pura, sem escrita."
  [ds ente]
  (into []
        (comp (mapcat (fn [[sql]] (jdbc/execute! ds [sql ente])))
              (map (fn [r] {:escopo (:escopo r) :piso (int (:piso r))}))
              (filter #(pos? (:piso %))))
        consultas))

(defn reconciliar!
  "Levanta cada contador do `ente` ate' o maior numero ja' gravado no escopo. Idempotente; nunca abaixa.
  Devolve `[{:escopo :piso :valor-final}]`, ordenado por escopo — o chamador imprime, nao adivinha."
  [ds ente]
  (let [observados (pisos ds ente)]
    (->> observados
         (mapv (fn [{:keys [escopo piso]}]
                 (let [final (tenancy/com-tenant* ds ente
                                                  (fn [tx] (sequencial/reconciliar! tx escopo piso)))]
                   {:escopo escopo :piso piso :valor-final final})))
         (sort-by :escopo)
         vec)))
