(ns oplenario.participacao.logic.anexo
  "PURO (§22.10 logic): os ANEXOS do atendimento ao cidadao — o arquivo que a secretaria junta a RESPOSTA de um protocolo
  (e-SIC, ouvidoria, LGPD): a resposta a um pedido de informacao costuma SER um documento. Sem IO e sem relogio (o
  `agora` chega por parametro, lido na borda).

  - QUANDO a Casa pode anexar (padrao dos comunicados, fatia 2): so' nos 10 minutos depois do ULTIMO ato de resposta da
    Casa naquele protocolo — a resposta e' imutavel e o arquivo sobe por outra requisicao;
  - QUANTOS: ate' 5 anexos de origem `casa` por protocolo (o limite e' da Casa; o que o requerente anexa, fatia
    seguinte, tem o seu);
  - QUE TIPO: allowlist de extensao + tipo declarado, coerentes entre si (declarar `application/octet-stream`, ou nada, vale:
    o navegador que nao conhece `.odt`/`.docx` diz isso) E a ASSINATURA do conteudo conferindo com a extensao (um `.exe`
    renomeado para `.pdf` nao passa). O tipo gravado (e servido no download) e' o CANONICO da extensao, nunca o que o
    navegador declarou;
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
  passam) OU o declarado ser `application/octet-stream` (o 'nao sei' do navegador — a borda normaliza tambem a parte sem
  tipo para ele). `declarado` ja' vem limpo da borda (`kernel/arquivo/tipo-de-midia`). Quem barra o impostor e' a
  assinatura do conteudo (`assinatura-confere?`), nao o que o navegador declarou."
  [nome declarado]
  (when-let [{:keys [tipo declarados]} (get tipos-aceitos (extensao nome))]
    (when (or (contains? declarados declarado) (= "application/octet-stream" declarado)) tipo)))

(defn- comeca-com?
  "`conteudo` abre com os `bytes` (inteiros 0-255)?"
  [^bytes conteudo bytes]
  (and (>= (alength conteudo) (count bytes))
       (every? true? (map-indexed (fn [i b] (= (bit-and (aget conteudo (int i)) 0xff) (int b))) bytes))))

(defn- contem-ate?
  "Os `bytes` aparecem em `conteudo` dentro dos primeiros `limite` bytes (a especificacao do PDF tolera um preambulo curto)?"
  [^bytes conteudo bytes limite]
  (let [n (count bytes) fim (- (min (alength conteudo) (long limite)) n)]
    (loop [i 0]
      (cond (> i fim) false
            (every? true? (map-indexed (fn [k b] (= (bit-and (aget conteudo (int (+ i k))) 0xff) (int b))) bytes)) true
            :else (recur (inc i))))))

(defn- sem-nul?
  "Nenhum byte NUL nos primeiros `limite` bytes (texto nao os tem; executavel e binario, sim)."
  [^bytes conteudo limite]
  (let [fim (min (alength conteudo) (long limite))]
    (loop [i 0] (cond (>= i fim) true (zero? (aget conteudo (int i))) false :else (recur (inc i))))))

(def ^:private assinaturas
  {"pdf"  #(contem-ate? % [0x25 0x50 0x44 0x46 0x2D] 1024)                      ; %PDF- (ate' o 1o KiB)
   "png"  #(comeca-com? % [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A])
   "jpg"  #(comeca-com? % [0xFF 0xD8 0xFF])
   "jpeg" #(comeca-com? % [0xFF 0xD8 0xFF])
   "docx" #(comeca-com? % [0x50 0x4B 0x03 0x04])                                  ; PK\x03\x04: todos sao ZIP
   "xlsx" #(comeca-com? % [0x50 0x4B 0x03 0x04])
   "odt"  #(comeca-com? % [0x50 0x4B 0x03 0x04])
   "ods"  #(comeca-com? % [0x50 0x4B 0x03 0x04])
   "txt"  #(sem-nul? % 8192)
   "csv"  #(sem-nul? % 8192)})

(defn assinatura-confere?
  "O CONTEUDO do arquivo bate com a extensao? PDF abre com `%PDF-`; PNG e JPEG pelos bytes magicos; DOCX/XLSX/ODT/ODS sao ZIP
  (`PK\\x03\\x04`); TXT/CSV sao texto: sem byte NUL nos primeiros 8 KiB. Extensao fora da lista (ou ausente) nunca confere.
  E' a conferencia do que o arquivo E, ja' que o tipo declarado e o nome sao o que o navegador diz."
  [ext ^bytes conteudo]
  (if-let [confere (get assinaturas ext)]
    (boolean (confere conteudo))
    false))

(defn chave-do-anexo
  "A chave no object storage: `atendimento/<ente>/<protocolo>/<anexo>` (a CHECK `anexo_chave_da_convencao` da migration
  exige exatamente isto)."
  [ente-id objeto-id anexo-id]
  (str "atendimento/" ente-id "/" objeto-id "/" anexo-id))

(defn ultimo-ato-de-resposta
  "O instante do ULTIMO ato de resposta da Casa neste protocolo, ou nil se ainda nao respondeu. Conta a resposta e o
  indeferimento (a mesma tabela de respostas) e, no e-SIC, a DECISAO do recurso. Na ouvidoria o ARQUIVAMENTO nao conta:
  e' encerrar sem resposta de merito (a justificativa vai na mesma tabela), nao ha documento a entregar.

  O COMPLEMENTO DA RESPOSTA (ADR-0022) tambem conta, mas so' DEPOIS de haver resposta: reabre a janela da Casa por mais 10
  minutos. (Um complemento nunca existe sem resposta; a guarda e' so' para o dado inconsistente nao abrir janela.)

  `dados` = {:estado :respostas [{:respondida-em}] :recurso {:respostas [{:respondida-em}]} :complementos
  [{:complementado-em}]} — o que os detalhes do balcao ja' leem."
  [especie {:keys [estado respostas recurso complementos]}]
  (when-not (and (= :ouvidoria especie) (not= "respondida" estado))
    (when-let [resposta (->> (concat respostas (:respostas recurso))
                             (keep :respondida-em)
                             (sort)
                             (last))]
      (->> (keep :complementado-em complementos)
           (cons resposta)
           (sort)
           (last)))))

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

(defn da-origem
  "Os anexos VIGENTES de uma `origem` (`casa` | `requerente`): o limite de 5 e' POR ORIGEM — o que a Casa anexa a resposta
  nao toma a vaga do que o requerente anexa ao pedido, e vice-versa — e o anexo RETIRADO (`:retirado-em`) devolve a vaga."
  [anexos origem]
  (filter #(and (= origem (:origem %)) (nil? (:retirado-em %))) anexos))

(defn da-casa [anexos] (da-origem anexos "casa"))

;; ---------- o REQUERENTE anexa ao proprio pedido (origem `requerente`) ----------
;; So' o cidadao DONO do protocolo, nos 10 minutos seguintes ao protocolo (`recibo-em`), ate' 5 de origem `requerente`.
;; Mesmos tipos, tamanho e erros dos anexos da Casa. A manifestacao ANONIMA nao tem dono persistido: nao tem anexo.

(def max-anexos-do-requerente 5)

(defn na-janela-do-requerente?
  "O requerente ainda pode anexar? So' ate' 10 minutos depois do protocolo (o proprio minuto 10 vale): o pedido e' imutavel
  e o arquivo sobe por outra requisicao, como na resposta da Casa."
  [^Instant recibo-em ^Instant agora]
  (boolean (and recibo-em
                (not (.isAfter agora (.plus recibo-em ^java.time.temporal.TemporalAmount janela-de-anexos))))))

(defn pode-anexar-requerente?
  "Verdadeiro so' dentro da janela do protocolo e abaixo do limite de anexos do requerente. E' o que `meus-protocolos`
  diz a tela (`pode-anexar`)."
  [recibo-em n-anexos-do-requerente agora]
  (boolean (and (na-janela-do-requerente? recibo-em agora)
                (< (long n-anexos-do-requerente) (long max-anexos-do-requerente)))))

;; ---------- a COTA de disco do cidadao (origem `requerente`) ----------
;; O limite de 5 por protocolo nao impede quem abre 1000 protocolos: a soma dos bytes que UMA identidade anexou nesta Casa
;; nas ultimas 24 horas tem teto. A Casa (a secretaria) nao tem cota. Conferida na mesma transacao do limite de 5.

(def cota-do-requerente-bytes (* 100 1024 1024))
(def ^Duration janela-da-cota (Duration/ofHours 24))

(defn cota-estourada?
  "Com `usados` bytes ja' enviados nas ultimas 24 horas, mais `novo`, passa do teto? (Chegar ao teto, sem passar, cabe.)"
  [usados novo]
  (> (+ (long usados) (long novo)) (long cota-do-requerente-bytes)))

;; ---------- SUBSTITUIR um anexo da Casa (ADR-0022, "Substituir um anexo") ----------
;; Trocar o arquivo errado pelo certo num so' ato. So' o anexo da CASA e ainda vigente; vale a qualquer tempo (nao depende da
;; janela de 10 minutos: a retirada tambem nao depende). O que o requerente anexou nao se substitui — so' se retira.

(defn motivo-de-nao-substituir
  "Por que este anexo NAO pode ser substituido: `:anexo-do-requerente` (so' a Casa substitui o que e' dela), `:ja-substituido`
  (alguem ja' o trocou: `:substituido-por`) ou `:ja-retirado` (a retirada o encerrou). nil = pode. Ordem: o substituido tambem
  esta retirado, mas a mensagem certa e' a da substituicao."
  [{:keys [origem retirado-em substituido-por]}]
  (cond
    (not= "casa" origem) :anexo-do-requerente
    substituido-por :ja-substituido
    retirado-em :ja-retirado))

(defn pode-substituir?
  "A secretaria pode substituir este anexo? E' o que a tela le para oferecer 'Substituir' ao lado de 'Retirar'."
  [anexo]
  (nil? (motivo-de-nao-substituir anexo)))
