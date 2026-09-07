(ns casa
  "Semente NARRATIVA da demo (plano `docs/superpowers/plans/2026-09-07-prontidao-de-apresentacao.md`,
  Task 0.2) — UMA UNICA Casa, coerente e RE-SEMEAVEL, ao contrario das 4 ilhas de `seed-demo` (§1.2 do
  plano: 4 Casas isoladas por `random-uuid`, nenhum fluxo atravessa duas telas com o mesmo dado).

  `semear!` cria: 1 municipio (Fortaleza, idempotente) + 1 ente de UUID FIXO (`ente-id`, constante —
  e' isso que torna a Casa re-executavel, nao `random-uuid`) + 1 legislatura 2025-2028 vigente + 17
  vereadores (nome/nome-parlamentar/partido distintos) + 17 mandatos vigentes (1 licenciado, p/ a
  jornada de licenca/reassuncao) + Mesa Diretora (presidente/vice/1º e 2º secretarios) + 3 comissoes
  permanentes (CCJ, Financas e Orcamento, Obras e Servicos Publicos) com presidente e membros + 4
  identidades com vinculo e papel: secretaria (`secretario`), presidente da Mesa (`vereador` +
  `admin_ente`), vereador comum (`vereador`), cidadao (sem vinculo/papel).

  IDEMPOTENCIA (Task 0.7 do plano exige 'reusar-se-existir'): `ente`/`municipio` usam ON CONFLICT nos
  proprios `db/` (`estrutura/inserir-ente!`, `referencia/inserir-municipio!`) — idempotentes por
  natureza, chamados sempre. As 4 identidades (`identidade/inserir!` por CPF, `vinculo/criar!` por
  (ente,identidade,tipo), `vinculo/adicionar-papel!` por (ente,identidade,papel)) SAO idempotentes por
  desenho (upsert/DO NOTHING) — tambem chamadas sempre, nas duas rotas. O BLOCO cadastral (legislatura +
  17 vereadores + mandatos + Mesa + comissoes) e' o UNICO sem ON CONFLICT nos `db/` que usa — por isso
  roda so' na PRIMEIRA chamada, atras do gate `ja-semeada?` (legislatura vigente do ente ja existe?);
  numa segunda chamada, `semear!` PULA a criacao e RELE do banco em vez de duplicar linha.

  PREMISSA CORRIGIDA contra o codigo (relatada na Task 0.2): o teste dado pelo plano usa `sistema`
  como um sistema Component JA' BOOTADO (mesmo formato de `test/integration/oplenario/sistema_test.clj`
  e `repo_test.clj` — `(component/start (oplenario.sistema/novo-sistema (config/carregar)))`), nao o
  `com-ds` (abre/fecha por chamada) que `seed_demo.clj` usa — `semear!` chama a MESMA Casa duas vezes
  seguidas no mesmo teste, o que exige um datasource que sobreviva entre as duas chamadas."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.db.comissao :as comissao]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.cadastros.db.referencia :as referencia]
            [oplenario.cadastros.db.vereador :as vereador]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.tenancy :as tenancy])
  (:import (java.time LocalDate)))

;; ---------- constantes da Casa ----------

(def ente-id
  "UUID FIXO da Casa da demo — NAO trocar entre execucoes: e' o que torna `semear!` re-executavel (a
  MESMA Casa a cada chamada), ao contrario do `random-uuid` que `seed_demo.clj` usa em toda semente."
  #uuid "10000000-0000-0000-0000-000000000001")

(def ^:private municipio
  {:codigo-ibge "2304400" :nome "Fortaleza" :uf "CE" :capital true :populacao 2703391})

(def ^:private hoje
  "Vigencia-inicio da legislatura/mandatos — 1º de janeiro de 2025 (posse), cobre 'hoje' (a legislatura
  2025-2028 segue vigente em qualquer data em que a demo rodar dentro dela)."
  (LocalDate/of 2025 1 1))

;; CPFs FIXOS e validos (mesmo digito-verificador de `identidade.models.identidade/valido-cpf?` — mod-11,
;; pesos 10..2 e 11..2) p/ os 4 atores nomeados. FIXOS (nao `random-uuid`/aleatorio) e' o que faz
;; `identidade/inserir!` (idempotente por CPF) devolver o MESMO id em toda chamada de `semear!`.
(def ^:private cpf-secretaria "12345678062")
(def ^:private cpf-presidente "23456789092")
(def ^:private cpf-vereador-comum "34567890175")
(def ^:private cpf-cidadao "45678901249")

;; 17 vereadores — nome, nome parlamentar e partido DISTINTOS (nada de "Vereador 1"). Indices usados
;; abaixo p/ atribuir papeis (Mesa, comissoes, licenca, identidade de login):
;;   0 presidente da Mesa (+ identidade "presidente")   1 vice   2 1º secretario   3 2º secretario
;;   4 presidente da CCJ   5 membro CCJ (+ identidade "vereador comum")   6 membro CCJ
;;   7 presidente Financas   8 membro Financas   9 membro Financas
;;  10 presidente Obras   11 membro Obras   12 membro Obras
;;  16 mandato LICENCIADO (jornada de licenca/reassuncao)
(def ^:private vereadores-base
  [{:nome "Antônio Carlos Ferreira" :nome-parlamentar "Antônio Ferreira"    :partido "PT"}
   {:nome "Beatriz Souza Lima"      :nome-parlamentar "Beatriz Lima"        :partido "PSDB"}
   {:nome "Carlos Eduardo Mendes"   :nome-parlamentar "Carlos Mendes"       :partido "PL"}
   {:nome "Débora Castro Nunes"     :nome-parlamentar "Débora Nunes"        :partido "PDT"}
   {:nome "Eduardo Barros Almeida"  :nome-parlamentar "Eduardo Almeida"     :partido "MDB"}
   {:nome "Fernanda Rocha Pinto"    :nome-parlamentar "Fernanda Pinto"      :partido "PSB"}
   {:nome "Gustavo Henrique Dias"   :nome-parlamentar "Gustavo Dias"        :partido "PP"}
   {:nome "Helena Martins Vieira"   :nome-parlamentar "Helena Vieira"       :partido "PSOL"}
   {:nome "Igor Farias Cavalcante"  :nome-parlamentar "Igor Cavalcante"     :partido "REPUBLICANOS"}
   {:nome "Juliana Alves Correia"   :nome-parlamentar "Juliana Correia"     :partido "UNIÃO"}
   {:nome "Marcos Vinícius Teixeira":nome-parlamentar "Marcos Teixeira"     :partido "PSD"}
   {:nome "Natália Gomes Ribeiro"   :nome-parlamentar "Natália Ribeiro"     :partido "PODEMOS"}
   {:nome "Otávio Cesar Monteiro"   :nome-parlamentar "Otávio Monteiro"     :partido "AVANTE"}
   {:nome "Patrícia Nogueira Santos":nome-parlamentar "Patrícia Santos"     :partido "CIDADANIA"}
   {:nome "Rodrigo Peixoto Cunha"   :nome-parlamentar "Rodrigo Cunha"       :partido "SOLIDARIEDADE"}
   {:nome "Simone Andrade Rezende"  :nome-parlamentar "Simone Rezende"      :partido "NOVO"}
   {:nome "Thiago Moraes Bezerra"   :nome-parlamentar "Thiago Bezerra"      :partido "PL"}])

(def ^:private idx-presidente 0)
(def ^:private idx-vereador-comum 5)
(def ^:private idx-licenciado 16)

(def ^:private cargos-mesa {0 "presidente" 1 "vice" 2 "1_secretario" 3 "2_secretario"})

(def ^:private comissoes-permanentes
  [["Comissão de Constituição e Justiça"    4  [5 6]]
   ["Comissão de Finanças e Orçamento"      7  [8 9]]
   ["Comissão de Obras e Serviços Públicos" 10 [11 12]]])

;; ---------- identidades (sempre idempotentes — rodam nas duas rotas) ----------

(defn- criar-identidades!
  "As 4 identidades nomeadas + o vinculo/papel de quem tem acesso a Casa (secretaria/presidente/vereador
  comum) — o cidadao fica SEM vinculo (a leitura publica do portal nao exige login, §1.5 do plano).
  `identidade/inserir!`, `vinculo/criar!` e `vinculo/adicionar-papel!` sao TODOS idempotentes (upsert por
  CPF / DO NOTHING por chave natural) — seguro chamar em toda execucao de `semear!`."
  [ds]
  (let [sec-id  (id/inserir! ds {:id (random-uuid) :cpf cpf-secretaria :nome "Marina Alencar Freire"})
        pres-id (id/inserir! ds {:id (random-uuid) :cpf cpf-presidente :nome (:nome (nth vereadores-base idx-presidente))})
        ver-id  (id/inserir! ds {:id (random-uuid) :cpf cpf-vereador-comum :nome (:nome (nth vereadores-base idx-vereador-comum))})
        cid-id  (id/inserir! ds {:id (random-uuid) :cpf cpf-cidadao :nome "Roberta Costa Aguiar"})]
    (tenancy/com-tenant* ds ente-id
      (fn [tx]
        (vinc/criar! tx {:id (random-uuid) :ente-id ente-id :identidade-id sec-id :tipo "servidor"})
        (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente-id :identidade-id sec-id :papel "secretario"})
        (vinc/criar! tx {:id (random-uuid) :ente-id ente-id :identidade-id pres-id :tipo "vereador"})
        (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente-id :identidade-id pres-id :papel "vereador"})
        (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente-id :identidade-id pres-id :papel "admin_ente"})
        (vinc/criar! tx {:id (random-uuid) :ente-id ente-id :identidade-id ver-id :tipo "vereador"})
        (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente-id :identidade-id ver-id :papel "vereador"})))
    {:secretaria sec-id :presidente pres-id :vereador ver-id :cidadao cid-id}))

;; ---------- o bloco cadastral (so' roda na PRIMEIRA chamada — ver `ja-semeada?`) ----------

(defn- ja-semeada?
  "A Casa ja tem legislatura vigente? — o gate de idempotencia do bloco cadastral (legislatura +
  vereadores + mandatos + Mesa + comissoes), que NAO tem ON CONFLICT nos `db/` que usa."
  [ds]
  (some? (tenancy/com-tenant* ds ente-id (fn [tx] (estrutura/legislatura-vigente tx ente-id)))))

(defn- criar-cadastro!
  "Cria, numa UNICA transacao do tenant, o agregado cadastral inteiro: legislatura + 17 vereadores +
  17 mandatos (1 licenciado) + Mesa Diretora + 3 comissoes permanentes. So' roda quando `ja-semeada?`
  e' false — por isso os ids aqui sao `random-uuid` (a chamada e' garantida unica, o gate e' externo)."
  [ds identidades]
  (tenancy/com-tenant* ds ente-id
    (fn [tx]
      (let [leg-id (random-uuid)
            _ (estrutura/inserir-legislatura! tx {:id leg-id :ente-id ente-id :numero 19
                                                   :ano-inicio 2025 :ano-fim 2028 :vigente true})
            linhas (vec (map-indexed
                          (fn [idx {:keys [nome nome-parlamentar partido]}]
                            {:idx idx :id (random-uuid) :mandato-id (random-uuid)
                             :nome nome :nome-parlamentar nome-parlamentar :partido partido
                             :identidade-id (case idx
                                              0 (:presidente identidades)
                                              5 (:vereador identidades)
                                              nil)})
                          vereadores-base))]
        ;; 17 vereadores
        (doseq [{:keys [id nome nome-parlamentar identidade-id]} linhas]
          (vereador/inserir! tx {:id id :ente-id ente-id :identidade-id identidade-id
                                  :nome nome :nome-parlamentar nome-parlamentar}))
        ;; 17 mandatos — todos vigentes, exceto o de `idx-licenciado` (jornada de licenca/reassuncao)
        (doseq [{:keys [idx id mandato-id partido]} linhas]
          (vereador/inserir-mandato! tx
            {:id mandato-id :ente-id ente-id :vereador-id id :legislatura-id leg-id
             :partido partido :estado (if (= idx idx-licenciado) "licenciado" "vigente")
             :natureza "titular" :vigencia-inicio hoje}))
        ;; Mesa Diretora — presidente/vice/1º e 2º secretarios. CORRIGIDO (ledger #3/#4,
        ;; docs/16-ledger-prontidao.md): a 1a redacao criava SO' `comissao_cargo` ("a Mesa nao e' corpo
        ;; de membresia") — mas `ficha-vereador` (repositorio.clj:136) resolve `:comissoes` via
        ;; `comissao/comissoes-do-vereador` (db/comissao.clj:54-71), que faz INNER JOIN em
        ;; `comissao_membro`. Sem membro, a ficha do presidente mostrava "Sem cargo na Mesa"/"Sem
        ;; comissões atribuídas" enquanto a LISTA e `/sessoes/:id/composicao` (que leem `comissao_cargo`
        ;; direto via `cargo-mesa-lateral`, db/vereador.clj:276-291 — sem exigir membro) mostravam "PT ·
        ;; presidente" ao lado — contradicao visivel na MESMA tela. Os 4 ocupantes de cargo da Mesa
        ;; agora TAMBEM entram como membro — mesmo desenho ja' usado abaixo p/ as 3 comissoes permanentes.
        (let [mesa-id (random-uuid)]
          (comissao/inserir! tx {:id mesa-id :ente-id ente-id :nome "Mesa Diretora" :tipo "mesa"
                                  :legislatura-id leg-id :vigencia-inicio hoje})
          (doseq [[idx cargo] cargos-mesa]
            (comissao/inserir-membro! tx {:id (random-uuid) :ente-id ente-id :comissao-id mesa-id
                                           :vereador-id (:id (nth linhas idx)) :vigencia-inicio hoje})
            (comissao/inserir-cargo! tx {:id (random-uuid) :ente-id ente-id :comissao-id mesa-id
                                          :vereador-id (:id (nth linhas idx)) :cargo cargo
                                          :vigencia-inicio hoje})))
        ;; 3 comissoes permanentes — presidente TAMBEM entra como membro (mesmo desenho de
        ;; `seed_demo.clj/vereadores`: `comissao_membro` cobre todo participante, `comissao_cargo` so' o
        ;; cargo nomeado por cima). `:comissoes` (mapv em vez de doseq) devolvido no retorno de
        ;; `semear!` p/ downstream (ledger #11, docs/16-ledger-prontidao.md: `acervo.clj` precisa das
        ;; comissoes REAIS da Casa em vez de `random-uuid` guard ref orfao).
        {:legislatura-id leg-id
         :vereadores (mapv #(select-keys % [:id :nome :nome-parlamentar :partido :mandato-id]) linhas)
         :comissoes (mapv (fn [[nome presidente-idx membros-idx]]
                             (let [com-id (random-uuid)]
                               (comissao/inserir! tx {:id com-id :ente-id ente-id :nome nome :tipo "permanente"
                                                       :legislatura-id leg-id :vigencia-inicio hoje})
                               (doseq [m-idx (cons presidente-idx membros-idx)]
                                 (comissao/inserir-membro! tx {:id (random-uuid) :ente-id ente-id :comissao-id com-id
                                                                :vereador-id (:id (nth linhas m-idx)) :vigencia-inicio hoje}))
                               (comissao/inserir-cargo! tx {:id (random-uuid) :ente-id ente-id :comissao-id com-id
                                                             :vereador-id (:id (nth linhas presidente-idx))
                                                             :cargo "presidente" :vigencia-inicio hoje})
                               {:id com-id :nome nome :tipo "permanente"}))
                           comissoes-permanentes)}))))

(defn- comissoes-permanentes-existentes
  "Leitura crua (nenhuma fn exposta no Repo/db do modulo p/ 'listar comissoes do ente' — mesmo racional
  de `acervo.clj`/`template-do-rito`): as 3 comissoes permanentes ja' criadas, p/ `ler-cadastro` RELER
  em vez de duplicar."
  [tx]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:id :nome] :from [:cadastros.comissao]
                   :where [:and [:= :ente_id ente-id] [:= :tipo "permanente"]]
                   :order-by [[:nome :asc]]}))))

(defn- ler-cadastro
  "RELE o agregado cadastral do banco (rota de re-execucao, `ja-semeada?` = true) em vez de duplicar."
  [ds]
  (tenancy/com-tenant* ds ente-id
    (fn [tx]
      (let [leg (estrutura/legislatura-vigente tx ente-id)
            linhas (vereador/listar tx ente-id hoje)
            comissoes (mapv #(assoc % :tipo "permanente") (comissoes-permanentes-existentes tx))]
        {:legislatura-id (:id leg)
         ;; ORDEM IMPORTA (nao so' semantica): `:vereadores` tem de imprimir ANTES de `:comissoes` no
         ;; EDN devolvido — `demo/semear-tudo.sh:72-78` extrai "o :id do 1º vereador" com um `sed` NAO
         ;; estrutural (o 1º `:id #uuid ...` LITERAL do arquivo inteiro). Com `:comissoes` primeiro, o
         ;; script pegaria o id de uma COMISSAO e o poll do perfil publico do vereador quebraria — bug
         ;; real, achado rodando `./demo/semear-tudo.sh` de verdade (nao pego por nenhum teste unitario,
         ;; que nao olha ordem de chave em mapa). Mesma ordem que `criar-cadastro!` ja usa.
         :vereadores (mapv (fn [linha]
                              (let [mandato (first (vereador/mandatos-do-vereador tx ente-id (:id linha)))]
                                {:id (:id linha) :nome (:nome linha)
                                 :nome-parlamentar (:nome-parlamentar linha)
                                 :partido (:partido linha) :mandato-id (:id mandato)}))
                            linhas)
         :comissoes comissoes}))))

;; ---------- artefato p/ downstream (sonda, varredura de API — Fases 1/2b do plano) ----------

(defn- gravar-artefato!
  "Grava o resultado em `<DEMO_ARTIFACTS_DIR>/demo-ids.edn` (default `.artifacts`, relativo ao CWD).
  Toda semente posterior e a sonda (Task 1.1) leem esse arquivo para nao cravar id a mao.

  FALHA ALTO se nao conseguir gravar. A primeira redacao engolia a excecao num `catch` com `log/warn`,
  porque o mount `:ro` do container de TESTE torna o CWD read-only; o efeito colateral era que uma
  semeadura REAL sem permissao de escrita passaria como sucesso e o erro so' apareceria tres tasks
  adiante, na sonda, como 'arquivo nao existe' — longe da causa. O teste, que e' quem legitimamente
  nao pode escrever no CWD, aponta `DEMO_ARTIFACTS_DIR` para um diretorio gravavel (`/tmp/...`).
  Quem escolhe tolerar e' quem chama, por configuracao explicita — nunca a funcao, em silencio."
  [resultado]
  (let [dir (io/file (or (System/getenv "DEMO_ARTIFACTS_DIR") ".artifacts"))]
    (.mkdirs dir)
    (let [alvo (io/file dir "demo-ids.edn")]
      (spit alvo (pr-str resultado))
      (log/info "casa/semear!: ids gravados em" (.getAbsolutePath alvo))
      alvo)))

;; ---------- a funcao publica ----------

(defn semear!
  "Semeia (ou rele, se ja semeada) A Casa unica da demo. `sistema` e' um sistema Component BOOTADO
  (`(component/start (oplenario.sistema/novo-sistema (config/carregar)))`, mesmo formato de
  `sistema_test.clj`/`repo_test.clj`) — so' o `:datasource` e' usado aqui.

  Devolve `{:ente :legislatura :vereadores :comissoes :identidades}`; grava o mesmo mapa em
  `.artifacts/demo-ids.edn`. Chamar de novo NAO cria uma segunda Casa — reusa o ente fixo e o
  cadastro ja existente."
  [sistema]
  (let [ds (get-in sistema [:datasource :ds])]
    (try
      (referencia/inserir-municipio! ds municipio)
      (catch Exception e
        (log/warn e "casa/semear!: falha ao semear municipio (idempotente — seguindo)")))
    (tenancy/com-tenant* ds ente-id
      (fn [tx]
        (estrutura/inserir-ente! tx {:ente-id ente-id :municipio-ibge (:codigo-ibge municipio)
                                      :nome-oficial "Câmara Municipal de Fortaleza"
                                      :nome-curto "CM Fortaleza"})))
    (let [identidades (criar-identidades! ds)
          cadastro (if (ja-semeada? ds) (ler-cadastro ds) (criar-cadastro! ds identidades))
          resultado {:ente ente-id :legislatura (:legislatura-id cadastro)
                     :vereadores (:vereadores cadastro) :comissoes (:comissoes cadastro)
                     :identidades identidades}]
      (gravar-artefato! resultado)
      resultado)))
