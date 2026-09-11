(ns oplenario.legislativo.db.parecer-tramitacao
  "ENGINE da tramitacao do PARECER (eixo F) — a state machine PROPRIA do parecer governada pelo MESMO
  motor do eixo C (decisao (b) do workflow de reuso). Reusa, SEM modificar o eixo C:
    - tram/transicoes-de  (mesmas tabelas de template, subject-agnosticas — migration 0016)
    - motor/guarda-dsl    (MESMO avaliador da DSL, disciplina 5)
  e tem estado/historico PROPRIOS (legislativo.pareceres / parecer_transicao_historico). Espelha
  legislativo.db.tramitacao/transicionar! com 3 seams de IO subject-coupled: estado+lock / registrar-
  transicao! / parecer/mudar-estado!.

  [CARRY disc.6 §22.4.3] Paridade com tramitacao.clj:76-115 (FOR UPDATE + CAS + 1o-guard-que-passa)
  e' DELIBERADA, nao copia/cola descuidada. Quando o 3o sujeito chegar (eleicao da Mesa, §22.5), extrair
  o core agnostico (fn ler-estado+lock / fn registrar! / fn muda-estado! / chave-do-amb) e os tres
  passam a usa-lo. Ate la, um fix de concorrencia num lado exige o espelho no outro (teste de paridade)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.db.parecer :as parecer]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.motor.api :as motor]))

(set! *warn-on-reflection* true)

(defn- estado+lock
  "Le estado+lock+objeto do parecer SOB FOR UPDATE: serializa transicoes concorrentes no MESMO parecer
  (espelha tramitacao.clj/estado+lock). Devolve tambem objeto_tipo/objeto_id p/ o payload do evento (que
  o consumer da mae usa p/ achar o objeto — F3.6c)."
  [tx ente-id parecer-id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version :objeto_tipo :objeto_id]
                     :from [:legislativo.pareceres]
                     :where [:and [:= :ente_id ente-id] [:= :id parecer-id]]
                     :for :update}))
      comum/linha->kebab))

(defn registrar-transicao!
  "Append-only: grava a transicao OCORRIDA no historico do parecer (a prova duravel, Inv.10). `efetivado_em`
  = now p/ a linha ser VISIVEL sob a policy RLS (USING exige efetivado_em IS NOT NULL p/ row nativa). `:id`
  e' parametro (gerado no call site, espelho de tramitacao.clj — paridade p/ a extracao do core disc.6)."
  [tx {:keys [id ente-id parecer-id template-id de-estado para-estado gatilho contexto ator-id]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.parecer_transicao_historico
                 :values [{:id id :ente_id ente-id :parecer_id parecer-id :template_id template-id
                           :de_estado de-estado :para_estado para-estado :gatilho gatilho
                           :contexto (some-> contexto comum/->jsonb) :ator_id ator-id :efetivado_em [:now]}]})))

(defn historico-do-parecer [tx ente-id parecer-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :parecer_id :template_id :de_estado :para_estado :gatilho :contexto :ator_id :ocorrido_em]
                  :from [:legislativo.parecer_transicao_historico]
                  :where [:and [:= :ente_id ente-id] [:= :parecer_id parecer-id]]
                  :order-by [[:ocorrido_em :asc]]}))))

(defn transicionar-parecer!
  "ENGINE do parecer (eixo F). Do estado ATUAL, sob `gatilho`, escolhe a 1a transicao candidata cujo GUARD
  passa (guard nil = sempre passa; senao avalia via motor/guarda-dsl — MESMO avaliador, disciplina 5, com
  o `parecer` no amb) e aplica: historico append-only + muda o estado (CAS por lock_version). Atomico na tx
  (o caller abre via Repo/transacao com o RegistroFatos do motor). Devolve {:transicionou? bool :de :para
  :transicao-id :objeto-tipo :objeto-id} — guard que bloqueia TODAS = {:transicionou? false} (dominio normal);
  guard que LANCA (fato ausente, tipo nao-booleano — ver `motor/api/exigir-booleano!`) e o CAS PROPAGAM
  como excecao.

  `alegado` e' o canal do CLIENTE no `amb`, com o mesmo nome que o engine da proposicao usa (fatia 4): o
  vocabulario que um rito enxerga tem de ser UM so' entre os dois sujeitos, senao quem escreve o rito do
  parecer precisa aprender uma segunda convencao para a mesma ideia. Aqui ele e' hoje sempre `{}` — o
  unico caller de borda (`adapters/in/parecer/emitir->dominio`) o fixa vazio, e nenhuma rota o aceita do
  corpo. Continua nomeado assim mesmo assim: no dia em que alguem abrir esse campo ao cliente, a regra ja'
  vai estar escrita sob o nome que declara a procedencia, em vez de precisar ser reescrita junto."
  [tx {:keys [registro ente-id parecer-id template-id gatilho ator-id alegado agora updated-by]}]
  (let [{:keys [estado lock-version objeto-tipo objeto-id] :as row} (estado+lock tx ente-id parecer-id)]
    ;; fail-closed (review F3.6a clojure-MENOR): parecer inexistente NAO se confunde com guard-bloqueado.
    ;; o {:transicionou? false} com objeto nil seria veneno p/ o consumer da mae (F3.6c) achar o objeto.
    (when (nil? row)
      (throw (ex-info "transicionar-parecer!: parecer inexistente no tenant"
                      {:parecer-id parecer-id :ente-id ente-id})))
    (let [candidatas (tram/transicoes-de tx ente-id template-id estado gatilho)
          passa? (fn [t]
                   (or (nil? (:guarda t))
                       ((motor/guarda-dsl {:registro registro :tx tx :expr (:guarda t)
                                           :agora agora :ente-id ente-id})
                        {"parecer" {:id parecer-id :estado estado
                                    :objeto-tipo objeto-tipo :objeto-id objeto-id}
                         "alegado" (or alegado {})})))
          escolhida (first (filter passa? candidatas))]
      (if-not escolhida
        {:transicionou? false :de estado :gatilho gatilho :objeto-tipo objeto-tipo :objeto-id objeto-id}
        (do
          (registrar-transicao! tx {:id (random-uuid) :ente-id ente-id :parecer-id parecer-id :template-id template-id
                                    :de-estado estado :para-estado (:para-estado escolhida)
                                    :gatilho gatilho :contexto alegado :ator-id ator-id})
          (parecer/mudar-estado! tx {:id parecer-id :ente-id ente-id :estado (:para-estado escolhida)
                                     :updated-by updated-by :lock-version lock-version})
          {:transicionou? true :de estado :para (:para-estado escolhida) :transicao-id (:id escolhida)
           :acao (:acao escolhida) :objeto-tipo objeto-tipo :objeto-id objeto-id})))))
