(ns oplenario.busca
  "Host (§22.10: raiz de composicao, pode requerer modulos): a busca intra-camara (Track IA, Faixa A / A.5).

  O indice mora no satelite (A.4, §22.3.4); ele devolve ids e trechos. QUEM DECIDE O QUE O USUARIO VE E' O CORE:
  cada resultado so' passa se o core o reencontra nas SUAS tabelas, na tx do tenant (RLS) — proposicao pelo id,
  trecho de fala pela sessao + o ponteiro da transcricao que o core registrou para ela. Sessao secreta nunca aparece
  (a gravacao dela ja' nao vai a IA; aqui e' a segunda trava). O texto mostrado da proposicao e' o do core (a ementa
  vigente), nao o que a IA indexou.

  IA fora do ar nao derruba a tela (R-IA-1): a resposta vem com `:modo \"sem-ia\"`, o aviso, e as proposicoes pela
  busca literal da ementa que o legislativo ja' tinha. Mora no host porque cruza tres modulos (integracao-ia,
  legislativo, sessoes) — nenhum deles importa o outro."
  (:require [clojure.string :as str]
            [oplenario.http :as http]
            [oplenario.integracao-ia.diplomat.http.out :as plataforma-ia]
            [oplenario.interceptors :as it]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]))

(def tipos #{"proposicao" "transcricao"})
(def ^:private limite 20)
;; pede-se um pouco mais do que se mostra: parte do que a IA devolve pode nao passar na hidratacao
(def ^:private limite-ia 30)
(def aviso-sem-ia
  "A busca por sentido está indisponível agora. Mostrando só proposições com essas palavras na ementa.")

(defn- invalido! [msg campo]
  (throw (ex-info msg {:tipo :validacao/invalido :campo campo})))

(defn pedido
  "query-params -> {:consulta :tipos}. `q` com 2 a 300 caracteres; `tipos` separados por virgula (padrao: todos)."
  [{:keys [q] tipos-param :tipos}]
  (let [consulta (some-> q str str/trim)
        ts (if (str/blank? tipos-param)
             (sort tipos)
             (distinct (map str/trim (str/split (str tipos-param) #","))))]
    (when-not (and consulta (<= 2 (count consulta) 300)) (invalido! "consulta deve ter de 2 a 300 caracteres" :q))
    (when-not (every? tipos ts) (invalido! "tipo de resultado desconhecido" :tipos))
    {:consulta consulta :tipos (vec ts)}))

(defn- ->uuid [s] (when (string? s) (parse-uuid s)))

(defn- par-da-transcricao [r]
  (let [{:keys [sessao-id transcricao-id]} (:meta r)
        sid (->uuid sessao-id) tid (->uuid transcricao-id)]
    (when (and sid tid) [sid tid])))

(defn hidratar
  "Resultados da IA (ja' em ordem de relevancia) + o que o core achou -> o que a tela mostra. Resultado que o core nao
  reencontrou some; a mesma proposicao aparece uma vez (o melhor trecho). `props` = {uuid resumo}; `sessoes` =
  {[sessao-id transcricao-id] sessao}. Puro."
  [resultados props sessoes]
  (loop [[r & mais] resultados vistas #{} acc []]
    (if-not r
      acc
      (case (:tipo r)
        "proposicao"
        (let [id (->uuid (:ref-id r)) p (get props id)]
          (if (and p (not (vistas id)))
            (recur mais (conj vistas id)
                   (conj acc {:tipo "proposicao" :score (:score r) :trecho (:texto r) :proposicao (assoc p :id id)}))
            (recur mais vistas acc)))
        "transcricao"
        (let [[_ tid :as par] (par-da-transcricao r) s (get sessoes par)
              {:keys [inicio fim orador]} (:meta r)]
          (if s
            (recur mais vistas
                   (conj acc (cond-> {:tipo "transcricao" :score (:score r) :trecho (:texto r)
                                      :sessao s :transcricao-id tid}
                               (number? inicio) (assoc :inicio inicio)
                               (number? fim) (assoc :fim fim)
                               (string? orador) (assoc :orador orador))))
            (recur mais vistas acc)))
        (recur mais vistas acc)))))

(defn buscar
  "O controller. `seams`: :buscar-ia (fn [ente-id pedido]) · :resumir-proposicoes (fn [ente-id ids]) ·
  :sessoes-das-transcricoes (fn [ente-id pares]) · :proposicoes-por-ementa (fn [ente-id consulta limite])."
  [{:keys [buscar-ia resumir-proposicoes sessoes-das-transcricoes proposicoes-por-ementa]} ente-id
   {:keys [consulta tipos]}]
  (try
    (let [{:keys [resultados]} (buscar-ia ente-id {:consulta consulta :tipos tipos :limite limite-ia})
          do-tipo (fn [t] (filter #(= t (:tipo %)) resultados))
          ids (vec (distinct (keep (comp ->uuid :ref-id) (do-tipo "proposicao"))))
          pares (vec (distinct (keep par-da-transcricao (do-tipo "transcricao"))))]
      {:modo "ia"
       :resultados (vec (take limite (hidratar resultados
                                               (if (seq ids) (resumir-proposicoes ente-id ids) {})
                                               (if (seq pares) (sessoes-das-transcricoes ente-id pares) {}))))})
    (catch clojure.lang.ExceptionInfo e
      (if (= :ia/indisponivel (:tipo (ex-data e)))
        {:modo "sem-ia"
         :aviso aviso-sem-ia
         :resultados (if (some #{"proposicao"} tipos)
                       (mapv (fn [p] {:tipo "proposicao" :proposicao p}) (proposicoes-por-ementa ente-id consulta limite))
                       [])}
        (throw e)))))

(defn- data-da-sessao [s] (some-> (or (:aberta-em s) (:agendada-para s)) str))

(defn seams
  "Os seams reais, a partir dos componentes do host. As leituras do core sao por tenant (RLS)."
  [{:keys [ia repo-legislativo repo-sessoes]}]
  {:buscar-ia (fn [ente-id p] (plataforma-ia/buscar ia ente-id p))
   :resumir-proposicoes (fn [ente-id ids] (repo-leg/resumos-de-proposicoes repo-legislativo ente-id ids))
   :sessoes-das-transcricoes
   (fn [ente-id pares]
     (let [sessoes (into {} (for [sid (distinct (map first pares))]
                              [sid (repo-sessoes/buscar-sessao repo-sessoes ente-id sid)]))]
       (into {} (for [[sid tid :as par] pares
                      :let [s (get sessoes sid)]
                      :when (and s (not= "secreta" (:tipo-sessao s))
                                 (repo-sessoes/buscar-transcricao repo-sessoes ente-id sid tid))]
                  [par {:id sid :tipo (:tipo-sessao s) :numero (:numero-sequencial s) :data (data-da-sessao s)}]))))
   :proposicoes-por-ementa
   (fn [ente-id consulta n]
     (mapv #(select-keys % [:id :tipo :ano :sequencial :ementa :autor-texto])
           (:itens (repo-leg/listar-e-contar-proposicoes
                    repo-legislativo ente-id {:busca consulta :pagina 1 :tamanho n
                                              :ordenar-por "atualizado_em" :ordenar-dir "desc"}))))})

(defn- busca-handler [seams]
  (fn [req]
    (http/json-resposta 200 (buscar seams (:ente-id (:ator req)) (pedido (:query-params req))))))

(defn rotas
  "GET /busca?q=&tipos= — da secretaria, como a leitura da transcricao que ela expoe (o vereador fica para depois)."
  [{:keys [auth seams]}]
  #{["/busca" :get [auth (it/exige-papel "secretario") (busca-handler seams)] :route-name :busca/buscar]})
