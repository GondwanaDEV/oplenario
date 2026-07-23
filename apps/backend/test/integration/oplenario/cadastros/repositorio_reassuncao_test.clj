(ns oplenario.cadastros.repositorio-reassuncao-test
  "INTEGRACAO (PG real) — REASSUNCAO DE MANDATO: o caminho de escrita que faltava em `cadastros` e que
  fechava DOIS buracos ao mesmo tempo, ambos nascidos do mesmo fato (`cadastros.mandato_licenca` nao tinha
  nenhum UPDATE no sistema inteiro — o unico statement que a tocava era o INSERT de `inserir-licenca!`):

  (1) A JANELA DE EXERCICIO congelava para sempre. Licenca com `fim` nil e' caminho de primeira classe do
      wire (`RegistrarLicenca`, `:fim` opcional e `:maybe`), e `janelas-de-exercicio` subtrai `[inicio, nil]`
      = tudo dali para frente. Quem se licenciava sem data de volta e reassumia publicava 100% de presenca
      tendo faltado a tudo desde a volta — numa pagina PUBLICA e NOMINAL.
  (2) O mandato ficava `licenciado` para sempre, e isso BLOQUEAVA a proxima licenca:
      `mandato-vigente-de-vereador` exige `estado = 'vigente'`, entao a Casa nao conseguia licenciar de novo
      quem ja' se licenciara uma vez — mesmo que a primeira licenca tivesse durado 5 dias e vencido ha' anos.

  A DECISAO DE SEMANTICA que este ns pina: a API recebe `reassumiu-em` = o dia em que a pessoa VOLTOU A
  EXERCER, e grava `fim = reassumiu-em MENOS 1 DIA`, porque a subtracao de intervalos do kernel e' INCLUSIVA
  nos dois lados (`tempo/subtrair-um` fecha o resto a esquerda em `(.minusDays b-inicio 1)`). Gravar
  `fim = reassumiu-em` comeria o dia da volta da janela de todo mundo — erro de um dia, silencioso, num
  numero publico.

  Os asserts de JANELA passam pelo seam REAL do host (`rotas/ficha-e-janelas-publicas`), nao por uma
  recomposicao do teste: e' a janela publicada que precisa ficar certa, nao a coluna."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [oplenario.cadastros.components.repositorio :as repo]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.cadastros.db.vereador :as vereador]
            [oplenario.config :as config]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.migracao :as migracao]
            [oplenario.rotas :as rotas])
  (:import (java.time LocalDate)))

(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*repo* (repo/->RepoCadastrosPg c)]
        (try (t) (finally (component/stop c)))))))

(defn- d [s] (LocalDate/parse s))

(defn- iv
  "Intervalo na forma canonica do kernel: INCLUSIVO dos dois lados, `fim` nil = em aberto."
  [inicio fim]
  {:inicio (d inicio) :fim (when fim (d fim))})

;; reference data (sem ente_id) e' semeada como DONO (bypassa RLS) — mesmo padrao de
;; repositorio-escrita-test/seed-municipio!. `inserir-municipio!` e' idempotente (ON CONFLICT).
(defn- seed-municipio! []
  (referencia/inserir-municipio! (:ds (:datasource *repo*))
    {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 1}))

(defn- semear-casa-com-mandato!
  "Ente + legislatura + vereador + UM stint aberto desde `inicio`, NUMA tx do tenant. `estado` default
  'vigente' (o vocabulario e' o do CHECK da mig 0010: vigente|licenciado|cassado|renunciado|falecido|
  concluido). INSERT direto de mandato — o guard app-level de sobreposicao nao e' o que esta' sob teste."
  ([inicio] (semear-casa-com-mandato! inicio "vigente"))
  ([inicio estado]
   (let [ente (random-uuid) leg (random-uuid) ver (random-uuid) mandato (random-uuid)]
     (seed-municipio!)
     (repo/transacao *repo* ente
       (fn [tx]
         (estrutura/inserir-ente! tx {:ente-id ente :municipio-ibge "2304400"
                                      :nome-oficial "Camara da Reassuncao"})
         (estrutura/inserir-legislatura! tx {:id leg :ente-id ente :numero 21 :ano-inicio 2024
                                             :ano-fim 2028 :vigente true})
         (vereador/inserir! tx {:id ver :ente-id ente :nome "Ana Reassuncao"})
         (vereador/inserir-mandato! tx {:id mandato :ente-id ente :vereador-id ver :legislatura-id leg
                                        :partido "PDT" :estado estado :natureza "titular"
                                        :vigencia-inicio inicio :vigencia-fim nil})))
     {:ente ente :leg leg :ver ver :mandato mandato})))

(defn- estado! [ente ver mandato-id]
  (:estado (first (filter #(= mandato-id (:id %)) (repo/mandatos-do-vereador *repo* ente ver)))))

(defn- licencas! [ente mandato-id]
  (repo/transacao *repo* ente (fn [tx] (vereador/licencas-de-mandatos tx ente [mandato-id]))))

(defn- janelas!
  "A janela de exercicio COMO A ROTA PUBLICA a publica — mesmo seam do host, sem recomposicao aqui."
  [ente ver data]
  (:janelas (rotas/ficha-e-janelas-publicas *repo* ente ver data)))

(defn- em-alguma-janela? [janelas ^LocalDate dia]
  (boolean (some (fn [{:keys [inicio fim]}]
                   (and (not (.isAfter ^LocalDate inicio dia))
                        (or (nil? fim) (not (.isBefore ^LocalDate fim dia)))))
                 janelas)))

(defn- licenciar! [ente ver inicio fim]
  (repo/registrar-licenca! *repo* ente ver
    {:id (random-uuid) :ente-id ente :inicio inicio :fim fim :motivo "motivo"} inicio))

;; ---------------------------------------------------------------------------
;; a decisao de semantica (o unico teste que pina o -1 dia)
;; ---------------------------------------------------------------------------

(deftest reassumir-em-D-fecha-a-licenca-em-D-menos-1-e-o-dia-D-conta-como-exercicio
  (let [{:keys [ente ver mandato]} (semear-casa-com-mandato! (d "2026-01-01"))
        volta (d "2026-04-10")
        hoje (d "2026-12-31")]
    (licenciar! ente ver (d "2026-03-01") nil)
    (let [r (repo/reassumir-mandato! *repo* ente ver volta)]
      (is (= {:id mandato :fim (d "2026-04-09")} r)
          "reassumir em D devolve o mandato reaberto e o `fim` GRAVADO = D menos 1 (bordas inclusivas)")
      (is (= [{:mandato-id mandato :inicio (d "2026-03-01") :fim (d "2026-04-09")}]
             (licencas! ente mandato))
          "a coluna `fim` da licenca e' a VESPERA da volta, nunca o dia da volta")
      (let [js (janelas! ente ver hoje)]
        (is (= [(iv "2026-01-01" "2026-02-28") (iv "2026-04-10" nil)] js)
            "a janela PUBLICA renasce EM D — gravar `fim = D` a faria renascer so' em D+1")
        (is (em-alguma-janela? js volta)
            "o proprio dia da reassuncao conta como exercicio (o erro de um dia, dito direto)")))))

;; ---------------------------------------------------------------------------
;; defeito (1): a licenca sem `fim` congelava a janela para sempre
;; ---------------------------------------------------------------------------

(deftest licenca-sem-fim-deixa-de-comer-a-janela-depois-da-reassuncao
  (let [{:keys [ente ver]} (semear-casa-com-mandato! (d "2026-01-01"))
        hoje (d "2026-12-31")]
    (licenciar! ente ver (d "2026-06-01") nil)
    (is (= [(iv "2026-01-01" "2026-05-31")] (janelas! ente ver hoje))
        "ANTES: `fim` nil come a janela de 06-01 em diante — o carry I-5 invertido")
    (repo/reassumir-mandato! *repo* ente ver (d "2026-09-01"))
    (is (= [(iv "2026-01-01" "2026-05-31") (iv "2026-09-01" nil)] (janelas! ente ver hoje))
        "DEPOIS: a janela volta a abrir no dia da volta — o fechamento deixou de ser irreversivel")))

;; ---------------------------------------------------------------------------
;; defeito (2): o mandato ficava `licenciado` para sempre e travava a proxima licenca
;; ---------------------------------------------------------------------------

(deftest vereador-que-ja-se-licenciou-e-reassumiu-pode-licenciar-de-novo
  (let [{:keys [ente ver mandato]} (semear-casa-com-mandato! (d "2026-01-01"))]
    (licenciar! ente ver (d "2026-02-01") (d "2026-02-10"))
    (is (= "licenciado" (estado! ente ver mandato)) "a 1a licenca flipa o estado, como sempre fez")
    (repo/reassumir-mandato! *repo* ente ver (d "2026-02-11"))
    (is (= "vigente" (estado! ente ver mandato)) "a reassuncao devolve o alvo que a 2a licenca precisa achar")
    (let [id2 (random-uuid)]
      (is (= {:id id2}
             (repo/registrar-licenca! *repo* ente ver
               {:id id2 :ente-id ente :inicio (d "2026-05-01") :fim nil :motivo "2a"} (d "2026-05-01")))
          "a SEGUNDA licenca passa — sem a reassuncao daria :conflito/sem-mandato-vigente para sempre"))))

(deftest reassumir-mandato-com-licenca-ja-vencida-devolve-o-estado-a-vigente
  ;; O guard de entrada da reassuncao e' o mandato estar `licenciado`, NAO existir licenca aberta: exigir
  ;; licenca aberta deixaria este caso (licenca de 5 dias, vencida ha' anos) preso para sempre.
  (let [{:keys [ente ver mandato]} (semear-casa-com-mandato! (d "2024-01-01"))]
    (licenciar! ente ver (d "2024-03-01") (d "2024-03-05"))
    (is (= "licenciado" (estado! ente ver mandato)))
    (let [r (repo/reassumir-mandato! *repo* ente ver (d "2026-07-22"))]
      (is (= mandato (:id r)) "ZERO linhas fechadas e ainda assim a reassuncao vale")
      (is (= "vigente" (estado! ente ver mandato)))
      (is (= [{:mandato-id mandato :inicio (d "2024-03-01") :fim (d "2024-03-05")}]
             (licencas! ente mandato))
          "a licenca ja' vencida fica INTACTA — o UPDATE so' casa `fim IS NULL`"))))

;; ---------------------------------------------------------------------------
;; os guards (o que a rota NAO pode deixar acontecer)
;; ---------------------------------------------------------------------------

(deftest reassumir-mandato-cassado-nao-ressuscita-o-mandato
  (let [{:keys [ente ver mandato]} (semear-casa-com-mandato! (d "2025-01-01") "cassado")
        ex (try (repo/reassumir-mandato! *repo* ente ver (d "2026-07-22"))
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "sem o guard de `licenciado`, anular uma cassacao seria um POST")
    (is (= :conflito/sem-mandato-licenciado (:tipo (ex-data ex))))
    (is (= "cassado" (estado! ente ver mandato)) "cassado/renunciado/falecido seguem terminais")))

(deftest reassumir-antes-do-inicio-da-licenca-nao-muda-estado-nem-fecha-licenca
  (let [{:keys [ente ver mandato]} (semear-casa-com-mandato! (d "2026-01-01"))]
    (licenciar! ente ver (d "2026-03-01") nil)
    (let [ex (try (repo/reassumir-mandato! *repo* ente ver (d "2026-02-10"))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "'voltei antes de sair' e' recusado")
      (is (= :conflito/retorno-anterior-ao-inicio (:tipo (ex-data ex)))))
    ;; fail-closed DE VERDADE: nao basta o 409, a tx inteira tem de reverter.
    (is (= "licenciado" (estado! ente ver mandato)) "o estado NAO flipou")
    (is (= [{:mandato-id mandato :inicio (d "2026-03-01") :fim nil}] (licencas! ente mandato))
        "a licenca continua ABERTA — nada gravado pela metade")
    (is (= [(iv "2026-01-01" "2026-02-28")] (janelas! ente ver (d "2026-12-31")))
        "e a janela publica segue exatamente como estava")))

(deftest reassumir-vereador-de-outro-ente-nao-encontra
  (let [{:keys [ente ver mandato]} (semear-casa-com-mandato! (d "2026-01-01"))
        outra-casa (random-uuid)]
    (licenciar! ente ver (d "2026-03-01") nil)
    (is (nil? (repo/reassumir-mandato! *repo* outra-casa ver (d "2026-04-10")))
        "sob a tx de OUTRA Casa o vereador e' invisivel (RLS) -> nil (404), nunca 409 nem escrita")
    (is (= "licenciado" (estado! ente ver mandato)) "e nada mudou na Casa dona")
    (is (= [{:mandato-id mandato :inicio (d "2026-03-01") :fim nil}] (licencas! ente mandato)))
    (is (nil? (repo/reassumir-mandato! *repo* ente (random-uuid) (d "2026-04-10")))
        "vereador-id desconhecido na PROPRIA Casa -> nil (404)")))

;; ---------------------------------------------------------------------------
;; a rede do banco (achado do levantamento, ver relatorio): o EXCLUDE da mig 0059
;; ---------------------------------------------------------------------------

(deftest reassumir-que-colide-com-o-contorno-do-mandato-novo-vira-conflito-nao-500
  ;; O contorno que o operador tinha ATE' esta fatia (documentado em `rotas.clj`) era registrar um mandato
  ;; NOVO 'vigente' para representar o retorno — e ele SOBREPOE o stint licenciado (que fica fora do
  ;; predicado do EXCLUDE `uq_mandato_vigente_sem_overlap`, mig 0059, justamente por nao ser 'vigente').
  ;; Reassumir o stint antigo depois disso viola o EXCLUDE (SQLState 23P01). Sem mapeamento, 500.
  (let [{:keys [ente leg ver mandato]} (semear-casa-com-mandato! (d "2026-01-01"))]
    (licenciar! ente ver (d "2026-03-01") nil)
    (repo/transacao *repo* ente
      (fn [tx]
        (vereador/inserir-mandato! tx {:id (random-uuid) :ente-id ente :vereador-id ver :legislatura-id leg
                                       :partido "PDT" :estado "vigente" :natureza "titular"
                                       :vigencia-inicio (d "2026-04-10") :vigencia-fim nil})))
    (let [ex (try (repo/reassumir-mandato! *repo* ente ver (d "2026-04-10"))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "a colisao com o EXCLUDE sobe como conflito de dominio, nunca como PSQLException crua")
      (is (= :conflito/mandato-sobreposto (:tipo (ex-data ex)))))
    (is (= "licenciado" (estado! ente ver mandato)) "e a tx reverteu inteira")))
