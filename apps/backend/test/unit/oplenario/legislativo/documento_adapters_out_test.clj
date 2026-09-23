(ns oplenario.legislativo.documento-adapters-out-test
  "UNIT (puro, sem DB) — o gate adapters/out do EXPEDIENTE (Onda B Slice 6): documento gerado, modelo de
  documento e protocolo geral (o 'Livro')."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [oplenario.legislativo.adapters.out.documento :as doc-out]
            [oplenario.legislativo.adapters.out.documento-modelo :as modelo-out]
            [oplenario.legislativo.adapters.out.protocolo-geral :as protocolo-out]
            [oplenario.legislativo.wire.out.documento :as wire-doc]
            [oplenario.legislativo.wire.out.documento-modelo :as wire-modelo]
            [oplenario.legislativo.wire.out.protocolo-geral :as wire-protocolo]))

;; ---------- documento->wire ----------

(defn- documento-canonico []
  {:id (random-uuid) :ente-id (random-uuid) :modelo-id (random-uuid) :tipo-documento "oficio"
   :assunto "Convite" :corpo "Ao Prefeito, ref. Convite." :estado "rascunho" :protocolo-geral-id nil
   :lock-version 0 :criado-em (java.time.Instant/parse "2026-07-01T00:00:00Z")})

(deftest documento->wire-sem-protocolo-vinculado
  (let [out (doc-out/documento->wire (documento-canonico))]
    (is (m/validate wire-doc/DocumentoOut out))
    (is (nil? (:protocolo-geral-id out)))
    (is (nil? (:protocolo-numero out)))
    (is (nil? (:protocolo-ano out)))
    (is (= "rascunho" (:estado out)))))

(deftest documento->wire-com-protocolo-vinculado-achata-numero-e-ano
  (let [pg-id (random-uuid)
        doc (assoc (documento-canonico) :protocolo-geral-id pg-id :estado "emitido")
        out (doc-out/documento->wire doc {:numero 12 :ano 2026})]
    (is (m/validate wire-doc/DocumentoOut out))
    (is (= (str pg-id) (:protocolo-geral-id out)))
    (is (= 12 (:protocolo-numero out)))
    (is (= 2026 (:protocolo-ano out)))
    (is (= "emitido" (:estado out)))))

;; ---------- modelo->wire / modelos->wire ----------

(defn- modelo-canonico []
  {:id (random-uuid) :ente-id (random-uuid) :chave "oficio_padrao" :nome "Oficio padrao"
   :tipo-documento "oficio" :corpo-template "Ao {{destinatario}}." :ativo true :lock-version 0})

(deftest modelo->wire-projeta-so-o-necessario-ao-seletor
  (let [out (modelo-out/modelo->wire (modelo-canonico))]
    (is (m/validate wire-modelo/DocumentoModeloOut out))
    (is (= "oficio_padrao" (:chave out)))
    (is (not (contains? out :corpo-template)) "corpo-template cru NAO sai no seletor")))

(deftest modelos->wire-envelope-itens
  (let [out (modelo-out/modelos->wire [(modelo-canonico) (modelo-canonico)])]
    (is (m/validate wire-modelo/ListaModelosOut out))
    (is (= 2 (count (:itens out))))))

(deftest modelos->wire-lista-vazia
  (let [out (modelo-out/modelos->wire [])]
    (is (m/validate wire-modelo/ListaModelosOut out))
    (is (= [] (:itens out)))))

;; ---------- modelo->wire-detalhe (fatia de escrita, aba "Modelos") ----------

(deftest modelo->wire-detalhe-inclui-corpo-e-cas
  (let [out (modelo-out/modelo->wire-detalhe (modelo-canonico))]
    (is (m/validate wire-modelo/DocumentoModeloDetalheOut out))
    (is (= "Ao {{destinatario}}." (:corpo-template out)) "ao contrario de modelo->wire, o detalhe leva o corpo cru")
    (is (true? (:ativo out)))
    (is (= 0 (:lock-version out)))))

(deftest modelo->wire-detalhe-inativo
  (let [out (modelo-out/modelo->wire-detalhe (assoc (modelo-canonico) :ativo false :lock-version 2))]
    (is (m/validate wire-modelo/DocumentoModeloDetalheOut out))
    (is (false? (:ativo out)))
    (is (= 2 (:lock-version out)))))

;; ---------- protocolo->wire / livro->wire ----------

(defn- protocolo-canonico []
  {:id (random-uuid) :ente-id (random-uuid) :numero 7 :ano 2026 :objeto-tipo "documento"
   :objeto-id (random-uuid) :sentido "expedido" :assunto "Convite"
   :protocolado-em (java.time.Instant/parse "2026-07-01T00:00:00Z") :protocolado-por (random-uuid)})

(deftest protocolo->wire-basico
  (let [out (protocolo-out/protocolo->wire (protocolo-canonico))]
    (is (m/validate wire-protocolo/ProtocoloGeralOut out))
    (is (= 7 (:numero out)))
    (is (= "documento" (:objeto-tipo out)))))

(deftest protocolo->wire-objeto-id-nil-e-papel-externo
  (let [out (protocolo-out/protocolo->wire (assoc (protocolo-canonico) :objeto-id nil :objeto-tipo "outro"))]
    (is (m/validate wire-protocolo/ProtocoloGeralOut out))
    (is (nil? (:objeto-id out)))))

(deftest livro->wire-preserva-ordem
  (let [linhas [(assoc (protocolo-canonico) :numero 1) (assoc (protocolo-canonico) :numero 2)
                (assoc (protocolo-canonico) :numero 3)]
        out (protocolo-out/livro->wire linhas)]
    (is (m/validate wire-protocolo/LivroProtocoloOut out))
    (is (= [1 2 3] (mapv :numero (:itens out))))))
