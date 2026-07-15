(ns oplenario.cadastros.vereador-adapters-in-test
  "UNITARIO (DB-free) — Slice 4: o gate de ENTRADA wire/in -> dominio das 4 escritas. Prova validacao
  (fail-closed -> :validacao/invalido), injecao de id/ente-id (nunca do cliente) e coercao de datas/uuids."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.cadastros.adapters.in.vereador :as a])
  (:import (java.time LocalDate)))

(def ^:private ATOR {:ente-id (random-uuid) :identidade-id (random-uuid)})

(defn- validacao-invalida? [f]
  (try (f) false
    (catch clojure.lang.ExceptionInfo e (= :validacao/invalido (:tipo (ex-data e))))))

;; ---- criar ----
(deftest criar-injeta-id-e-ente-e-mantem-nome
  (let [m (a/criar-vereador->dominio ATOR {"nome" "Helena Matos" "nome-parlamentar" "Helena"})]
    (is (uuid? (:id m)) "id gerado no servidor, nunca do cliente")
    (is (= (:ente-id ATOR) (:ente-id m)) "ente-id vem do ator")
    (is (= "Helena Matos" (:nome m)))
    (is (= "Helena" (:nome-parlamentar m)))))

(deftest criar-sem-nome-e-invalido
  (is (validacao-invalida? #(a/criar-vereador->dominio ATOR {"nome-parlamentar" "so apelido"})))
  (is (validacao-invalida? #(a/criar-vereador->dominio ATOR {"nome" "   "})) "nome em branco -> invalido"))

(deftest criar-recusa-campo-extra
  (is (validacao-invalida? #(a/criar-vereador->dominio ATOR {"nome" "X" "identidade-id" "forja"}))
      ":closed recusa campo fora do contrato (anti-forja de identidade-id)"))

;; ---- editar ----
(deftest editar-exige-ao-menos-um-campo
  (is (validacao-invalida? #(a/editar-vereador->dominio {})) "corpo vazio -> invalido")
  (is (= {:nome "Novo Nome"} (a/editar-vereador->dominio {"nome" "Novo Nome"})))
  (is (= {:nome-parlamentar "Apelido"} (a/editar-vereador->dominio {"nome-parlamentar" "Apelido"})))
  (is (validacao-invalida? #(a/editar-vereador->dominio {"nome" "  "})) "nome presente e em branco -> invalido"))

(deftest editar-esvaziar-nome-parlamentar-vira-nil
  ;; Limpar o apelido: campo presente-mas-branco = alteracao valida; chave fica presente (contains? true)
  ;; p/ atualizar! escrever, mas o VALOR e' nil -> NULL no banco, nunca "".
  (let [m (a/editar-vereador->dominio {"nome-parlamentar" ""})]
    (is (contains? m :nome-parlamentar) "chave presente -> atualizar! escreve a coluna")
    (is (nil? (:nome-parlamentar m)) "branco vira nil (NULL), nunca string vazia")))

(deftest criar-nome-parlamentar-branco-vira-nil
  (is (nil? (:nome-parlamentar (a/criar-vereador->dominio ATOR {"nome" "So Nome" "nome-parlamentar" "  "})))
      "apelido em branco na criacao tambem normaliza p/ nil"))

;; ---- mandato ----
(deftest mandato-coage-datas-e-uuid-e-injeta-estado-vigente
  (let [ver (random-uuid) leg (random-uuid)
        m (a/registrar-mandato->dominio ATOR ver {"legislatura-id" (str leg) "partido" "PT"
                                                  "natureza" "titular" "vigencia-inicio" "2025-01-01"})]
    (is (= ver (:vereador-id m)) "vereador-id vem do path, nao do corpo")
    (is (= leg (:legislatura-id m)) "legislatura-id coagido de string p/ uuid")
    (is (= "vigente" (:estado m)) "estado nasce 'vigente' (nao e' entrada)")
    (is (= (LocalDate/of 2025 1 1) (:vigencia-inicio m)))
    (is (nil? (:vigencia-fim m)))))

(deftest mandato-data-ou-uuid-invalido-e-invalido
  (let [ver (random-uuid)]
    (is (validacao-invalida? #(a/registrar-mandato->dominio ATOR ver {"legislatura-id" "nao-uuid" "natureza" "titular" "vigencia-inicio" "2025-01-01"})))
    (is (validacao-invalida? #(a/registrar-mandato->dominio ATOR ver {"legislatura-id" (str (random-uuid)) "natureza" "titular" "vigencia-inicio" "01/01/2025"})))
    (is (validacao-invalida? #(a/registrar-mandato->dominio ATOR ver {"legislatura-id" (str (random-uuid)) "natureza" "prefeito" "vigencia-inicio" "2025-01-01"}))
        "natureza fora do enum -> invalido")))

(deftest mandato-vigencia-fim-antes-do-inicio-e-invalido
  (let [ver (random-uuid) leg (random-uuid)]
    (is (validacao-invalida?
          #(a/registrar-mandato->dominio ATOR ver {"legislatura-id" (str leg) "natureza" "titular"
                                                    "vigencia-inicio" "2025-06-01" "vigencia-fim" "2025-01-01"}))
        "vigencia-fim anterior a vigencia-inicio -> 400, nunca 500 no daterange do db/")
    (is (= (LocalDate/of 2025 1 1)
           (:vigencia-inicio (a/registrar-mandato->dominio ATOR ver {"legislatura-id" (str leg) "natureza" "titular"
                                                                     "vigencia-inicio" "2025-01-01" "vigencia-fim" "2025-06-01"})))
        "vigencia-fim apos vigencia-inicio continua valido (caminho feliz preservado)")))

;; ---- licenca ----
(deftest licenca-coage-inicio-e-injeta-id-ente
  (let [m (a/registrar-licenca->dominio ATOR {"inicio" "2026-03-01" "motivo" "saude"})]
    (is (uuid? (:id m)))
    (is (= (:ente-id ATOR) (:ente-id m)))
    (is (= (LocalDate/of 2026 3 1) (:inicio m)))
    (is (nil? (:fim m)))
    (is (= "saude" (:motivo m)))))

(deftest licenca-sem-inicio-e-invalido
  (is (validacao-invalida? #(a/registrar-licenca->dominio ATOR {"motivo" "sem data"}))))

;; ---- ligar identidade (Task 9, review IMPORTANT-3: faltava o unitario deste adapter) ----
(deftest ligar-identidade-coage-identidade-id-para-uuid
  (let [ident (random-uuid)]
    (is (= ident (a/ligar-identidade->dominio {"identidade-id" (str ident)}))
        "devolve so' o uuid coagido, nao um mapa (unico campo do contrato)")))

(deftest ligar-identidade-sem-ou-com-uuid-invalido-e-invalido
  (is (validacao-invalida? #(a/ligar-identidade->dominio {})) "corpo sem identidade-id -> invalido")
  (is (validacao-invalida? #(a/ligar-identidade->dominio {"identidade-id" "nao-e-um-uuid"}))
      "identidade-id que nao parseia como uuid -> invalido")
  (is (validacao-invalida? #(a/ligar-identidade->dominio {"identidade-id" (str (random-uuid)) "extra" "forja"}))
      ":closed recusa campo fora do contrato"))
