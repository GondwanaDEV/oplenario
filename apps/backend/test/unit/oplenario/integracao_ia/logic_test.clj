(ns oplenario.integracao-ia.logic-test
  "UNIT (puro): ADR-0008 — o que atravessa a fronteira core -> IA, o sigilo e o segredo de servico."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.integracao-ia.logic :as logic]))

(def ente #uuid "10000000-0000-0000-0000-000000000001")
(def seg #uuid "20000000-0000-0000-0000-000000000002")
(def sid #uuid "30000000-0000-0000-0000-000000000003")

(deftest gravacao-vinculada-vira-evento-de-integracao-com-uris
  (let [e (logic/promover "gravacao.segmento-vinculado" ente {:segmento-id (str seg) :sessao-id (str sid)
                                                               :acesso-restrito false})]
    (is (= ["GravacaoVinculada" 1] [(:tipo e) (:versao e)]))
    (is (= (str "GravacaoVinculada:v1:" seg) (:chave e)) "a chave e' estavel: promover de novo nao duplica")
    (is (= (str "/integracao/ia/v1/entes/" ente "/gravacoes/" seg "/conteudo") (get-in e [:payload :conteudo-uri])))
    (is (= (str "/integracao/ia/v1/entes/" ente "/sessoes/" sid "/contexto") (get-in e [:payload :contexto-uri])))
    (is (not (contains? (:payload e) :acesso-restrito)) "o payload carrega URIs, nao internos")))

(deftest gravacao-restrita-nao-atravessa
  (is (nil? (logic/promover "gravacao.segmento-vinculado" ente {:segmento-id (str seg) :sessao-id (str sid)
                                                                 :acesso-restrito true}))))

(deftest so-o-que-esta-na-lista-e-promovido
  (is (nil? (logic/promover "gravacao.segmento-captado" ente {:segmento-id (str seg)}))
      "captado sem vinculo nao vai: a IA so' trabalha gravacao que a secretaria pos numa sessao")
  (is (nil? (logic/promover "presenca.registrada" ente {}))))

(deftest sessao-secreta-e-restrita-e-segmento-restrito-sai-do-contexto
  (is (logic/contexto-restrito? {:sessao {:tipo-sessao "secreta"}}))
  (is (not (logic/contexto-restrito? {:sessao {:tipo-sessao "ordinaria"}})))
  (is (= [{:id 1 :acesso-restrito false}] (logic/segmentos-liberados [{:id 1 :acesso-restrito false} {:id 2 :acesso-restrito true}]))))

(deftest segredo-fail-closed
  (is (logic/segredo-confere? "s3gr3d0-longo" "s3gr3d0-longo"))
  (is (not (logic/segredo-confere? "s3gr3d0-longo" "s3gr3d0-long0")))
  (is (not (logic/segredo-confere? "s3gr3d0-longo" nil)))
  (is (not (logic/segredo-confere? nil nil)) "sem segredo configurado nada confere")
  (is (not (logic/segredo-confere? "" ""))))

(deftest limite-do-feed-tem-teto-e-piso
  (is (= 100 (logic/limite-do-feed nil)))
  (is (= 1 (logic/limite-do-feed 0)))
  (is (= 200 (logic/limite-do-feed 999999))))

(deftest ponteiro-da-transcricao
  (let [t (java.time.Instant/parse "2026-09-26T22:00:00Z")]
    (is (= "concluida" (:situacao (logic/ponteiro-da-transcricao
                                   {:tipo "TranscricaoConcluida" :ocorrido-em t :payload {:sessao-id sid}}))))
    (is (= {:situacao "falhou" :sessao-id sid :segmento-id seg :categoria-erro "entrada"
            :detalhe-erro "audio corrompido" :retentavel false :ocorrido-em t}
           (logic/ponteiro-da-transcricao
            {:tipo "TranscricaoFalhou" :ocorrido-em t
             :payload {:sessao-id sid :segmento-id seg :categoria "entrada" :detalhe "audio corrompido"
                       :retentavel false}})))))
