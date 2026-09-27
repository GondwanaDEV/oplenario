(ns oplenario.kernel.catalogo
  "O CATALOGO DE ACOES (ADR-0009, §22.11 Eixo 2): uma ferramenta por ACAO DE DOMINIO — o que uma pessoa da Camara
  diria em voz alta ('consultar a tramitacao da proposicao X') — declarada uma vez, servindo a tela, o agente interno
  e, depois, o MCP. Aqui mora so' o FORMATO e a mecanica pura/generica; cada modulo declara as entradas que sao suas
  em `diplomat/catalogo.clj`, e o host as agrega (`oplenario.catalogo`).

  Uma entrada:
    :nome       snake_case, unico, estavel (e' o nome da ferramenta para o agente)
    :descricao  em portugues, escrita PARA O AGENTE (quando usar, o que devolve)
    :classe     :leitura | :rascunho | :ato — AUSENTE = :ato (fail-closed, Eixo 4.4)
    :papeis     papeis (strings) que podem usar; o agente nunca tem mais que a pessoa (interseccao, Eixo 3.2)
    :entrada    schema Malli da entrada (vira JSON Schema para o agente)
    :saida      schema Malli da saida (o MESMO wire/out da tela)
    :rotas      route-names das rotas HTTP que sao a mesma acao (a tela) — o lint de rotas cobra isso
    :executar   (fn [deps ator entrada] -> saida | nil), nil = nao encontrado"
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as json-schema]
            [malli.transform :as mt]
            [oplenario.kernel.autorizacao :as authz]))

(set! *warn-on-reflection* true)

(def classes #{:leitura :rascunho :ato})

(def ^:private Entrada
  [:map {:closed true}
   [:nome [:re #"^[a-z][a-z0-9]*(_[a-z0-9]+)*$"]]
   [:descricao [:string {:min 40}]]
   [:classe {:optional true} [:enum :leitura :rascunho :ato]]
   [:papeis [:set {:min 1} :string]]
   [:entrada :any]
   [:saida :any]
   [:rotas [:set :keyword]]
   [:executar fn?]])

(defn entrada
  "Declara uma entrada do catalogo, validando o formato na carga (um catalogo mal formado nao sobe). Sem `:classe`
  a entrada e' `:ato` — quem esquece de classificar nunca ganha uma leitura livre."
  [e]
  (when-not (m/validate Entrada e)
    (throw (ex-info (str "entrada de catalogo mal formada: " (:nome e))
                    {:erros (me/humanize (m/explain Entrada e))})))
  (update e :classe #(or % :ato)))

(defn validar-catalogo!
  "Nomes unicos e schemas que viram JSON Schema. Devolve o catalogo como {nome entrada}."
  [entradas]
  (let [por-nome (group-by :nome entradas)
        repetidos (keep (fn [[n es]] (when (> (count es) 1) n)) por-nome)]
    (when (seq repetidos)
      (throw (ex-info "nomes repetidos no catalogo" {:nomes (vec repetidos)})))
    (doseq [e entradas] (json-schema/transform (:entrada e)) (json-schema/transform (:saida e)))
    (into {} (map (juxt :nome identity)) entradas)))

(defn descrever
  "A entrada como o agente a ve: nome, descricao, classe e os JSON Schemas de entrada e saida."
  [e]
  {:nome (:nome e) :descricao (:descricao e) :classe (name (:classe e))
   :entrada (json-schema/transform (:entrada e)) :saida (json-schema/transform (:saida e))})

(def ^:private transformador (mt/transformer mt/json-transformer mt/strip-extra-keys-transformer))

(defn exige-classe!
  "A metade da interseccao (Eixo 3.2) que o papel nao cobre, para o ator de AGENTE (com `:via`): a classe da entrada
  tem de estar entre as concedidas a execucao; agente institucional nunca passa de `rascunho` (3.1 b); e `ato` por
  agente nunca executa direto — vira proposta confirmada pela pessoa na tela (Eixo 4.2, B.6). Ator sem `:via` (a
  tela) nao passa por aqui."
  [e ator]
  (when-let [via (:via ator)]
    (let [classe (:classe e)]
      (when-not (contains? (:classes via) classe)
        (authz/negar! :classe-nao-concedida {:ferramenta (:nome e) :classe classe}))
      (when (= :ato classe)
        (authz/negar! (if (:institucional? via) :institucional-nunca-ato :ato-so-por-proposta)
                      {:ferramenta (:nome e)})))))

(defn- desfecho [ex]
  (case (:tipo (ex-data ex))
    :autorizacao/negado "negado"
    :validacao/invalido "invalido"
    "erro"))

(defn- executar*
  [e deps ator dados]
  (authz/exige-algum-papel! ator (:papeis e))
  (exige-classe! e ator)
  (let [entrada (m/decode (:entrada e) (or dados {}) transformador)]
    (when-not (m/validate (:entrada e) entrada)
      (throw (ex-info (str "entrada invalida para " (:nome e))
                      {:tipo :validacao/invalido :ferramenta (:nome e)
                       :erros (me/humanize (m/explain (:entrada e) entrada))})))
    (let [saida ((:executar e) deps ator entrada)]
      (when (and (some? saida) (not (m/validate (:saida e) saida)))
        (throw (ex-info (str "saida de " (:nome e) " viola o contrato (bug de servidor)")
                        {:ferramenta (:nome e) :erros (me/humanize (m/explain (:saida e) saida))})))
      saida)))

(defn executar
  "Executa a entrada `e` para o `ator`: (1) papel — o ator tem de ter ALGUM papel da entrada (`:autorizacao/negado`);
  (1b) ator de agente: a classe tem de estar concedida (`exige-classe!`); (2) a entrada chega como JSON (chaves
  keyword) e e' decodificada e validada contra o schema (`:validacao/invalido`); (3) roda; (4) a saida e' conferida
  contra o schema (divergencia e' bug de servidor). nil = nao encontrado. A camada fina (policy do recurso)
  continua dentro do controller que `:executar` chama.

  AUDIT (Eixo 3.5): chamada de AGENTE a entrada que escreve (`rascunho`/`ato`) vai SEMPRE ao registro, com o
  desfecho — inclusive negada ou invalida —, pelo seam `(:registrar-chamada deps)` `(fn [ator e desfecho])`. Sem o
  seam, a chamada de escrita por agente nao roda (fail-closed: escrita sem rastro nao existe)."
  [e deps ator dados]
  (if (and (:via ator) (not= :leitura (:classe e)))
    (let [registrar (or (:registrar-chamada deps)
                        (throw (ex-info "chamada de escrita por agente sem registro de audit" {:ferramenta (:nome e)})))]
      (try
        (let [saida (executar* e deps ator dados)]
          (registrar ator e (if (some? saida) "ok" "nao_encontrado"))
          saida)
        (catch Exception ex
          (registrar ator e (desfecho ex))
          (throw ex))))
    (executar* e deps ator dados)))

(defn nomes [entradas] (set (map :nome entradas)))
