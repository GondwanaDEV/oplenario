(ns oplenario.compliance.components.serializador-remessa
  "Port de SAIDA SerializadorRemessa (§22.7.8 D4): leva o DOCUMENTO INTERMEDIARIO (do renderizador puro,
  gerador-remessa) ao formato FISICO do arquivo (XML/posicional/CSV/proprio). O descritor e' formato-
  agnostico; o ADAPTER conhece o formato. **1 adapter na V1** (SIM/TCE-CE) — a generalizacao e' DISPARADA
  quando o 2o TCE chegar (S2, disc.6: generalizar no 2o caso), nao antes; a *forma* do port e' barata agora.
  Renderiza o descritor declarativo (dado, dec.2b) — NAO estende a DSL de avaliacao (serializacao != avaliacao).

  Layout FISICO do SIM = [GAP] regulatorio: o `serializador-sim` emite XML ILUSTRATIVO e DETERMINISTICO
  (mesma entrada -> mesmos bytes, p/ hash de integridade estavel) — o formato real (campos/ordem/encoding)
  e' conteudo a popular sob a regua das 4 perguntas, sem reabrir a forma. Segue a ORDEM declarada no
  descritor (cabecalho/colunas), pois o documento intermediario e' mapa (sem ordem)."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defprotocol SerializadorRemessa
  (serializar [this descritor documento]
    "Renderiza `documento` (intermediario) no formato fisico, na ordem declarada por `descritor`. Devolve
     {:bytes <byte-array> :content-type <str>}. Deterministico (re-emissao reproduz bytes identicos)."))

(defn- escapar-xml
  "Escapa os 5 metacaracteres de XML — & primeiro (senao re-escaparia os outros). Artefato regulatorio
  nao pode quebrar por um '&' num nome de ente."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn- tag [nome valor] (str "<" nome ">" (escapar-xml valor) "</" nome ">"))

(defrecord SerializadorSim []
  SerializadorRemessa
  (serializar [_ descritor documento]
    (let [cab     (:cabecalho documento)
          cab-xml (apply str (for [{:keys [campo]} (:cabecalho descritor)] (tag campo (get cab campo))))
          colunas (map :campo (get-in descritor [:registros :colunas]))
          reg-xml (apply str (for [r (:registros documento)]
                               (str "<registro>" (apply str (for [c colunas] (tag c (get r c)))) "</registro>")))
          xml     (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                       "<remessa sistema=\"" (escapar-xml (:sistema documento)) "\""
                       " spec-layout=\"" (escapar-xml (:spec-layout-versao documento)) "\">"
                       "<cabecalho>" cab-xml "</cabecalho>"
                       "<registros>" reg-xml "</registros>"
                       "</remessa>")]
      {:bytes (.getBytes ^String xml "UTF-8") :content-type (:content-type descritor)})))

(defn serializador-sim
  "Cria o adapter SerializadorRemessa do SIM/TCE-CE (sem estado — nao e' Component com Lifecycle; o host
  o constroi e injeta no caminho de geracao). Layout fisico = [GAP] (XML ilustrativo)."
  []
  (->SerializadorSim))
