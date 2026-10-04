(ns oplenario.participacao.logic.anexo
  "PURO (§22.10 logic): os ANEXOS do atendimento ao cidadao — o arquivo que a secretaria junta a RESPOSTA de um protocolo
  (e-SIC, ouvidoria, LGPD): a resposta a um pedido de informacao costuma SER um documento. Sem IO e sem relogio (o
  `agora` chega por parametro, lido na borda).

  - QUANDO a Casa pode anexar (padrao dos comunicados, fatia 2): so' nos 10 minutos depois do ULTIMO ato de resposta da
    Casa naquele protocolo — a resposta e' imutavel e o arquivo sobe por outra requisicao;
  - QUANTOS: ate' 5 anexos de origem `casa` por protocolo (o limite e' da Casa; o que o requerente anexa, fatia
    seguinte, tem o seu);
  - QUE TIPO: allowlist de extensao + tipo declarado, coerentes entre si. O tipo gravado (e servido no download) e' o
    CANONICO da extensao, nunca o que o navegador declarou;
  - ONDE: a chave no object storage `atendimento/<ente>/<protocolo>/<anexo>` (a convencao `<pasta>/<ente>/` que a
    exportacao e o apagamento da Casa descobrem sozinhos, ADR-0018)."
  (:require [clojure.string :as str])
  (:import (java.time Duration Instant)))

(set! *warn-on-reflection* true)

(def max-anexos-da-casa 5)
(def max-bytes-anexo (* 10 1024 1024))
(def ^Duration janela-de-anexos (Duration/ofMinutes 10))

(def objeto-tipo-da-especie
  "A especie da rota (balcao/portal) -> o `objeto_tipo` do protocolo (o mesmo de `prazo_ativo` e `prorrogacao`)."
  {:esic "pedido_esic" :ouvidoria "manifestacao_ouvidoria" :lgpd "solicitacao_titular"})

(def tipos-aceitos
  "extensao -> {:tipo (o canonico, o que se grava e se serve) :declarados (o que o navegador pode mandar)}. So' estes nove
  formatos; qualquer outro e' 415.

  `csv` aceita tambem `application/vnd.ms-excel`: no Windows com o Excel instalado o navegador declara esse tipo para
  .csv — sem ele a secretaria nao conseguiria anexar uma planilha exportada em CSV. A extensao continua mandando."
  {"pdf"  {:tipo "application/pdf" :declarados #{"application/pdf"}}
   "png"  {:tipo "image/png" :declarados #{"image/png"}}
   "jpg"  {:tipo "image/jpeg" :declarados #{"image/jpeg"}}
   "jpeg" {:tipo "image/jpeg" :declarados #{"image/jpeg"}}
   "txt"  {:tipo "text/plain" :declarados #{"text/plain"}}
   "csv"  {:tipo "text/csv" :declarados #{"text/csv" "application/csv" "text/comma-separated-values" "application/vnd.ms-excel"}}
   "docx" {:tipo "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
           :declarados #{"application/vnd.openxmlformats-officedocument.wordprocessingml.document"}}
   "xlsx" {:tipo "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
           :declarados #{"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"}}
   "odt"  {:tipo "application/vnd.oasis.opendocument.text" :declarados #{"application/vnd.oasis.opendocument.text"}}
   "ods"  {:tipo "application/vnd.oasis.opendocument.spreadsheet"
           :declarados #{"application/vnd.oasis.opendocument.spreadsheet"}}})

(def descricao-dos-tipos "PDF, PNG, JPEG, TXT, CSV, DOCX, XLSX, ODT e ODS")

(defn extensao
  "A extensao do nome, em minusculas, sem o ponto; nil se nao tem (ou o nome acaba em ponto)."
  [nome]
  (let [n (str nome)
        i (str/last-index-of n ".")]
    (when (and i (< (inc i) (count n)))
      (str/lower-case (subs n (inc i))))))

(defn classificar
  "O tipo canonico do arquivo, ou nil se nao e' aceito: a extensao tem de estar na lista E o tipo declarado tem de ser um
  dos que essa extensao admite (`.pdf` declarado como `text/plain`, ou `.exe` declarado como `application/pdf`, nao
  passam). `declarado` ja' vem limpo da borda (`kernel/arquivo/tipo-de-midia`)."
  [nome declarado]
  (when-let [{:keys [tipo declarados]} (get tipos-aceitos (extensao nome))]
    (when (contains? declarados declarado) tipo)))

(defn chave-do-anexo
  "A chave no object storage: `atendimento/<ente>/<protocolo>/<anexo>` (a CHECK `anexo_chave_da_convencao` da migration
  exige exatamente isto)."
  [ente-id objeto-id anexo-id]
  (str "atendimento/" ente-id "/" objeto-id "/" anexo-id))

(defn ultimo-ato-de-resposta
  "O instante do ULTIMO ato de resposta da Casa neste protocolo, ou nil se ainda nao respondeu. Conta a resposta e o
  indeferimento (a mesma tabela de respostas) e, no e-SIC, a DECISAO do recurso. Na ouvidoria o ARQUIVAMENTO nao conta:
  e' encerrar sem resposta de merito (a justificativa vai na mesma tabela), nao ha documento a entregar.

  `dados` = {:estado :respostas [{:respondida-em}] :recurso {:respostas [{:respondida-em}]}} — o que os detalhes do
  balcao ja' leem."
  [especie {:keys [estado respostas recurso]}]
  (when-not (and (= :ouvidoria especie) (not= "respondida" estado))
    (->> (concat respostas (:respostas recurso))
         (keep :respondida-em)
         (sort)
         (last))))

(defn na-janela?
  "A Casa ainda pode anexar? So' ate' 10 minutos depois do ultimo ato de resposta (o proprio minuto 10 vale)."
  [^Instant ultimo-ato-em ^Instant agora]
  (boolean (and ultimo-ato-em
                (not (.isAfter agora (.plus ultimo-ato-em ^java.time.temporal.TemporalAmount janela-de-anexos))))))

(defn pode-anexar?
  "Verdadeiro so' dentro da janela e abaixo do limite de anexos da Casa. E' o que a tela do balcao le (`pode-anexar`)."
  [ultimo-ato-em n-anexos-da-casa agora]
  (boolean (and (na-janela? ultimo-ato-em agora)
                (< (long n-anexos-da-casa) (long max-anexos-da-casa)))))

(defn da-casa [anexos] (filter #(= "casa" (:origem %)) anexos))
