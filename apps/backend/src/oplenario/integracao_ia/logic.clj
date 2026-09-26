(ns oplenario.integracao-ia.logic
  "PURO (ADR-0008): as regras da fronteira core <-> IA — quais eventos de dominio viram eventos de INTEGRACAO
  (promocao explicita, §22.3.3), o sigilo fail-closed, os contratos versionados que a IA pode devolver e a
  comparacao do segredo de servico. Sem I/O."
  (:require [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(set! *warn-on-reflection* true)

(def prefixo "/integracao/ia/v1")

(defn uri-conteudo-gravacao [ente-id segmento-id]
  (str prefixo "/entes/" ente-id "/gravacoes/" segmento-id "/conteudo"))

(defn uri-contexto-sessao [ente-id sessao-id]
  (str prefixo "/entes/" ente-id "/sessoes/" sessao-id "/contexto"))

;; ---------- promocao: dominio -> integracao (a lista E' o contrato; promover e' decisao revisada) ----------

(defn- promover-gravacao-vinculada
  "`gravacao.segmento-vinculado` -> `GravacaoVinculada` v1. Gravacao RESTRITA (sessao secreta) NAO sai: nil."
  [ente-id {:keys [segmento-id sessao-id acesso-restrito]}]
  (when-not acesso-restrito
    {:ente-id ente-id
     :tipo    "GravacaoVinculada"
     :versao  1
     :chave   (str "GravacaoVinculada:v1:" segmento-id)
     :payload {:segmento-id  (str segmento-id)
               :sessao-id    (str sessao-id)
               :conteudo-uri (uri-conteudo-gravacao ente-id segmento-id)
               :contexto-uri (uri-contexto-sessao ente-id sessao-id)}}))

(defn- promover-gravacao-captada
  "`gravacao.segmento-captado` COM sessao (link-at-ingest: o utilitario de captacao enviou com `--sessao`) e' uma
  gravacao vinculada desde o nascimento — o core nunca emite `segmento-vinculado` para ela. Mesma chave da
  promocao do vinculo: se as duas acontecerem, o feed tem um evento so'. Sem sessao: nil (espera o vinculo)."
  [ente-id payload]
  (when (:sessao-id payload)
    (promover-gravacao-vinculada ente-id payload)))

(defn- promover-ata-solicitada
  "`ata.rascunho-solicitado` -> `AtaSolicitada` v1 (Faixa A / A.6b). Sessao secreta nunca chega aqui (o controller de
  sessoes recusa o pedido) e, se chegasse, o contexto responderia 403 — o sigilo tem duas travas."
  [ente-id {:keys [solicitacao-id sessao-id]}]
  {:ente-id ente-id
   :tipo    "AtaSolicitada"
   :versao  1
   :chave   (str "AtaSolicitada:v1:" solicitacao-id)
   :payload {:solicitacao-id (str solicitacao-id)
             :sessao-id      (str sessao-id)
             :contexto-uri   (uri-contexto-sessao ente-id sessao-id)}})

(defn uri-ata-publicada [ente-id sessao-id versao]
  (str prefixo "/entes/" ente-id "/sessoes/" sessao-id "/atas/" versao))

(defn- promover-ata-publicada
  "`ata.publicada` -> `AtaRevisadaEPublicada` v1 (A.6c), SO' a que partiu de um rascunho da IA: e' o retorno da
  revisao humana (§22.3.3) que a IA mede (aprovado/editado, proporcao alterada). Ata redigida pela Casa: nil."
  [ente-id {:keys [ata-id sessao-id versao rascunho-id publicada-por conteudo-sha256]}]
  (when rascunho-id
    {:ente-id ente-id
     :tipo    "AtaRevisadaEPublicada"
     :versao  1
     :chave   (str "AtaRevisadaEPublicada:v1:" ata-id)
     :payload {:sessao-id       (str sessao-id)
               :rascunho-id     (str rascunho-id)
               :versao-ata      versao
               :publicada-por   (str publicada-por)
               :conteudo-sha256 conteudo-sha256
               :conteudo-uri    (uri-ata-publicada ente-id sessao-id versao)}}))

(def promocoes
  "tipo de dominio -> (fn [ente-id payload] -> evento de integracao | nil). FONTE UNICA do que atravessa."
  {"gravacao.segmento-vinculado" promover-gravacao-vinculada
   "gravacao.segmento-captado"   promover-gravacao-captada
   "ata.rascunho-solicitado"     promover-ata-solicitada
   "ata.publicada"               promover-ata-publicada})

(defn promover
  "O evento de integracao para um evento de dominio, ou nil (tipo nao promovido, ou conteudo restrito)."
  [tipo ente-id payload]
  (when-let [f (get promocoes tipo)]
    (f ente-id payload)))

;; ---------- o que a IA pode devolver (tipo, versao) ----------

(def categorias-falha
  "As categorias do §22.3.5 que sao FALHA (1-4); 5 e 6 viajam como metadado, nunca como evento de falha."
  #{"infraestrutura" "sobrecarga" "entrada" "modelo"})

(def eventos-aceitos
  "(tipo, versao) que a caixa de entrada conhece. Desconhecido -> 422 (a IA esta' a frente do core: nao aplica)."
  #{["TranscricaoConcluida" 1] ["TranscricaoFalhou" 1] ["AtaRascunhoPronta" 1] ["AtaFalhou" 1]})

(def eventos-de-ata #{"AtaRascunhoPronta" "AtaFalhou"})

;; ---------- sigilo e contexto ----------

(defn contexto-restrito?
  "Sessao secreta nunca vai para a IA (o sigilo e' da sessao inteira, nao so' da gravacao)."
  [{:keys [sessao]}]
  (= "secreta" (:tipo-sessao sessao)))

(defn segmentos-liberados [segmentos] (vec (remove :acesso-restrito segmentos)))

;; ---------- o segredo de servico ----------

(defn segredo-confere?
  "Compara em tempo constante (MessageDigest/isEqual). Segredo nao configurado nunca confere (fail-closed)."
  [esperado recebido]
  (boolean
   (and (not (str/blank? esperado)) (string? recebido)
        (MessageDigest/isEqual (.getBytes ^String esperado StandardCharsets/UTF_8)
                               (.getBytes ^String recebido StandardCharsets/UTF_8)))))

(def teto-do-feed 200)

(defn limite-do-feed [n]
  (-> (or n 100) (max 1) (min teto-do-feed)))

;; ---------- efeito no core de cada evento aceito ----------

(defn ponteiro-da-transcricao
  "Evento `TranscricaoConcluida`/`TranscricaoFalhou` (dominio) -> o ponteiro que `sessoes` grava. O texto nunca
  vem: so' situacao, metricas, modelos e, na falha, a categoria do §22.3.5."
  [{:keys [tipo payload ocorrido-em]}]
  (case tipo
    "TranscricaoConcluida" (assoc payload :situacao "concluida" :ocorrido-em ocorrido-em)
    "TranscricaoFalhou"    {:situacao "falhou" :sessao-id (:sessao-id payload) :segmento-id (:segmento-id payload)
                            :categoria-erro (:categoria payload) :detalhe-erro (:detalhe payload)
                            :retentavel (:retentavel payload) :ocorrido-em ocorrido-em}))

(defn fato-do-rascunho
  "Evento `AtaRascunhoPronta`/`AtaFalhou` (dominio) -> o fato que `sessoes` grava no ponteiro do rascunho. O texto
  nunca vem: so' o id na IA, a proveniencia e os sinais da Camada de Confianca."
  [{:keys [tipo payload ocorrido-em]}]
  (case tipo
    "AtaRascunhoPronta" (assoc payload :situacao "pronto" :ocorrido-em ocorrido-em)
    "AtaFalhou"         {:situacao "falhou" :sessao-id (:sessao-id payload) :solicitacao-id (:solicitacao-id payload)
                         :categoria-erro (:categoria payload) :detalhe-erro (:detalhe payload)
                         :retentavel (:retentavel payload) :ocorrido-em ocorrido-em}))
