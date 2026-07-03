(ns oplenario.legislativo.components.serializador-publicacao
  "Port de SAIDA SerializadorPublicacao (F6c Slice 4a): leva o DOCUMENTO INTERMEDIARIO (do renderizador puro,
  gerador-publicacao) ao formato FISICO do artefato de publicacao oficial. O renderizador e' formato-agnostico;
  o ADAPTER conhece o formato. Layout FISICO real (PDF/DO diagramado) = [GAP]: `serializador-fixture` emite um
  documento-texto ILUSTRATIVO e DETERMINISTICO (mesma entrada -> mesmos bytes, p/ hash de integridade estavel).
  1 adapter na V1 — mesma disciplina do serializador-remessa: a *forma* do port e' barata; a generalizacao (2o
  formato real) e' disparada quando chegar, nao antes."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defprotocol SerializadorPublicacao
  (serializar [this documento]
    "Renderiza o `documento` intermediario no formato fisico do ato. Devolve {:bytes <byte-array>
     :content-type <str>}. DETERMINISTICO (mesma entrada reproduz bytes identicos, p/ hash estavel)."))

(defn- especie-legivel
  "Rotulo legivel da especie (tipo_norma) p/ o cabecalho. [GAP]: o mapa oficial/completo de especies vive no
  legislativo.logic — nao duplicar aqui; o fixture so' normaliza (underscore->espaco, maiuscula)."
  [especie]
  (-> (str especie) (str/replace "_" " ") str/upper-case))

(defrecord SerializadorFixture []
  SerializadorPublicacao
  (serializar [_ documento]
    (let [{:keys [especie numero ano urn ementa publicado-em veiculo corpo]} documento
          txt (str "=== ATO OFICIAL ===\n"
                   (especie-legivel especie) " N. " numero "/" ano "\n"
                   "URN: " urn "\n"
                   "EMENTA: " ementa "\n"
                   "PUBLICADO EM: " publicado-em "\n"
                   "VEICULO: " veiculo "\n"
                   "=== TEXTO ===\n"
                   corpo "\n"
                   "=== FIM ===\n")]
      {:bytes (.getBytes ^String txt "UTF-8") :content-type "text/plain; charset=utf-8"})))

(defn serializador-fixture
  "Cria o adapter SerializadorPublicacao ilustrativo (sem estado — o host o constroi e injeta no caminho de
  geracao, como o serializador-remessa). Layout fisico real = [GAP] (documento-texto deterministico)."
  []
  (->SerializadorFixture))
