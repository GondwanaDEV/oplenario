(ns oplenario.legislativo.notificacao-logic-test
  "Unit: logica PURA da notificacao 'a sua proposicao virou lei' (Onda E fatia 1). Renderizacao (info
  PUBLICA — a norma publicada e' ato publico) + chave de idempotencia DETERMINISTICA."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [oplenario.legislativo.logic.notificacao :as logic]))

(def ^:private norma
  {:tipo-norma "lei" :numero 3 :ano 2026 :ementa "Dispoe sobre as hortas comunitarias."
   :urn "urn:lex:br;ceara;fortaleza:municipal:lei:2026-06-28;3"})

(deftest chave-e-deterministica
  (let [nid (random-uuid) categoria "norma_publicada" dest (str (random-uuid))]
    (is (= (logic/chave-idempotencia nid categoria dest) (logic/chave-idempotencia nid categoria dest))
        "mesma (norma, categoria, destinatario) -> mesma chave (redrive vira no-op no ON CONFLICT)")
    (is (not= (logic/chave-idempotencia nid categoria dest) (logic/chave-idempotencia nid categoria (str (random-uuid))))
        "destinatarios diferentes -> chaves diferentes")
    (is (not= (logic/chave-idempotencia nid categoria dest) (logic/chave-idempotencia (random-uuid) categoria dest))
        "normas diferentes -> chaves diferentes (o eixo que o ON CONFLICT (ente_id, idempotency_key) protege
         de verdade — uma implementacao que concatenasse sem separador/sem norma-id passaria pelos dois
         asserts acima e so' quebraria aqui)")
    (is (not= (logic/chave-idempotencia nid categoria dest) (logic/chave-idempotencia nid "norma_revogada" dest))
        "categorias diferentes -> chaves diferentes (achado 4: um 2o motivo sobre a MESMA norma ao MESMO
         destinatario nao pode colidir e ser engolido pelo ON CONFLICT DO NOTHING)")))

(deftest renderiza-assunto-e-corpo-publicos
  (let [{:keys [assunto corpo]} (logic/renderizar norma)]
    (is (str/includes? assunto "Lei 3/2026") "assunto identifica a norma")
    (is (str/includes? corpo "hortas comunitarias") "corpo carrega a ementa (dado publico)")
    (is (not (str/includes? corpo "CPF")) "nenhuma PII no conteudo")))
