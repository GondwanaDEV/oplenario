(ns oplenario.legislativo.components.assinador-icp
  "Port de SAIDA AssinadorICP (§22.5 eixo F — assinatura digital ICP-Brasil de artefato LEGAL; F6c Slice 4a).
  Assina os BYTES ja' serializados do artefato de publicacao (assinatura DESTACADA/detached, padrao CAdES: a
  assinatura e' metadado sobre o binario, nao embutida no binario hasheado). Devolve {:algoritmo :assinatura-b64}.

  STUB nesta fatia (`assinador-stub`): a validacao REAL da cadeia ICP-Brasil e' infra-deferida (carry F1.4) —
  cert valido na cadeia da AC-Raiz + CPF-do-cert == CPF-da-sessao (two-layer §22.5) + carimbo de tempo de uma
  autoridade confiavel. `:algoritmo` = 'STUB-ICP-v0' marca HONESTAMENTE que a assinatura NAO e' ICP real (o
  portal/consumidor nunca deve trata-la como prova juridica ate' a AC real entrar). Deterministico sobre o
  conteudo (mesmos bytes -> mesma assinatura-b64), p/ re-geracao reproduzir."
  (:import (java.security MessageDigest)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(def ^:const algoritmo-stub "STUB-ICP-v0")

(defprotocol AssinadorICP
  (assinar [this conteudo]
    "Assina o byte-array `conteudo` (bytes serializados do artefato). Devolve {:algoritmo <str>
     :assinatura-b64 <str>} — assinatura DESTACADA. O carimbo de tempo (assinado_em) e' do banco (server-now no
     stub; [GAP] autoridade de tempo ICP)."))

(defrecord AssinadorStub []
  AssinadorICP
  (assinar [_ conteudo]
    ;; STUB: 'assinatura' = base64 do SHA-256 do conteudo. NAO e' assinatura ICP real (nenhuma chave privada de
    ;; certificado) — placeholder DETERMINISTICO que completa a FORMA. [GAP] real = §22.5 eixo F (carry F1.4).
    (let [dig (.digest (MessageDigest/getInstance "SHA-256") ^bytes conteudo)]
      {:algoritmo algoritmo-stub
       :assinatura-b64 (.encodeToString (Base64/getEncoder) dig)})))

(defn assinador-stub
  "Cria o adapter AssinadorICP STUB (sem estado — o host o constroi e injeta). Assinatura ICP-Brasil real =
  [GAP]/infra-deferida (carry F1.4)."
  []
  (->AssinadorStub))
