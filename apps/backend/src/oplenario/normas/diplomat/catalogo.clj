(ns oplenario.normas.diplomat.catalogo
  "As entradas do CATALOGO DE ACOES das normas de referencia (ADR-0009, docs/25 Eixo 7.5, B.5): o agente consulta a LOM,
  o Regimento e as leis por DISPOSITIVO — buscar (pela palavra exata e pelo sentido) e ler pelo endereco. So' a versao
  VIGENTE, conferida por uma pessoa; cada dispositivo sai com a citacao pronta e a data ate' quando o texto foi
  conferido, porque toda afirmacao normativa do agente cita um dispositivo lido na mesma execucao.

  A busca pelo sentido e' da IA (seam `:buscar-dispositivos-ia` do host sobre a fronteira); o core so' devolve o que
  ainda e' da versao vigente. IA fora: a busca cai nas palavras exatas do texto (R-IA-1)."
  (:require [oplenario.kernel.catalogo :as catalogo]
            [oplenario.normas.components.repositorio :as repo]))

(set! *warn-on-reflection* true)

(def ^:private NormaOut [:map {:closed true} [:id :string] [:titulo :string] [:especie :string]])

(def ^:private VersaoOut
  [:map {:closed true} [:id :string] [:consolidada-ate [:maybe :string]] [:conferida-em :string]])

(def DispositivoLidoOut
  [:map {:closed true}
   [:norma NormaOut]
   [:versao VersaoOut]
   [:endereco :string]
   [:rotulo :string]
   [:citacao :string]
   [:texto :string]
   [:agrupador [:maybe :string]]])

(def BuscaDispositivosOut
  [:map {:closed true}
   [:modo [:enum "ia" "literal"]]
   [:aviso [:maybe :string]]
   [:resultados [:vector DispositivoLidoOut]]])

(def LeituraOut
  [:map {:closed true}
   [:norma NormaOut]
   [:versao VersaoOut]
   [:citacao :string]
   [:dispositivos [:vector [:map {:closed true} [:endereco :string] [:rotulo :string] [:tipo :string]
                            [:texto :string]]]]])

(defn- norma-e-versao [d]
  {:norma {:id (str (:norma-id d)) :titulo (:titulo d) :especie (:especie d)}
   :versao {:id (str (:versao-id d)) :consolidada-ate (some-> (:consolidada-ate d) str)
            :conferida-em (str (:conferida-em d))}})

(defn- lido->out [d]
  (merge (norma-e-versao d)
         {:endereco (:endereco d) :rotulo (:rotulo d) :citacao (str (:titulo d) ", " (:rotulo d))
          :texto (:texto d) :agrupador (:agrupador d)}))

(def ^:private aviso-literal
  "A busca por sentido está indisponível agora; o resultado vem das palavras exatas do texto.")

(defn- buscar [{:keys [repo-normas buscar-dispositivos-ia]} ator {:keys [consulta limite]}]
  (let [ente (:ente-id ator)
        n (or limite 5)
        da-ia (if buscar-dispositivos-ia
                (try (buscar-dispositivos-ia ente consulta n)
                     (catch clojure.lang.ExceptionInfo e
                       (if (= :ia/indisponivel (:tipo (ex-data e))) ::fora (throw e))))
                ::fora)]
    (if (= ::fora da-ia)
      {:modo "literal" :aviso aviso-literal
       :resultados (mapv lido->out (repo/buscar-dispositivos-literal repo-normas ente consulta n))}
      (let [pares (->> da-ia
                       (keep (fn [{:keys [meta]}]
                               (when-let [v (some-> (:versao-id meta) str parse-uuid)]
                                 (when (string? (:endereco meta)) [v (:endereco meta)]))))
                       distinct vec)
            vivos (into {} (map (juxt (juxt :versao-id :endereco) identity))
                        (repo/hidratar-dispositivos repo-normas ente pares))]
        {:modo "ia" :aviso nil :resultados (vec (keep #(some-> (get vivos %) lido->out) pares))}))))

(defn- ler [{:keys [repo-normas]} ator {:keys [norma-id especie endereco]}]
  (let [ds (repo/ler-dispositivo repo-normas (:ente-id ator) norma-id especie endereco)]
    (when-let [raiz (first (filter #(= endereco (:endereco %)) ds))]
      (merge (norma-e-versao raiz)
             {:citacao (str (:titulo raiz) ", " (:rotulo raiz))
              :dispositivos (mapv #(select-keys % [:endereco :rotulo :tipo :texto]) ds)}))))

(def entradas
  [(catalogo/entrada
    {:nome "buscar_dispositivos"
     :descricao (str "Busca nas normas da Casa (Lei Organica do Municipio, Regimento Interno, leis e resolucoes) os "
                     "dispositivos (artigo, paragrafo, inciso) que tratam de um assunto ou que tem um numero, pela palavra "
                     "exata e pelo sentido. So' a versao vigente, conferida pela Casa. Use antes de afirmar qualquer "
                     "coisa sobre prazo, quorum, rito ou competencia: 'qual o quorum para derrubar um veto?'. Devolve "
                     "cada dispositivo com a citacao pronta ('Regimento Interno, art. 45, § 1º') e o endereco para ler.")
     :classe :leitura
     :papeis #{"secretario" "vereador"}
     :entrada [:map {:closed true}
               [:consulta {:description "O assunto ou o numero, em palavras: 'quorum veto', 'art. 45'."}
                [:string {:min 2 :max 300}]]
               [:limite {:optional true :description "Quantos dispositivos (1 a 10; padrao 5)."} [:int {:min 1 :max 10}]]]
     :saida BuscaDispositivosOut
     :rotas #{}
     :executar buscar})
   (catalogo/entrada
    {:nome "ler_dispositivo"
     :descricao (str "Le um dispositivo da versao vigente de uma norma da Casa pelo endereco, com tudo o que esta dentro "
                     "dele (o artigo inteiro: caput, paragrafos, incisos, alineas). Aponte a norma pelo norma-id que a "
                     "busca devolveu, ou pela especie ('lei_organica' ou 'regimento_interno'). Endereco: 'art45' "
                     "(artigo), 'art45_par1' (§ 1º), 'art45_par1u' (paragrafo unico), 'art45_cpt_inc2' (inciso II do "
                     "caput), 'art45_par1_inc2_ali1' (alinea a). Leia o dispositivo antes de cita-lo.")
     :classe :leitura
     :papeis #{"secretario" "vereador"}
     :entrada [:and
               [:map {:closed true :description "Informe norma-id ou especie, e o endereco."}
                [:norma-id {:optional true :description "Id da norma (vem da busca)."} :uuid]
                [:especie {:optional true :description "Para a LOM ou o Regimento da Casa, sem saber o id."}
                 [:enum "lei_organica" "regimento_interno"]]
                [:endereco {:description "Endereco do dispositivo, ex.: art45_par1."} [:re #"^[a-z0-9_\-]{2,80}$"]]]
               [:fn {:error/message "informe norma-id ou especie"} (fn [m] (or (:norma-id m) (:especie m)))]]
     :saida LeituraOut
     :rotas #{}
     :executar ler})])
