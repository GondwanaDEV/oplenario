(ns oplenario.encerramento.formato-test
  "Unit (puro): o formato aberto da exportacao (ADR-0018, Eixo 4.2) — como cada valor do Postgres vira campo de CSV,
  o escape RFC 4180 — e as regras puras dos blobs da Casa (coluna de ponteiro, convencao `<pasta>/<ente>/`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oplenario.encerramento.arquivos :as arquivos]
            [oplenario.encerramento.csv :as csv]
            [oplenario.encerramento.inventario :as inventario]
            [oplenario.encerramento.protecao :as protecao]
            [oplenario.encerramento.protecao :as protecao])
  (:import (java.sql Timestamp)
           (java.time Instant LocalDate)
           (org.postgresql.util PGobject)))

(defn- pg [tipo valor] (doto (PGobject.) (.setType tipo) (.setValue valor)))

(deftest valores-viram-texto
  (testing "NULL fica NULL; texto passa"
    (is (nil? (csv/->texto nil)))
    (is (= "" (csv/->texto ""))))
  (testing "instante em UTC ISO-8601; data AAAA-MM-DD"
    (is (= "2026-10-02T14:33:20.770517Z"
           (csv/->texto (Timestamp/from (Instant/parse "2026-10-02T14:33:20.770517Z")) "timestamptz")))
    (is (= "2026-10-02" (csv/->texto (java.sql.Date/valueOf (LocalDate/parse "2026-10-02"))))))
  (testing "jsonb como o banco guarda; bytea em base64; booleano; uuid"
    (is (= "{\"a\": 1}" (csv/->texto (pg "jsonb" "{\"a\": 1}"))))
    (is (= "AQID" (csv/->texto (byte-array [1 2 3]))))
    (is (= "true" (csv/->texto true)))
    (is (= "00000000-0000-0000-0000-000000000001" (csv/->texto (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001"))))))

(deftest escape-rfc-4180
  (is (= "" (csv/campo nil)) "NULL = campo vazio sem aspas")
  (is (= "\"\"" (csv/campo "")) "texto vazio = aspas (nao se confunde com NULL)")
  (is (= "simples" (csv/campo "simples")))
  (is (= "\"a,b\"" (csv/campo "a,b")))
  (is (= "\"diz \"\"oi\"\"\"" (csv/campo "diz \"oi\"")))
  (is (= "\"linha\nnova\"" (csv/campo "linha\nnova")))
  (is (= "\" espaco\"" (csv/campo " espaco")) "espaco nas pontas preservado")
  (is (= "a,,\"\"\r\n" (csv/linha ["a" nil ""]))))

(deftest ponteiros-e-convencao-dos-blobs
  (is (every? arquivos/coluna-de-ponteiro? ["objeto_store_ref" "html_objeto_store_ref" "conteudo_uri" "audio_uri" "brasao_ref"]))
  (is (not-any? arquivos/coluna-de-ponteiro? ["origem_ref" "evento_ref" "registry_versao_ref" "uri_base"]))
  (let [e "11111111-1111-1111-1111-111111111111"]
    (is (arquivos/da-convencao? e (str "remessas/" e "/x/2026-09/h.bin")))
    (is (arquivos/da-convencao? e (str "gravacao/" e "/seg")))
    (is (not (arquivos/da-convencao? e "textos/abc.txt")))
    (is (not (arquivos/da-convencao? e (str "gravacao/x/" e))) "o ente como NOME do blob nao e' a pasta da Casa")
    (is (not (arquivos/da-convencao? e (str "gravacao/" e "0/seg"))) "segmento inteiro, nao prefixo")))

(deftest predicado-do-inventario-vira-jdbc
  (is (= ["(t.ente_id = ?)" 1] (inventario/com-parametro "(t.ente_id = $1)")))
  (is (= ["(EXISTS (SELECT 1 FROM x p1 WHERE p1.id = t.pai AND p1.ente_id = ?))" 1]
         (inventario/com-parametro "(EXISTS (SELECT 1 FROM x p1 WHERE p1.id = t.pai AND p1.ente_id = $1))")))
  (is (= "\"a\"\"b\"" (inventario/ident "a\"b")) "identificador escapado"))

(deftest protecao-do-cidadao
  (let [c "aaaaaaaa-0000-0000-0000-000000000001"
        s "bbbbbbbb-0000-0000-0000-000000000002"
        pseudo #(str "#p-" (subs % 0 4))
        proteger (protecao/protetor #{c} pseudo)]
    (testing "o id do cidadao vira pseudonimo em qualquer campo (uuid, texto, JSON, maiusculas); o resto passa"
      (is (= "#p-aaaa" (proteger c)))
      (is (= "{\"quem\": \"#p-aaaa\", \"servidor\": \"bbbbbbbb-0000-0000-0000-000000000002\"}"
             (proteger (str "{\"quem\": \"" (str/upper-case c) "\", \"servidor\": \"" s "\"}"))))
      (is (= s (proteger s)))
      (is (nil? (proteger nil))))
    (let [cols ["ente_id" "ator_tipo" "identidade_id" "acao" "ip"]
          linha (protecao/protetor-de-linha "auditoria.registro" cols proteger pseudo)]
      (testing "na trilha: ato como cidadao -> pseudonimo e sem IP, mesmo para quem tambem e' servidor"
        (is (= ["e" "cidadao" "#p-bbbb" "transparencia/seguir" nil]
               (linha ["e" "cidadao" s "transparencia/seguir" "10.0.0.1"]))))
      (testing "o ato que pode ser anonimo sai sem ator"
        (is (= ["e" "cidadao" nil "participacao/protocolar-manifestacao" nil]
               (linha ["e" "cidadao" c "participacao/protocolar-manifestacao" "10.0.0.1"]))))
      (testing "o servidor agindo como servidor segue identificado, com IP"
        (is (= ["e" "pessoa" s "legislativo/protocolar" "10.0.0.1"]
               (linha ["e" "pessoa" s "legislativo/protocolar" "10.0.0.1"])))))
    (testing "fora da trilha, so' a troca de ids"
      (is (= ["#p-aaaa" "cidadao"] ((protecao/protetor-de-linha "x.y" ["a" "ator_tipo"] proteger pseudo) [c "cidadao"]))))))

(deftest o-pseudonimo-nunca-funde-duas-pessoas
  (let [p (protecao/sem-colisao (fn [id] (if (#{"a" "b"} id) "#igual" (str "#" id))))]
    (is (= "#igual" (p "a")))
    (is (= "#igual" (p "a")) "a mesma pessoa, o mesmo pseudonimo")
    (is (= "#c" (p "c")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mesmo pseudonimo" (p "b"))
        "outra pessoa com o mesmo pseudonimo: a exportacao falha em vez de fundir as duas")))
