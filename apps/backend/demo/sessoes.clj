(ns sessoes
  "Semente das TRES SESSOES da demo (plano `docs/superpowers/plans/2026-09-07-prontidao-de-apresentacao.md`,
  Task 0.5, fundida com a antiga Task 0.3) — sobre a Casa de `casa/semear!` e o ACERVO de `acervo/semear!`:
  uma sessao ENCERRADA (semana passada: chamada registrada, 2 votacoes apuradas, tribuna com fala encerrada),
  uma sessao ABERTA (hoje, o telao ao vivo: quorum real, 1 orador na tribuna com cronometro correndo, 1
  votacao nominal ABERTA p/ o vereador votar ao vivo — jornada J4) e uma sessao AGENDADA (semana que vem:
  pauta montada, ZERO presenca). Usa SO os Repo-Component REAIS de sessoes (`RepoSessoes`, ja' booted em
  `sistema` — `(:repo-sessoes sistema)`) e de legislativo (`(:repo-legislativo sistema)`, so' p/ o eixo G —
  votacao) + `(:repo-cadastros sistema)` p/ o ROSTER (o MESMO seam `roster-da-casa` que a chamada real usa,
  §22.10 — `sessoes` nunca importa `cadastros`, e este ns tambem nao: le' o Repo-Component do host).

  A GARANTIA CENTRAL desta task (o defeito mais caro ja' medido no projeto — §1.2 do plano): TODA presenca
  semeada aqui vem de um `:vereador-id` do ROSTER REAL (`roster-da-casa`), NUNCA `random-uuid`. Antes desta
  task a demo tinha 14 presencas de 14 pessoas que nao existem no cadastro (o telao mostrava UUID sem nome)
  enquanto os 17 vereadores nominados tinham ZERO presenca — o teste
  `oplenario.demo.sessoes-test/tres-sessoes-em-estados-distintos` trava isso com uma asserção que reprova.

  VOCABULARIO — lido da FONTE, nao de memoria (regra dura do briefing):
  - `sessoes.sessao.estado` — CHECK estrito, migration `20260620000026-sessoes-sessao.up.sql:19-20`, default
    'agendada': `agendada · aberta · suspensa · encerrada · nao_realizada · arquivada`
    (`oplenario.sessoes.logic/estados-sessao`). A sessao AO VIVO e' 'aberta', NAO 'em_curso' — essa era a
    redacao errada da 1a versao da Task 0.5, corrigida em 07/09 contra o CHECK real. A maquina
    (`oplenario.sessoes.logic/transicoes-sessao`) so' permite agendada->aberta->encerrada (nunca um salto
    direto) — por isso a ENCERRADA passa pelas DUAS transicoes.
  - `sessoes.presenca_evento.tipo` ∈ entrada·saida·retorno·mudanca_modalidade
    (`oplenario.sessoes.logic/tipos-evento-presenca`); `.fonte` ∈ painel_eletronico·manual_secretaria·
    autoatendimento·inferida_por_voto·inferida_por_tribuna (`logic/fontes-presenca`); modalidade de presenca
    ∈ plenario·remoto (`logic/modalidades-presenca`, `src/oplenario/sessoes/logic.clj:181`).
  - `legislativo.votacoes.estado` ∈ aberta·encerrada·anulada; `.modalidade` ∈ nominal·simbolica·secreta;
    `legislativo.votos.voto` ∈ sim·nao·abstencao (migration `20260620000021-legislativo-votacao.up.sql`,
    CHECKs em linha). `quorum_tipo` ∈ maioria_simples·maioria_absoluta·maioria_qualificada_2_3·
    maioria_qualificada_3_5 (mesma migration) — usamos 'maioria_simples' (`sim > nao`,
    `oplenario.legislativo.logic/resultado-votacao`).
  - `sessoes.pauta_item.tipo_item` ∈ proposicao·leitura·comunicado·homenagem
    (`oplenario.sessoes.logic/tipos-item-pauta`); `.fase` ∈ expediente·grande_expediente·ordem_do_dia·
    explicacoes_pessoais·tribuna_livre_cidadao (`logic/fases-pauta`).
  - `sessoes.inscricao_oradores.estado` ∈ inscrita·desistencia; origem_inscricao ∈ pre_sessao_app·
    pre_sessao_secretaria·intra_sessao_pedido·automatica_por_autoria (`logic/origens-inscricao`);
    `sessoes.fala_executada.tipo_fala` ∈ principal·aparte·pela_ordem·questao_de_ordem·explicacao_pessoal·
    comunicado (`logic/tipos-fala`).
  - `cadastros.sessao_legislativa` (migration `20260620000010-cadastros.up.sql:95`) e' um conceito
    DISTINTO de `cadastros.legislatura`: o 'ano legislativo' dentro da legislatura 2025-2028. NAO tem
    UNIQUE alem de (ente_id,id) — a idempotencia deste ns e' um SELECT antes do INSERT (mesmo padrao de
    `casa/ja-semeada?`/`acervo/template-do-rito`, carry #6 do briefing: os `db/` que este ns usa NAO tem
    ON CONFLICT). `sessoes.sessao.sessao_legislativa_id` e' forward-ref (sem FK, §22.10).

  DEPENDE do ACERVO (`acervo/semear!`) ja' ter rodado: as 2 votacoes da ENCERRADA + a votacao ABERTA usam 3
  proposicoes 'em_pauta' reais, e os 4 primeiros itens da pauta da AGENDADA usam as 4 'aguardando_pauta'
  reais — lidas via `(repo-leg/listar-e-contar-proposicoes ...)` (a MESMA leitura paginada do FE, nenhum
  SELECT cru sobre `legislativo.proposicoes`). Falha alto (ex-info) se o acervo nao foi semeado — este ns
  nao encadeia `acervo/semear!` (cada semente da demo assume as anteriores, mesmo desenho de `acervo.clj`
  que assume `casa/semear!`, sem chamar `casa/semear!` de dentro).

  QUORUM/CHAMADA — Disciplina 5 (§22.4.3/§22.5.3, carry #6 do briefing): nada aqui reimplementa a
  aritmetica. `quorum` e o `base-membros` das votacoes chamam `logic/derivar-linhas-da-chamada` +
  `logic/contar-quorum` / `logic/membros-da-casa-da-chamada` — as MESMAS funcoes PURAS que
  `sessoes.controllers/projetar-chamada` usa na rota real `/sessoes/:id/chamada`."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.logic :as slogic])
  (:import (java.time Duration Instant LocalDate)))

;; ---------- constantes (UUIDs FIXOS — re-executavel, mesmo racional de `casa/ente-id`) ----------

(def ^:private sessao-legislativa-id #uuid "10000000-0000-0000-0000-000000000201")
(def ^:private id-encerrada #uuid "10000000-0000-0000-0000-000000000210")
(def ^:private id-aberta #uuid "10000000-0000-0000-0000-000000000211")
(def ^:private id-agendada #uuid "10000000-0000-0000-0000-000000000212")

;; ---------- leituras cruas (nenhuma fn exposta no Repo/db do modulo p/ isto — mesma nota de
;;            acervo.clj/template-do-rito: adicionar uma so' pra este script ficaria fora do escopo) ----------

(defn- sessao-legislativa-existe? [tx ente-id]
  (some? (jdbc/execute-one! tx
           (sql/format {:select [:id] :from [:cadastros.sessao_legislativa]
                        :where [:and [:= :ente_id ente-id] [:= :id sessao-legislativa-id]]}))))

(defn- garantir-sessao-legislativa!
  "Get-or-create (SELECT antes do INSERT — carry #6: `inserir-sessao-legislativa!` nao tem ON CONFLICT)."
  [ds ente-id]
  (tenancy/com-tenant* ds ente-id
    (fn [tx]
      (when-not (sessao-legislativa-existe? tx ente-id)
        (let [leg (estrutura/legislatura-vigente tx ente-id)]
          (when (nil? leg)
            (throw (ex-info "sessoes/semear!: sem legislatura vigente — rode casa/semear! primeiro"
                            {:ente-id ente-id})))
          (estrutura/inserir-sessao-legislativa! tx
            {:id sessao-legislativa-id :ente-id ente-id :legislatura-id (:id leg)
             :numero 1 :ano 2026 :data-inicio (LocalDate/of 2026 2 1) :data-fim (LocalDate/of 2026 12 20)}))))))

(defn- votos-apurados*
  [tx ente-id sessao-id]
  (:n (jdbc/execute-one! tx
        (sql/format {:select [[[:count :*] :n]] :from [:legislativo.votacoes]
                     :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id] [:= :estado "encerrada"]]}))))

(defn- votacao-aberta*
  [tx ente-id sessao-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [:id] :from [:legislativo.votacoes]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id] [:= :estado "aberta"]]
                  :limit 1}))))

;; ---------- o roster + o denominador do quorum (SEMPRE via `logic`, nunca uma 2a aritmetica) ----------

(defn- roster-da-data
  "O roster REAL da Casa na data de referencia da sessao — a MESMA fonte que `registrar-presenca!`/
  `registrar-chamada-conduzida!` exigem assento (carry #6) e a garantia central desta task (nunca
  `random-uuid`)."
  [repo-cad ente-id sessao]
  (repo-cadastros/roster-da-casa repo-cad ente-id (slogic/data-de-referencia-da-sessao sessao)))

(defn- membros-da-casa-agora
  "O DENOMINADOR do quorum sobre a MESMA uniao que a chamada publica (`logic/membros-da-casa-da-chamada`
  sobre `repo/chamada-da-sessao` — nunca uma conta roster-only). Insumo de `encerrar-votacao!` (`base-
  membros`)."
  [repo-s ente-id sessao-id roster]
  (let [{:keys [presencas justificativas]} (repo-sessoes/chamada-da-sessao repo-s ente-id sessao-id (Instant/now))]
    (slogic/membros-da-casa-da-chamada roster presencas justificativas)))

;; ---------- os 3 materiais do acervo que este ns consome (leitura pela API real do Repo) ----------

(defn- materias-em-pauta
  "As 3 proposicoes 'em_pauta' do acervo (`acervo.clj`, Task 0.4) — 2 votam na ENCERRADA, 1 vota (aberta)
  na ABERTA. Falha alto se o acervo nao foi semeado: este ns depende dele, nao o recria."
  [repo-l ente-id]
  (let [itens (:itens (repo-leg/listar-e-contar-proposicoes repo-l ente-id
                        {:pagina 1 :tamanho 10 :estado "em_pauta"}))]
    (when (< (count itens) 3)
      (throw (ex-info (str "sessoes/semear!: precisa de >=3 proposicoes 'em_pauta' do acervo (Task 0.4) — "
                           "rode acervo/semear! primeiro")
                      {:encontradas (count itens)})))
    (mapv :id itens)))

(defn- materias-aguardando-pauta
  "As 4 proposicoes 'aguardando_pauta' do acervo — viram os itens tipo 'proposicao' da pauta da AGENDADA."
  [repo-l ente-id]
  (mapv :id (:itens (repo-leg/listar-e-contar-proposicoes repo-l ente-id
                      {:pagina 1 :tamanho 10 :estado "aguardando_pauta"}))))

;; ---------- a ENCERRADA (semana passada) ----------

(defn- registrar-presenca-de! [repo-s ente-id sessao-id vereadores fonte]
  (let [agora (Instant/now)]
    (repo-sessoes/registrar-presenca-lote! repo-s ente-id
      {:sessao-id sessao-id :agora agora
       :registros (mapv (fn [v] {:id (random-uuid) :vereador-id (:vereador-id v) :tipo "entrada"
                                  :modalidade "plenario" :fonte fonte :ocorrido-em agora :created-by nil})
                         vereadores)})))

(defn- semear-tribuna! [repo-s ente-id sessao-id orador-vereador-id encerrar?]
  (let [insc-id (random-uuid) fala-id (random-uuid) ator (random-uuid) inicio (Instant/now)]
    (repo-sessoes/inscrever! repo-s ente-id
      {:id insc-id :sessao-id sessao-id :vereador-id orador-vereador-id
       :origem-inscricao "pre_sessao_secretaria" :fase "grande_expediente" :created-by ator})
    (repo-sessoes/iniciar-fala! repo-s ente-id
      {:id fala-id :sessao-id sessao-id :inscricao-id insc-id :orador-id orador-vereador-id
       :tipo-fala "principal" :fase "grande_expediente" :iniciou-em inicio :created-by ator})
    (when encerrar?
      (repo-sessoes/encerrar-fala! repo-s ente-id
        {:id fala-id :encerrou-em (.plusSeconds inicio 180) :lock-version 0 :updated-by ator}))))

(defn- votar-e-apurar! [repo-l ente-id sessao-id materia-id eleitores base-membros]
  (let [vid (random-uuid)
        votos (cycle ["sim" "sim" "sim" "sim" "sim" "sim" "sim" "nao" "nao" "abstencao"])]
    (repo-leg/abrir-votacao! repo-l ente-id
      {:id vid :objeto-tipo "proposicao" :objeto-id materia-id :modalidade "nominal"
       :quorum-tipo "maioria_simples" :sessao-id sessao-id :created-by nil})
    (doseq [[v voto] (map vector eleitores votos)]
      (repo-leg/registrar-voto! repo-l ente-id
        {:id (random-uuid) :votacao-id vid :vereador-id (:vereador-id v) :voto voto :created-by nil}))
    (repo-leg/encerrar-votacao! repo-l ente-id {:id vid :base-membros base-membros :updated-by nil :lock-version 0})
    vid))

(defn- semear-encerrada!
  [repo-s repo-l repo-cad ente-id materias-votadas]
  (repo-sessoes/agendar-sessao! repo-s ente-id
    {:id id-encerrada :sessao-legislativa-id sessao-legislativa-id :tipo-sessao "ordinaria"
     :modalidade "presencial" :agendada-para (.minus (Instant/now) (Duration/ofDays 7)) :created-by nil})
  (repo-sessoes/transicionar-sessao! repo-s ente-id {:id id-encerrada :para "aberta" :updated-by nil :lock-version 0})
  (let [sessao (repo-sessoes/buscar-sessao repo-s ente-id id-encerrada)
        roster (roster-da-data repo-cad ente-id sessao)
        vigentes (filterv #(= "vigente" (:estado-mandato %)) roster)
        presentes (vec (take 14 vigentes))]
    (registrar-presenca-de! repo-s ente-id id-encerrada presentes "manual_secretaria")
    (repo-sessoes/registrar-chamada-conduzida! repo-s ente-id
      {:id (random-uuid) :sessao-id id-encerrada :conduzida-por (random-uuid) :roster roster
       :ocorrido-em (Instant/now) :agora (Instant/now) :created-by (random-uuid)})
    (let [base (membros-da-casa-agora repo-s ente-id id-encerrada roster)]
      (doseq [materia-id (take 2 materias-votadas)]
        (votar-e-apurar! repo-l ente-id id-encerrada materia-id (take 10 presentes) base)))
    (semear-tribuna! repo-s ente-id id-encerrada (:vereador-id (first presentes)) true))
  (repo-sessoes/transicionar-sessao! repo-s ente-id {:id id-encerrada :para "encerrada" :updated-by nil :lock-version 1}))

;; ---------- a ABERTA (hoje, o telao ao vivo) ----------

(defn- semear-aberta!
  [repo-s repo-l repo-cad ente-id materia-votando]
  (repo-sessoes/agendar-sessao! repo-s ente-id
    {:id id-aberta :sessao-legislativa-id sessao-legislativa-id :tipo-sessao "ordinaria"
     :modalidade "presencial" :agendada-para (Instant/now) :created-by nil})
  (repo-sessoes/transicionar-sessao! repo-s ente-id {:id id-aberta :para "aberta" :updated-by nil :lock-version 0})
  (let [sessao (repo-sessoes/buscar-sessao repo-s ente-id id-aberta)
        roster (roster-da-data repo-cad ente-id sessao)
        vigentes (filterv #(= "vigente" (:estado-mandato %)) roster)
        presentes (vec (take 12 vigentes))]
    (registrar-presenca-de! repo-s ente-id id-aberta presentes "painel_eletronico")
    ;; a votacao FICA aberta — e' o que a jornada J4 exige (o vereador vota ao vivo)
    (repo-leg/abrir-votacao! repo-l ente-id
      {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id materia-votando :modalidade "nominal"
       :quorum-tipo "maioria_simples" :sessao-id id-aberta :created-by nil})
    ;; 1 orador na tribuna com a fala INICIADA — cronometro correndo, sem encerrar
    (semear-tribuna! repo-s ente-id id-aberta (:vereador-id (first presentes)) false)))

;; ---------- a AGENDADA (semana que vem: pauta montada, ZERO presenca) ----------

(defn- semear-agendada!
  [repo-s repo-l ente-id]
  (repo-sessoes/agendar-sessao! repo-s ente-id
    {:id id-agendada :sessao-legislativa-id sessao-legislativa-id :tipo-sessao "ordinaria"
     :modalidade "presencial" :agendada-para (.plus (Instant/now) (Duration/ofDays 7)) :created-by nil})
  (doseq [materia-id (materias-aguardando-pauta repo-l ente-id)]
    (repo-sessoes/adicionar-item-na-sessao! repo-s ente-id
      {:id (random-uuid) :sessao-id id-agendada :fase "ordem_do_dia" :tipo-item "proposicao"
       :proposicao-id materia-id :created-by nil}))
  (repo-sessoes/adicionar-item-na-sessao! repo-s ente-id
    {:id (random-uuid) :sessao-id id-agendada :fase "expediente" :tipo-item "comunicado"
     :texto-descricao "Leitura do expediente recebido da Prefeitura Municipal de Fortaleza."
     :created-by nil}))

;; ---------- a funcao publica ----------

(defn semear!
  "Semeia (ou rele, se ja' semeada) as TRES SESSOES da Casa `ente`. `sistema` e' um sistema Component
  BOOTADO (mesmo contrato de `casa/semear!`/`acervo/semear!`) — usa `(:repo-sessoes sistema)` +
  `(:repo-legislativo sistema)` + `(:repo-cadastros sistema)`, mais o `:datasource` cru so' pra' o gate de
  `cadastros.sessao_legislativa` (sem fn exposta no modulo p/ isso — ver nota do ns).

  IDEMPOTENCIA: os 3 UUIDs das sessoes sao FIXOS (`id-encerrada`/`id-aberta`/`id-agendada`) — o gate e' a
  existencia da ENCERRADA. Se ja' existe, RELE (devolve so' os 3 ids, sem duplicar) em vez de re-tentar
  agendar (que colidiria na UNIQUE de numeracao) ou re-registrar presenca/votacao/tribuna sobre um estado
  que ja' avancou.

  Devolve `{:encerrada :aberta :agendada}` (os 3 UUIDs)."
  [sistema ente]
  (let [repo-s (:repo-sessoes sistema)
        repo-l (:repo-legislativo sistema)
        repo-cad (:repo-cadastros sistema)
        ds (get-in sistema [:datasource :ds])]
    (if (some? (repo-sessoes/buscar-sessao repo-s ente id-encerrada))
      {:encerrada id-encerrada :aberta id-aberta :agendada id-agendada}
      (do
        (garantir-sessao-legislativa! ds ente)
        (let [materias (materias-em-pauta repo-l ente)]
          (semear-encerrada! repo-s repo-l repo-cad ente (take 2 materias))
          (semear-aberta! repo-s repo-l repo-cad ente (nth materias 2))
          (semear-agendada! repo-s repo-l ente))
        {:encerrada id-encerrada :aberta id-aberta :agendada id-agendada}))))

;; ---------- leituras p/ o teste e p/ a Fase 1/2 do plano (sonda + caminhada) ----------

(defn buscar
  "A sessao inteira (inclusive `:estado`, do vocabulario real do CHECK — ver cabecalho do ns)."
  [sistema ente sessao-id]
  (repo-sessoes/buscar-sessao (:repo-sessoes sistema) ente sessao-id))

(defn quorum
  "O `:presentes-total` do quorum da sessao AGORA — via `logic/derivar-linhas-da-chamada` +
  `logic/contar-quorum` sobre `repo/chamada-da-sessao` (carry #6: nunca uma 2a aritmetica)."
  [sistema ente sessao-id]
  (let [repo-s (:repo-sessoes sistema)
        repo-cad (:repo-cadastros sistema)
        {:keys [sessao presencas justificativas]} (repo-sessoes/chamada-da-sessao repo-s ente sessao-id (Instant/now))
        roster (roster-da-data repo-cad ente sessao)
        linhas (mapv :linha (slogic/derivar-linhas-da-chamada roster presencas justificativas))]
    (:presentes-total (slogic/contar-quorum linhas))))

(defn presencas
  "Os eventos de presenca CRUS da sessao (auditoria) — usado pelo teste p/ provar que todo `:vereador-id`
  vem do roster real (a garantia central desta task)."
  [sistema ente sessao-id]
  (repo-sessoes/listar-presenca (:repo-sessoes sistema) ente sessao-id))

(defn itens-de-pauta
  [sistema ente sessao-id]
  (let [repo-s (:repo-sessoes sistema)
        pauta (repo-sessoes/buscar-pauta-por-sessao repo-s ente sessao-id)]
    (if pauta (count (repo-sessoes/listar-itens repo-s ente (:id pauta))) 0)))

(defn votos-apurados
  "Quantas votacoes ENCERRADAS (apuradas) esta sessao tem — leitura crua (ver nota do ns: `RepoLegislativo`
  nao expoe 'votacoes por sessao', so' por objeto polimorfico)."
  [sistema ente sessao-id]
  (let [ds (get-in sistema [:datasource :ds])]
    (tenancy/com-tenant* ds ente (fn [tx] (votos-apurados* tx ente sessao-id)))))

(defn votacao-aberta
  "A votacao 'aberta' desta sessao (ou nil) — o que a jornada J4 exige p/ o vereador votar ao vivo."
  [sistema ente sessao-id]
  (let [ds (get-in sistema [:datasource :ds])]
    (tenancy/com-tenant* ds ente (fn [tx] (votacao-aberta* tx ente sessao-id)))))
