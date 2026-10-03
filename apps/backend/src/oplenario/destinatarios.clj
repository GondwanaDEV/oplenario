(ns oplenario.destinatarios
  "HOST (§22.10) — QUEM RECEBE. Os seams que cruzam `cadastros` (setores, comissoes, vereadores, Mesa) e `identidade`
  (as pessoas com vinculo ativo, os papeis, os nomes) para quem nao pode importa-los:

  - `comunicacao` (ADR-0020): resolver um destino (pessoa, vereador, setor, comissao, todos os setores) nas pessoas que
    ele tem AGORA — a lista que o comunicado congela; as opcoes do formulario; se o ator pode enviar a grupo; o nome de
    quem envia;
  - os AVISOS AUTOMATICOS da caixa do sistema (ADR-0020 fatia 2): os vereadores da Casa (a pauta publicada, em
    `sessoes`) e as pessoas com o papel `juridico` (o pedido de parecer, em `legislativo`).

  A regra comum: so' recebe quem tem vinculo ATIVO de quem trabalha na Casa (nunca cidadao) — quem nao entra no sistema
  nao veria o comunicado. O vereador sem identidade (ou com o vinculo suspenso) nao entra e e' CONTADO em `:sem-acesso`
  para a tela avisar."
  (:require [oplenario.cadastros.components.repositorio :as repo-cad]
            [oplenario.identidade.components.repositorio :as repo-id]))

(set! *warn-on-reflection* true)

(def papeis-que-enviam-a-grupo
  "Eixo 3: a secretaria e o administrador da Casa (alem do vereador membro vigente da Mesa)."
  #{"secretario" "admin_ente"})

(def tipos-de-vinculo-dos-setores
  "\"Todos os setores\" = toda pessoa com vinculo ativo `servidor` ou `admin_ente` (com ou sem setor). Nao inclui
  vereadores: para eles o caminho e' a Mesa, uma comissao ou o vereador."
  #{"servidor" "admin_ente"})

(defn- pessoas-por-id [repo-identidade ente-id]
  (into {} (map (juxt :identidade-id identity)) (repo-id/pessoas-da-casa repo-identidade ente-id)))

(defn- pessoa [p] {:identidade-id (:identidade-id p) :nome (:nome p)})

(defn- da-casa
  "Os `membros` [{:identidade-id :nome?}] que sao pessoas ativas da Casa -> {:pessoas [...] :sem-acesso n}. O nome e' o
  do caminho quando vem (o parlamentar, na comissao), senao o da identidade."
  [pessoas membros]
  (let [com (filter #(and (:identidade-id %) (contains? pessoas (:identidade-id %))) membros)]
    {:pessoas (mapv (fn [m] {:identidade-id (:identidade-id m)
                             :nome (or (:nome m) (:nome (get pessoas (:identidade-id m))))})
                    com)
     :sem-acesso (- (count membros) (count com))}))

(defn resolver-destino
  "Um destino -> {:alvo-nome :pessoas [{:identidade-id :nome}] :sem-acesso n}, ou nil se o alvo nao existe nesta Casa
  (ou o setor esta' desativado, ou a pessoa nao tem vinculo ativo). `hoje` decide a comissao vigente e os membros."
  [{:keys [repo-cadastros repo-identidade]} ente-id hoje {:keys [tipo alvo-id]}]
  (let [pessoas (delay (pessoas-por-id repo-identidade ente-id))]
    (case tipo
      "pessoa"
      (when-let [p (get @pessoas alvo-id)]
        {:alvo-nome (:nome p) :pessoas [(pessoa p)] :sem-acesso 0})
      "vereador"
      (when-let [v (get (repo-cad/identidades-de-vereadores repo-cadastros ente-id [alvo-id]) alvo-id)]
        (assoc (da-casa @pessoas [{:identidade-id (:identidade-id v) :nome (:nome v)}]) :alvo-nome (:nome v)))
      "setor"
      (when-let [s (repo-cad/setor-com-membros repo-cadastros ente-id alvo-id)]
        (when (:ativo s)
          (assoc (da-casa @pessoas (mapv (fn [i] {:identidade-id i}) (:membros s))) :alvo-nome (:nome s))))
      "comissao"
      (when-let [c (repo-cad/comissao-com-membros repo-cadastros ente-id alvo-id hoje)]
        (assoc (da-casa @pessoas (:membros c)) :alvo-nome (:nome c)))
      "todos"
      {:alvo-nome "todos os setores"
       :pessoas (->> (vals @pessoas)
                     (filter #(some tipos-de-vinculo-dos-setores (:tipos %)))
                     (sort-by (juxt :nome (comp str :identidade-id)))
                     (mapv pessoa))
       :sem-acesso 0}
      nil)))

(defn opcoes-de-destino
  "As opcoes do formulario de envio (GET /comunicados/destinos). Os grupos so' quando `grupos?`."
  [{:keys [repo-cadastros repo-identidade]} ente-id hoje grupos?]
  (let [ps (repo-id/pessoas-da-casa repo-identidade ente-id)
        pessoas (into {} (map (juxt :identidade-id identity)) ps)
        roster (repo-cad/roster-da-casa repo-cadastros ente-id hoje)
        idents (repo-cad/identidades-de-vereadores repo-cadastros ente-id (map :vereador-id roster))]
    {:pessoas (mapv pessoa ps)
     :vereadores (mapv (fn [l] (let [v (get idents (:vereador-id l))]
                                 {:id (:vereador-id l)
                                  :nome (or (not-empty (:nome-parlamentar l)) (:nome l))
                                  :tem-acesso (boolean (and (:identidade-id v) (contains? pessoas (:identidade-id v))))}))
                       (sort-by (juxt #(or (not-empty (:nome-parlamentar %)) (:nome %)) (comp str :vereador-id)) roster))
     :setores (if grupos?
                (->> (repo-cad/listar-setores repo-cadastros ente-id)
                     (filter :ativo)
                     (mapv (fn [s] {:id (:id s) :nome (:nome s)
                                    :membros (count (filter #(contains? pessoas %) (:membros s)))})))
                [])
     :comissoes (if grupos?
                  (mapv (fn [c] (let [{:keys [pessoas sem-acesso]} (da-casa pessoas (:membros c))]
                                  {:id (:id c) :nome (:nome c) :membros (count pessoas) :sem-acesso sem-acesso}))
                        (repo-cad/comissoes-com-membros repo-cadastros ente-id hoje))
                  [])
     :todos-os-setores (if grupos? (count (filter #(some tipos-de-vinculo-dos-setores (:tipos %)) ps)) 0)}))

(defn pode-enviar-a-grupos?
  "Eixo 3: `secretario` ou `admin_ente`, ou o vereador que e' membro vigente da Mesa (`cargo-na-mesa` = o cargo dele
  na Mesa de hoje, ou nil)."
  [cargo-na-mesa ator]
  (boolean (or (some papeis-que-enviam-a-grupo (map name (:papeis ator)))
               (when (and cargo-na-mesa (:identidade-id ator))
                 (some? (cargo-na-mesa (:ente-id ator) (:identidade-id ator)))))))

(defn seams-de-comunicacao
  "Os seams que `comunicacao` recebe (ver `oplenario.comunicacao.controllers`). `hoje` = (fn [] -> LocalDate) no dia
  civil da Casa; `cargo-na-mesa` = (fn [ente-id identidade-id] -> cargo | nil)."
  [{:keys [repo-cadastros repo-identidade hoje cargo-na-mesa] :as repos}]
  {:resolver-destino (fn [ente-id destino] (resolver-destino repos ente-id (hoje) destino))
   :pode-enviar-a-grupos? (fn [ator] (pode-enviar-a-grupos? cargo-na-mesa ator))
   :destinos (fn [ente-id grupos?] (opcoes-de-destino repos ente-id (hoje) grupos?))
   :nome-de (fn [ente-id identidade-id]
              (:nome (get (pessoas-por-id repo-identidade ente-id) identidade-id)))})

;; ---------- os avisos automaticos (ADR-0020 fatia 2) ----------

(defn vereadores-a-avisar
  "As identidades dos vereadores com mandato VIGENTE hoje que entram no sistema — a pauta publicada vai a eles."
  [{:keys [repo-cadastros repo-identidade]} ente-id hoje]
  (let [ativas (set (map :identidade-id (repo-id/pessoas-da-casa repo-identidade ente-id)))]
    (filterv ativas (repo-cad/identidades-dos-vereadores-vigentes repo-cadastros ente-id hoje))))

(defn pessoas-com-papel
  "As identidades com vinculo ativo na Casa e o `papel` — o pedido de parecer vai a quem tem `juridico`."
  [repo-identidade ente-id papel]
  (->> (repo-id/pessoas-da-casa repo-identidade ente-id)
       (filter #(contains? (:papeis %) papel))
       (mapv :identidade-id)))
