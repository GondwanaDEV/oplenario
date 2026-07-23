(ns oplenario.cadastros.vereador-adapters-in-test
  "UNITARIO (DB-free) — Slice 4: o gate de ENTRADA wire/in -> dominio das 4 escritas. Prova validacao
  (fail-closed -> :validacao/invalido), injecao de id/ente-id (nunca do cliente) e coercao de datas/uuids."
  (:require [clojure.test :refer [deftest is]]
            [oplenario.cadastros.adapters.in.vereador :as a])
  (:import (java.time LocalDate)))

(def ^:private ATOR {:ente-id (random-uuid) :identidade-id (random-uuid)})

;; `hoje` CRAVADO (as duas rotas com teto de data o recebem da borda, resolvido pelo relogio injetado) —
;; nenhum teste deste ns le o relogio do sistema.
(def ^:private HOJE (LocalDate/of 2026 7 22))

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
  (let [m (a/registrar-licenca->dominio ATOR HOJE {"inicio" "2026-03-01" "motivo" "saude"})]
    (is (uuid? (:id m)))
    (is (= (:ente-id ATOR) (:ente-id m)))
    (is (= (LocalDate/of 2026 3 1) (:inicio m)))
    (is (nil? (:fim m)))
    (is (= "saude" (:motivo m)))))

(deftest licenca-sem-inicio-e-invalido
  (is (validacao-invalida? #(a/registrar-licenca->dominio ATOR HOJE {"motivo" "sem data"}))))

(deftest licenca-com-inicio-posterior-a-hoje-e-invalido
  ;; `registrar-licenca!` flipa `mandato.estado` p/ 'licenciado' no INSTANTE do POST e nao ha agendador que
  ;; faca esse flip esperar o inicio: aceitar licenca que so' comeca daqui a semanas tira o vereador do
  ;; exercicio HOJE (a policy de `meu-voto` exige mandato vigente) e o prende num estado que a reassuncao
  ;; nao alcanca.
  (is (validacao-invalida? #(a/registrar-licenca->dominio ATOR HOJE {"inicio" "2026-09-01"}))
      "licenca que ainda nao comecou -> 400 na borda, nunca um mandato 'licenciado' antecipado")
  (is (= HOJE (:inicio (a/registrar-licenca->dominio ATOR HOJE {"inicio" "2026-07-22"})))
      "comecar HOJE e' valido (a borda e' inclusiva)")
  (is (= (LocalDate/of 2024 3 1) (:inicio (a/registrar-licenca->dominio ATOR HOJE {"inicio" "2024-03-01"})))
      "licenca RETROATIVA continua de primeira classe — e' o caso comum da secretaria"))

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

;; ---- reassuncao de mandato ----
(deftest reassuncao-coage-reassumiu-em-para-localdate
  (is (= (LocalDate/of 2026 4 10) (a/reassumir-mandato->dominio HOJE {"reassumiu-em" "2026-04-10"}))
      "devolve so' a data coagida (mesma forma de ligar-identidade->dominio, que devolve so' o uuid) — a
       aritmetica do -1 dia mora no Repo, nunca aqui"))

(deftest reassuncao-sem-data-ou-com-data-malformada-e-invalido
  (is (validacao-invalida? #(a/reassumir-mandato->dominio HOJE {})) "corpo sem reassumiu-em -> invalido")
  (is (validacao-invalida? #(a/reassumir-mandato->dominio HOJE {"reassumiu-em" ""}))
      "string vazia nao e' data ({:min 1} do wire)")
  ;; buraco de cobertura herdado das outras rotas de data: nenhum teste mandava data MALFORMADA.
  (is (validacao-invalida? #(a/reassumir-mandato->dominio HOJE {"reassumiu-em" "10/04/2026"}))
      "data em formato brasileiro -> 400, nunca 500 no LocalDate/parse")
  (is (validacao-invalida? #(a/reassumir-mandato->dominio HOJE {"reassumiu-em" "2026-13-45"}))
      "data sintaticamente ISO mas inexistente -> 400")
  (is (validacao-invalida? #(a/reassumir-mandato->dominio HOJE {"reassumiu-em" "2026-04-10" "vereador-id" "forja"}))
      ":closed recusa campo fora do contrato (anti-forja)"))

(deftest data-com-ano-estendido-e-invalido-na-borda-nunca-500-no-driver
  ;; `LocalDate/parse` ACEITA o ISO de ano estendido com sinal: "+10000000-01-01" nao lanca
  ;; DateTimeParseException, atravessa a borda e so' estoura no bind do driver como PSQLException 22008
  ;; (`date out of range`) — que nao e' ExceptionInfo, nao casa em nenhum catch de conflito e vira 500.
  (doseq [hostil ["+10000000-01-01" "+999999999-12-31" "-999999999-01-01" "10000000-01-01"]]
    (is (validacao-invalida? #(a/reassumir-mandato->dominio HOJE {"reassumiu-em" hostil}))
        (str hostil " -> 400 na borda"))
    (is (validacao-invalida? #(a/registrar-licenca->dominio ATOR HOJE {"inicio" hostil}))
        (str hostil " -> 400 tambem em /licencas (o helper e' o mesmo)"))
    (is (validacao-invalida?
          #(a/registrar-mandato->dominio ATOR (random-uuid) {"legislatura-id" (str (random-uuid))
                                                            "natureza" "titular" "vigencia-inicio" hostil}))
        (str hostil " -> 400 tambem em /mandatos"))))

(deftest reassuncao-com-data-futura-e-invalido
  ;; Fato datado e' no PASSADO. Sem o teto, um "2999" digitado no lugar de "2029" grava `fim = 2998-12-31`
  ;; na licenca aberta — linha que nenhum caminho de escrita alcanca depois (o unico UPDATE so' casa
  ;; `fim IS NULL`, nao ha DELETE nem PATCH) — enquanto o mandato volta a 'vigente' e conta no quorum de
  ;; hoje: a pagina publica passa a dizer "exercicio de 2999 em diante".
  (is (validacao-invalida? #(a/reassumir-mandato->dominio HOJE {"reassumiu-em" "2999-01-01"}))
      "data futura -> 400, e a licenca segue ABERTA (fail-closed)")
  (is (validacao-invalida? #(a/reassumir-mandato->dominio HOJE {"reassumiu-em" "2026-07-23"}))
      "amanha ja' e' futuro")
  (is (= HOJE (a/reassumir-mandato->dominio HOJE {"reassumiu-em" "2026-07-22"}))
      "voltar HOJE e' o caminho normal — o teto e' inclusivo"))
