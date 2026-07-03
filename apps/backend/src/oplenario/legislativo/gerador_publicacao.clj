(ns oplenario.legislativo.gerador-publicacao
  "O RENDERIZADOR PROPRIO do artefato de publicacao oficial ('DO-lite', doc-mestre L287, F6c Slice 4a). PURO:
  le os `dados` JA RESOLVIDOS (metadados da norma publicada + texto legal integral) e projeta um DOCUMENTO
  INTERMEDIARIO formato-agnostico. Sem banco, sem I/O, SEM descritor — um ato de Diario Oficial tem estrutura
  FIXA (cabecalho do ato + texto), ao contrario da remessa (arquivo legivel-por-maquina do TCE, que precisa de
  descritor de layout campo-a-campo). A serializacao no formato fisico e' do port SerializadorPublicacao; a
  assinatura DESTACADA e' do AssinadorICP (sobre os bytes ja' serializados) — nenhum dos dois entra aqui.

  Fail-closed (mesma disciplina de gerador-remessa): qualquer campo do ato nao resolvido LANCA — um documento
  legal nao sai com campo em branco silencioso (§5 'incidente inaceitavel')."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def spec-versao
  "Versao do renderizador/layout do artefato de publicacao. Bump quando a FORMA do documento mudar (o layout
  FISICO real do DO = [GAP]; esta e' a versao da forma ilustrativa)."
  "do-lite-v0")

(defn- exigir
  "Resolve UM campo do ato: nil OU string vazia/branca = nao resolvido -> LANCA (fail-closed estrito; num
  documento legal um campo em branco e' tao ruim quanto ausente)."
  [dados campo]
  (let [v (get dados campo)]
    (when (or (nil? v) (and (string? v) (str/blank? v)))
      (throw (ex-info "campo do artefato de publicacao nao resolvido" {:campo campo})))
    v))

(defn renderizar
  "Projeta os `dados` resolvidos ({:especie :numero :ano :urn :ementa :publicado-em :veiculo :corpo}) num
  DOCUMENTO INTERMEDIARIO ({:spec-versao ...+ os mesmos campos}). Formato-agnostico — o port SerializadorPublicacao
  o leva ao formato fisico. Fail-closed em qualquer campo nao resolvido. TODOS os campos sao obrigatorios: um ato
  oficial precisa de especie/numero/ano/URN/ementa/data+veiculo de publicacao/texto integral."
  [dados]
  {:spec-versao  spec-versao
   :especie      (exigir dados :especie)
   :numero       (exigir dados :numero)
   :ano          (exigir dados :ano)
   :urn          (exigir dados :urn)
   :ementa       (exigir dados :ementa)
   :publicado-em (exigir dados :publicado-em)
   :veiculo      (exigir dados :veiculo)
   :corpo        (exigir dados :corpo)})
