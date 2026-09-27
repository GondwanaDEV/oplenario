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
    :executar   (fn [deps ator entrada] -> saida | nil), nil = nao encontrado
  So' de `ato` (ADR-0012), e obrigatorios nele — o que a pessoa confirma quando o agente propoe:
    :ritual     :confirmar | :assinatura (a folha de 2 toques)
    :apresentar (fn [deps ator entrada] -> {:titulo :texto} | nil), nil = nao encontrado
  Opcional de leitura que devolve conteudo de TERCEIRO (Eixo 4.5):
    :terceiro   (fn [saida] -> [{:origem :referencia}]) — vazio = nada de terceiro nesta saida"
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
   [:executar fn?]
   [:ritual {:optional true} [:enum :confirmar :assinatura]]
   [:apresentar {:optional true} fn?]
   [:terceiro {:optional true} fn?]])

(defn entrada
  "Declara uma entrada do catalogo, validando o formato na carga (um catalogo mal formado nao sobe). Sem `:classe`
  a entrada e' `:ato` — quem esquece de classificar nunca ganha uma leitura livre."
  [e]
  (when-not (m/validate Entrada e)
    (throw (ex-info (str "entrada de catalogo mal formada: " (:nome e))
                    {:erros (me/humanize (m/explain Entrada e))})))
  (let [e (update e :classe #(or % :ato))
        ato? (= :ato (:classe e))]
    (when (and ato? (not (and (:ritual e) (:apresentar e))))
      (throw (ex-info (str "entrada de catalogo " (:nome e) " e' ato (ou nao tem classe) e nao declara :ritual e"
                           " :apresentar — o que a pessoa confirmaria quando o agente propuser (ADR-0012)")
                      {:nome (:nome e)})))
    (when (and (not ato?) (or (:ritual e) (:apresentar e)))
      (throw (ex-info (str "entrada de catalogo " (:nome e) ": :ritual e :apresentar sao so' de ato") {:nome (:nome e)})))
    e))

(defn validar-catalogo!
  "Nomes unicos e schemas que viram JSON Schema. Devolve o catalogo como {nome entrada}."
  [entradas]
  (let [por-nome (group-by :nome entradas)
        repetidos (keep (fn [[n es]] (when (> (count es) 1) n)) por-nome)]
    (when (seq repetidos)
      (throw (ex-info "nomes repetidos no catalogo" {:nomes (vec repetidos)})))
    (doseq [e entradas] (json-schema/transform (:entrada e)) (json-schema/transform (:saida e)))
    (into {} (map (juxt :nome identity)) entradas)))

(defn- mapa-de
  "O schema de mapa de uma entrada: ela mesma, ou o primeiro mapa dentro de um `[:and ...]` (a regra entre campos
  mora no `:fn` ao lado)."
  [s]
  (let [s (m/schema s)]
    (case (m/type s)
      :map s
      :and (some mapa-de (m/children s))
      nil)))

(defn- chaves-da-entrada
  "nome-string -> keyword das chaves que a entrada aceita."
  [e]
  (into {} (map (fn [[k]] [(name k) k])) (some-> (mapa-de (:entrada e)) m/entries)))

(defn- so-chaves-conhecidas
  "Os `dados` do agente chegam de JSON, possivelmente com chaves STRING (o `corpo-json` da borda nunca interna
  keyword de cliente). So' as chaves que a entrada declara viram keyword; o resto cai aqui."
  [e dados]
  (let [conhecidas (chaves-da-entrada e)]
    (into {} (keep (fn [[k v]] (when-let [kw (get conhecidas (if (keyword? k) (name k) (str k)))] [kw v])))
          dados)))

(def PropostaOut
  "O que um `ato` devolve ao AGENTE (ADR-0012): a proposta criada, nunca o efeito — o ato so' acontece quando a pessoa
  confirma na tela."
  [:map {:closed true}
   [:proposta-id :string]
   [:titulo :string]
   [:estado [:enum "aguardando_confirmacao"]]
   [:mensagem :string]])

(defn descrever
  "A entrada como o agente a ve: nome, descricao, classe e os JSON Schemas de entrada e saida. O de entrada e' sempre
  um objeto (o MCP exige): de uma entrada `[:and mapa regra]` sai o mapa, e a regra continua valendo no servidor. A
  saida de um `ato` e' a proposta."
  [e]
  {:nome (:nome e) :descricao (:descricao e) :classe (name (:classe e))
   :entrada (json-schema/transform (or (mapa-de (:entrada e)) (:entrada e)))
   :saida (json-schema/transform (if (= :ato (:classe e)) PropostaOut (:saida e)))})

(def ^:private chaves-declaradas
  "Mapas ANINHADOS da entrada (ex.: as citacoes de uma nota tecnica) tambem chegam do JSON com chaves STRING: cada
  `:map` do schema vira keyword so' as chaves que ele declara — a mesma disciplina de `so-chaves-conhecidas` (nunca
  internar keyword de cliente); chave desconhecida cai. `:map-of` (chaves livres, ex.: campos de requerimento) nao passa
  por aqui."
  (mt/transformer
   {:name :chaves-declaradas
    :decoders {:map {:compile (fn [schema _]
                                (let [conhecidas (into {} (map (fn [[k]] [(name k) k])) (m/children schema))]
                                  (fn [x]
                                    (if (map? x)
                                      (into {} (keep (fn [[k v]] (if (keyword? k)
                                                                   [k v]
                                                                   (when-let [kw (get conhecidas (str k))] [kw v]))))
                                            x)
                                      x))))}}}))

(def ^:private transformador
  (mt/transformer chaves-declaradas mt/json-transformer mt/strip-extra-keys-transformer))

(defn exige-classe!
  "A metade da interseccao (Eixo 3.2) que o papel nao cobre, para o ator de AGENTE (com `:via`): a classe da entrada
  tem de estar entre as concedidas a execucao; agente institucional nunca passa de `rascunho` (3.1 b). O `ato` por
  agente (de pessoa) segue, mas so' para virar proposta (`executar`, ADR-0012). Ator sem `:via` (a tela) nao passa
  por aqui."
  [e ator]
  (when-let [via (:via ator)]
    (let [classe (:classe e)]
      (when-not (contains? (:classes via) classe)
        (authz/negar! :classe-nao-concedida {:ferramenta (:nome e) :classe classe}))
      (when (and (= :ato classe) (:institucional? via))
        (authz/negar! :institucional-nunca-ato {:ferramenta (:nome e)})))))

(defn- desfecho [ex]
  (case (:tipo (ex-data ex))
    :autorizacao/negado "negado"
    :validacao/invalido "invalido"
    "erro"))

(defn entrada-validada
  "Os `dados` (JSON, chaves keyword ou string) decodificados e validados contra a entrada de `e`, ou `:validacao/invalido`."
  [e dados]
  (let [entrada (m/decode (:entrada e) (so-chaves-conhecidas e (if (map? dados) dados {})) transformador)]
    (when-not (m/validate (:entrada e) entrada)
      (throw (ex-info (str "entrada invalida para " (:nome e))
                      {:tipo :validacao/invalido :ferramenta (:nome e)
                       :erros (me/humanize (m/explain (:entrada e) entrada))})))
    entrada))

(defn- propor
  "ADR-0012: o `ato` pedido por AGENTE nao executa — vira proposta com a entrada EXATA ja' validada e o que a pessoa vai
  ver (`:apresentar`, nil = nao encontrado). Quem grava e' o seam `(:propor deps)` do host."
  [e deps ator entrada]
  (let [gravar (or (:propor deps)
                   (throw (ex-info "ato por agente sem o seam de proposta (ADR-0012)" {:ferramenta (:nome e)})))]
    (when-let [apresentacao ((:apresentar e) deps ator entrada)]
      (let [p (gravar ator e entrada apresentacao)]
        (when-not (m/validate PropostaOut p)
          (throw (ex-info "proposta viola o contrato (bug de servidor)" {:ferramenta (:nome e)})))
        p))))

(defn- marcar-terceiro!
  "Eixo 4.5: a saida que traz conteudo de TERCEIRO marca a execucao do agente (a proposta criada depois leva a marca).
  Sem o seam, o conteudo de terceiro nao vai ao agente (fail-closed)."
  [e deps ator saida]
  (when-let [marcas (and (:via ator) (some? saida) (:terceiro e) (seq ((:terceiro e) saida)))]
    (let [marcar (or (:marcar-terceiro deps)
                     (throw (ex-info "conteudo de terceiro sem o seam que marca a execucao (Eixo 4.5)"
                                     {:ferramenta (:nome e)})))]
      (marcar ator e (vec marcas)))))

(defn origem
  "A origem de uma saida, para o `_meta` do MCP (Eixo 4.5): \"terceiro\" se a entrada diz que ha' conteudo de terceiro
  nela, senao \"interno\"."
  [e saida]
  (if (and (:terceiro e) (some? saida) (seq ((:terceiro e) saida))) "terceiro" "interno"))

(defn- executar*
  [e deps ator dados]
  (authz/exige-algum-papel! ator (:papeis e))
  (exige-classe! e ator)
  (let [entrada (entrada-validada e dados)]
    (if (and (:via ator) (= :ato (:classe e)))
      (propor e deps ator entrada)
      (let [saida ((:executar e) deps ator entrada)]
        (when (and (some? saida) (not (m/validate (:saida e) saida)))
          (throw (ex-info (str "saida de " (:nome e) " viola o contrato (bug de servidor)")
                          {:ferramenta (:nome e) :erros (me/humanize (m/explain (:saida e) saida))})))
        (marcar-terceiro! e deps ator saida)
        saida))))

(defn executar
  "Executa a entrada `e` para o `ator`: (1) papel — o ator tem de ter ALGUM papel da entrada (`:autorizacao/negado`);
  (1b) ator de agente: a classe tem de estar concedida (`exige-classe!`); (2) a entrada chega como JSON (chaves
  keyword) e e' decodificada e validada contra o schema (`:validacao/invalido`); (3) roda; (4) a saida e' conferida
  contra o schema (divergencia e' bug de servidor). nil = nao encontrado. A camada fina (policy do recurso)
  continua dentro do controller que `:executar` chama.

  `ato` pedido por AGENTE nao roda: vira PROPOSTA (ADR-0012) e devolve `PropostaOut`; a pessoa confirma na tela, e ai'
  a mesma entrada roda por aqui como ela (ator sem `:via`). Leitura de conteudo de terceiro por agente marca a
  execucao (seam `:marcar-terceiro`).

  AUDIT (Eixo 3.5): chamada de AGENTE a entrada que escreve (`rascunho`/`ato`) vai SEMPRE ao registro, com o
  desfecho — inclusive negada ou invalida; ato proposto = `proposta` —, pelo seam `(:registrar-chamada deps)`
  `(fn [ator e desfecho])`. Sem o seam, a chamada de escrita por agente nao roda (fail-closed: escrita sem rastro nao
  existe)."
  [e deps ator dados]
  (if (and (:via ator) (not= :leitura (:classe e)))
    (let [registrar (or (:registrar-chamada deps)
                        (throw (ex-info "chamada de escrita por agente sem registro de audit" {:ferramenta (:nome e)})))]
      (try
        (let [saida (executar* e deps ator dados)]
          (registrar ator e (cond (nil? saida) "nao_encontrado" (= :ato (:classe e)) "proposta" :else "ok"))
          saida)
        (catch Exception ex
          (registrar ator e (desfecho ex))
          (throw ex))))
    (executar* e deps ator dados)))

(defn nomes [entradas] (set (map :nome entradas)))
