(ns acervo
  "Semente do ACERVO LEGISLATIVO da demo (plano `docs/superpowers/plans/2026-09-07-prontidao-de-
  apresentacao.md`, Task 0.4) — sobre a Casa de `casa/semear!`: 24 proposicoes de autoria distribuida
  entre os 17 vereadores, cobrindo os 6 estados de um RITO REAL da Casa (`rito_ordinario`, nome
  apresentavel — NAO 'rito_fixture_portal' como `seed_demo.clj/materias`) + 3 pareceres (rascunho /
  aguardando assinatura do relator / emitido) + 2 autografos pos-aprovacao (1 aguardando o Executivo, 1
  sancionado) + 4 normas promulgadas e publicadas. Usa SO o Repo-Component REAL do legislativo
  (`RepoLegislativo`, ja' booted em `sistema` — `(:repo-legislativo sistema)`/`(:registro-fatos
  sistema)`), o MESMO motor declarativo compartilhado de tramitacao/pareceres (Disciplina 5, §22.4.3/
  §22.5.3) — nenhuma DSL nova.

  VOCABULARIO — lido da FONTE, nao de memoria (regra dura do briefing da Task 0.4):
  - `legislativo.proposicoes.estado` NAO TEM CHECK (migration 20260620000013-legislativo-
    proposicoes.up.sql:29, comentario 'coarse; a maquina fina e' a tramitacao (F3.3)'); os 6 estados
    usados aqui sao DADO, inseridos por este ns em `legislativo.template_estado` (Invariante 4 — regra
    de compliance/rito e' dado, nao codigo).
  - `tipo` — autoridade real `legislativo.logic/tipos` (src/oplenario/legislativo/logic.clj:13-16); as
    24 materias cobrem os 8 valores do vocabulario fechado da V1.
  - `legislativo.pareceres.estado` tambem e' template-driven, sem CHECK (migration 20260620000019-
    legislativo-pareceres.up.sql:32); os 4 UNICOS terminais fixos no trigger de imutabilidade sao
    'aprovado'/'rejeitado'/'prejudicado'/'prazo_vencido' (mesma migration:87, espelhados em
    `legislativo.logic/estados-parecer-terminais`) — o parecer 'emitido' deste ns termina em 'aprovado'
    (vocabulario real de dominio; 'emitido' e' so' o rotulo de UI da Task 0.4/J3).
  - `legislativo.norma.autografo_id` e' `NOT NULL` + `UNIQUE (ente_id, autografo_id)` (migration
    20260620000023-legislativo-norma.up.sql:24,49) — cada norma exige o SEU PROPRIO autografo
    sancionado. Os '2 autografos' do briefing sao os que ficam VISIVEIS na jornada pos-aprovacao SEM
    virar lei ainda (1 aguardando, 1 sancionado); as 4 normas nascem de 4 OUTROS autografos, cada um
    sancionado so' para satisfazer essa FK — nao contam contra os '2' do briefing, que descrevem o que
    a tela de pos-aprovacao mostra pendente."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.components.repositorio :as repo-cadastros]
            [oplenario.cadastros.db.vereador :as vereador]
            [oplenario.kernel.db-util :as comum]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.assinador-icp :as assinador-icp]
            [oplenario.legislativo.components.repositorio :as repo-leg])
  (:import (java.time LocalDate)))

;; ---------- constantes ----------

(def ^:private rito-chave "rito_ordinario")
(def ^:private parecer-chave "parecer_comissao_permanente")

(def ^:private hoje
  "Mesma vigencia-inicio de `casa/hoje` (LocalDate 2025-01-01, a posse da legislatura 2025-2028 — cobre
  qualquer data em que a demo rodar dentro dela). Duplicado aqui de proposito: `casa/hoje` e' privado ao
  ns `casa` (`^:private`), e este ns so' pode CRIAR `acervo.clj` (escopo da Task 0.4) — os dois leem o
  MESMO invariante (inicio da legislatura vigente), nao dois fatos diferentes por coincidencia."
  (LocalDate/of 2025 1 1))

;; ---------- o RITO ORDINARIO (sujeito 'proposicao', o default de `criar-template!`) ----------

(def ^:private estados-rito
  [{:chave "protocolada"      :nome "Protocolada"            :ordem 1 :terminal false}
   {:chave "em_comissoes"     :nome "Em Comissões"           :ordem 2 :terminal false}
   {:chave "aguardando_pauta" :nome "Aguardando Pauta"       :ordem 3 :terminal false}
   {:chave "em_pauta"         :nome "Em Pauta"               :ordem 4 :terminal false}
   {:chave "aprovada"         :nome "Aprovada"               :ordem 5 :terminal true}
   {:chave "arquivada"        :nome "Arquivada"              :ordem 6 :terminal true}])

(def ^:private transicoes-rito
  [{:de-estado "protocolada"      :para-estado "em_comissoes"     :gatilho "despachar"}
   {:de-estado "protocolada"      :para-estado "arquivada"        :gatilho "arquivar"}
   {:de-estado "em_comissoes"     :para-estado "aguardando_pauta" :gatilho "concluir_comissoes"}
   {:de-estado "aguardando_pauta" :para-estado "em_pauta"         :gatilho "incluir_pauta"}
   {:de-estado "em_pauta"         :para-estado "aprovada"         :gatilho "aprovar"}
   {:de-estado "em_pauta"         :para-estado "arquivada"        :gatilho "rejeitar"}])

;; ---------- as 24 proposicoes — ementas plausiveis de camara municipal (nada de "teste 1"/"foo") ----------
;; :ref identifica o item p/ pareceres/autografos/normas irem buscar um :id especifico depois de protocolar.
;; :caminho = os gatilhos disparados em sequencia por `transicionar!`, a partir de 'protocolada'.

(def ^:private materias
  [;; ---- protocolada (4) ----
   {:ref :protocolada-1 :tipo "projeto_lei"
    :ementa "Institui o Programa Municipal de Hortas Comunitárias e dá outras providências."
    :caminho []}
   {:ref :protocolada-2 :tipo "projeto_lei"
    :ementa "Dispõe sobre a obrigatoriedade de instalação de bebedouros em praças públicas municipais."
    :caminho []}
   {:ref :protocolada-3 :tipo "indicacao"
    :ementa "Indica ao Executivo a instalação de iluminação pública na Praça da Gentilândia."
    :objeto-indicacao "Instalação de iluminação pública na Praça da Gentilândia" :caminho []}
   {:ref :protocolada-4 :tipo "requerimento"
    :ementa "Requer informações ao Executivo sobre o andamento das obras do Parque Linear do Rio Cocó."
    :tipo-requerimento "informacao" :caminho []}

   ;; ---- em_comissoes (4) ----
   {:ref :em-comissoes-1 :tipo "projeto_lei"
    :ementa "Dispõe sobre a criação do Conselho Municipal de Mobilidade Urbana."
    :caminho ["despachar"]}
   {:ref :em-comissoes-2 :tipo "projeto_lei"
    :ementa "Autoriza o Executivo a firmar convênio com entidades de assistência social do Município."
    :caminho ["despachar"]}
   {:ref :em-comissoes-3 :tipo "projeto_lei_complementar"
    :ementa "Altera o Código de Posturas do Município quanto ao horário de funcionamento do comércio."
    :caminho ["despachar"]}
   {:ref :em-comissoes-4 :tipo "mocao"
    :ementa "Manifesta congratulações à comunidade escolar pela conquista na Olimpíada Municipal de Matemática."
    :categoria-mocao "congratulacoes" :caminho ["despachar"]}

   ;; ---- aguardando_pauta (4) ----
   {:ref :aguardando-pauta-1 :tipo "projeto_lei"
    :ementa "Institui a Semana Municipal de Combate ao Trabalho Infantil."
    :caminho ["despachar" "concluir_comissoes"]}
   {:ref :aguardando-pauta-2 :tipo "projeto_lei"
    :ementa "Dispõe sobre a coleta seletiva de resíduos sólidos na Zona Leste do Município."
    :caminho ["despachar" "concluir_comissoes"]}
   {:ref :aguardando-pauta-3 :tipo "projeto_resolucao"
    :ementa "Concede título de utilidade pública à Associação Comunitária do Bairro Parangaba."
    :caminho ["despachar" "concluir_comissoes"]}
   {:ref :aguardando-pauta-4 :tipo "projeto_decreto_legislativo"
    :ementa "Concede Diploma de Honra ao Mérito a profissionais da Educação Municipal."
    :caminho ["despachar" "concluir_comissoes"]}

   ;; ---- em_pauta (3) ----
   {:ref :em-pauta-1 :tipo "projeto_lei"
    :ementa "Institui o Programa Municipal de Arborização Urbana."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta"]}
   {:ref :em-pauta-2 :tipo "projeto_lei"
    :ementa "Dispõe sobre a criação de vagas de estacionamento para idosos em logradouros públicos."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta"]}
   {:ref :em-pauta-3 :tipo "proposta_emenda_lom"
    :ementa "Altera a Lei Orgânica do Município quanto à composição da Mesa Diretora."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta"]}

   ;; ---- aprovada (6, todas projeto_lei — alimentam os autografos/normas abaixo) ----
   {:ref :aprovada-1 :tipo "projeto_lei"
    :ementa "Institui o Código Municipal de Defesa do Consumidor."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta" "aprovar"]}
   {:ref :aprovada-2 :tipo "projeto_lei"
    :ementa "Cria o Programa Municipal de Incentivo à Leitura nas Escolas Públicas."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta" "aprovar"]}
   {:ref :aprovada-3 :tipo "projeto_lei"
    :ementa "Institui multa para o descarte irregular de resíduos da construção civil."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta" "aprovar"]}
   {:ref :aprovada-4 :tipo "projeto_lei"
    :ementa "Autoriza a cessão de uso de imóvel público a entidade privada sem fins lucrativos."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta" "aprovar"]}
   {:ref :aprovada-5 :tipo "projeto_lei"
    :ementa "Institui o Programa Municipal de Combate ao Desperdício de Alimentos."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta" "aprovar"]}
   {:ref :aprovada-6 :tipo "projeto_lei"
    :ementa "Dispõe sobre a acessibilidade em prédios públicos municipais."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta" "aprovar"]}

   ;; ---- arquivada (3) ----
   {:ref :arquivada-1 :tipo "mocao"
    :ementa "Manifesta repúdio a atos de violência contra profissionais da imprensa local."
    :categoria-mocao "repudio" :caminho ["arquivar"]}
   {:ref :arquivada-2 :tipo "requerimento"
    :ementa "Requer voto de pesar pelo falecimento do ex-vereador Antônio Bezerra."
    :tipo-requerimento "voto_pesar" :caminho ["arquivar"]}
   {:ref :arquivada-3 :tipo "projeto_lei"
    :ementa "Dispõe sobre a redução da jornada de trabalho dos servidores da Guarda Municipal Metropolitana."
    :caminho ["despachar" "concluir_comissoes" "incluir_pauta" "rejeitar"]}])

;; ---------- leituras cruas (nenhuma fn exposta no Repo/db do modulo p/ isto; adicionar uma so' pra este
;;            script ficaria fora do escopo da Task 0.4, que e' CRIAR SO acervo.clj) ----------

(defn- template-do-rito
  "O `id` do template `rito-chave` v1 deste ente, se ja' existir — o GATE de idempotencia (mesmo padrao
  de `casa/ja-semeada?`, carry #6 do briefing: os `db/` de proposicao/template NAO tem ON CONFLICT)."
  [tx ente]
  (:id (comum/linha->kebab
        (jdbc/execute-one! tx
          (sql/format {:select [:id] :from [:legislativo.template_tramitacao]
                       :where [:and [:= :ente_id ente] [:= :chave rito-chave] [:= :versao 1]]})))))

(defn estados-do-template
  "As `chave` que `template-id` DECLARA em `legislativo.template_estado` — leitura direta (sem fn
  exposta no Repo/db do modulo p/ listar estados de um template; ver nota da ns acima)."
  [sistema ente template-id]
  (let [ds (get-in sistema [:datasource :ds])]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (set (map :chave
                  (comum/linhas->kebab
                   (jdbc/execute! tx
                     (sql/format {:select [:chave] :from [:legislativo.template_estado]
                                  :where [:and [:= :ente_id ente] [:= :template_id template-id]]})))))))))

(defn tipos-usados
  "Os `tipo` distintos das proposicoes do ente — via `listar-e-contar-proposicoes` (a MESMA leitura
  paginada que a lista real do FE usa), dentro da API do Repo (§22.10), nao um SELECT cru."
  [sistema ente]
  (->> (repo-leg/listar-e-contar-proposicoes (:repo-legislativo sistema) ente {:pagina 1 :tamanho 200})
       :itens (map :tipo) set))

(defn contar-por-estado
  "{estado -> quantidade de proposicoes} do ente — MESMA leitura de `tipos-usados`."
  [sistema ente]
  (->> (repo-leg/listar-e-contar-proposicoes (:repo-legislativo sistema) ente {:pagina 1 :tamanho 200})
       :itens (map :estado) frequencies))

(defn- comissoes-permanentes-do-ente
  "As comissoes permanentes REAIS da Casa (CCJ, Financas, Obras — criadas por `casa.clj`), tipo!='mesa'.
  Leitura crua cross-schema (mesmo racional das leituras acima; `sessoes.clj` ja cruza pra
  `cadastros.sessao_legislativa` do mesmo jeito) — ledger #11 (docs/16-ledger-prontidao.md):
  `semear-pareceres!` gravava `comissao-id` como `(random-uuid)`, guard ref ORFAO (sem FK, §22.10),
  e a tela `/parecer/:id` mostrava esse UUID cru onde deveria ir o nome da comissao."
  [tx ente]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:id :nome] :from [:cadastros.comissao]
                   :where [:and [:= :ente_id ente] [:= :tipo "permanente"]]
                   :order-by [[:nome :asc]]}))))

(defn comissoes
  "As comissoes permanentes reais da Casa — usado pelo teste da Task 0.4 (ledger #11) p/ provar que
  todo `comissao-id` de parecer aponta pra uma comissao que EXISTE."
  [sistema ente]
  (let [ds (get-in sistema [:datasource :ds])]
    (tenancy/com-tenant* ds ente (fn [tx] (comissoes-permanentes-do-ente tx ente)))))

(defn pareceres
  "Leitura crua de auditoria dos pareceres deste ente (id/comissao-id/estado/relator-id) — usado pelo
  teste da Task 0.4 (ledger #11/#12); nenhuma fn exposta no Repo/db do modulo p/ 'listar pareceres do
  ente' sem filtrar por objeto/relator (ver nota da ns acima)."
  [sistema ente]
  (let [ds (get-in sistema [:datasource :ds])]
    (tenancy/com-tenant* ds ente
      (fn [tx]
        (comum/linhas->kebab
          (jdbc/execute! tx
            (sql/format {:select [:id :comissao_id :estado :relator_id] :from [:legislativo.pareceres]
                         :where [:= :ente_id ente]})))))))

;; ---------- o rito + as 24 proposicoes ----------

(defn- criar-rito!
  "Persiste o template `rito-chave` (sujeito 'proposicao', o default) + os 6 estados + as 6 transicoes —
  TUDO guard nil (sempre permite; nenhuma regra condicional e' necessaria p/ o roteiro da demo)."
  [repo ente]
  (let [tid (random-uuid)]
    (repo-leg/criar-template! repo ente
      {:id tid :chave rito-chave :versao 1 :nome "Rito Ordinário de Tramitação" :estado-inicial "protocolada"})
    (doseq [e estados-rito]
      (repo-leg/criar-estado! repo ente (assoc e :id (random-uuid) :template-id tid)))
    (doseq [t transicoes-rito]
      (repo-leg/criar-transicao! repo ente (assoc t :id (random-uuid) :template-id tid)))
    tid))

(defn- protocolar-e-tramitar!
  "Protocola 1 materia (autor = vereador `idx` do roster, round-robin) e percorre `:caminho` via o
  ENGINE real (`transicionar!`, Disciplina 5) — nunca `mudar-estado-proposicao!` (bypassaria o motor).
  Devolve o `id` da proposicao. Falha alto se algum gatilho do caminho NAO transicionar (guard bloqueado
  ou rito mal-formado — bug deste ns, nao dado esperado)."
  [repo registro ente template-id vereadores idx
   {:keys [tipo ementa caminho objeto-indicacao tipo-requerimento categoria-mocao]}]
  (let [autor (nth vereadores (mod idx (count vereadores)))
        {pid :id} (repo-leg/protocolar! repo ente
                    {:id (random-uuid) :ente-id ente :tipo tipo :ano 2026 :uf "CE" :municipio-nome "Fortaleza"
                     :ementa ementa :autor-tipo "vereador" :autor-id (:id autor) :autor-texto (:nome-parlamentar autor)
                     :objeto-indicacao objeto-indicacao :tipo-requerimento tipo-requerimento
                     :categoria-mocao categoria-mocao})]
    (doseq [gatilho caminho]
      (let [r (repo-leg/transicionar! repo ente registro
                {:proposicao-id pid :template-id template-id :gatilho gatilho})]
        (when-not (:transicionou? r)
          (throw (ex-info "acervo/semear!: gatilho do caminho nao transicionou (guard bloqueado ou rito mal-formado)"
                          {:proposicao-id pid :gatilho gatilho :de (:de r)})))))
    pid))

;; ---------- o template de PARECER (sujeito 'parecer') + os 3 pareceres ----------

(defn- criar-template-parecer!
  "'em_elaboracao' (inicial) -[concluir_relatoria]-> 'aguardando_assinatura' -[emitir]-> 'aprovado'
  (terminal, vocabulario real de `legislativo.logic/estados-parecer-terminais`)."
  [repo ente]
  (let [tid (random-uuid)]
    (repo-leg/criar-template! repo ente
      {:id tid :chave parecer-chave :versao 1 :sujeito "parecer"
       :nome "Parecer de Comissão Permanente" :estado-inicial "em_elaboracao"})
    (repo-leg/criar-estado! repo ente {:id (random-uuid) :template-id tid :chave "em_elaboracao"
                                       :nome "Em Elaboração" :ordem 1 :terminal false})
    (repo-leg/criar-estado! repo ente {:id (random-uuid) :template-id tid :chave "aguardando_assinatura"
                                       :nome "Aguardando Assinatura do Relator" :ordem 2 :terminal false})
    (repo-leg/criar-estado! repo ente {:id (random-uuid) :template-id tid :chave "aprovado"
                                       :nome "Aprovado" :ordem 3 :terminal true})
    (repo-leg/criar-transicao! repo ente {:id (random-uuid) :template-id tid :de-estado "em_elaboracao"
                                          :para-estado "aguardando_assinatura" :gatilho "concluir_relatoria"})
    (repo-leg/criar-transicao! repo ente {:id (random-uuid) :template-id tid :de-estado "aguardando_assinatura"
                                          :para-estado "aprovado" :gatilho "emitir"})
    tid))

(defn- texto-parecer [assunto]
  (str "## Relatório\n\n" assunto "\n\n## Análise\n\nA proposição atende aos requisitos formais e "
       "materiais de admissibilidade regimental, nos termos do Regimento Interno desta Casa."))

(defn- semear-pareceres!
  "3 pareceres — A: rascunho (relator designado, texto em elaboração, SEM transicionar). B: aguardando
  assinatura do relator (relatoria concluída, ainda não emitido). C: emitido — engine leva a 'aprovado'
  (o vocabulario real; 'emitido' e' rotulo de UI), com assinatura (Onda C Slice C4, `assinador-icp`).

  CORRIGIDO (ledger #12, docs/16-ledger-prontidao.md): a 1a redacao designava relator1/2/3 pelos 3
  PRIMEIROS do roster (`vereadores`, ordem de `vereador/listar`), sem vinculo com identidade nenhuma
  — a identidade `:vereador` da demo NUNCA era relatora, e GET /parecer/:id/assinar respondia 404 pro
  login vereador (a jornada J3 morria). `relator-vereador-id` (o vereador ligado a' identidade
  `:vereador`, resolvido em `semear!`) agora e' o relator do parecer B — o UNICO dos 3 num estado
  NAO-terminal ('aguardando_assinatura'): A e' rascunho (ainda sem relatoria concluida) e C ja'
  termina em 'aprovado' (§22.4 eixo F, `legislativo.logic/estados-parecer-terminais` — um parecer
  terminal nao pode mais ser assinado, migration 20260620000019-legislativo-pareceres.up.sql:87).

  CORRIGIDO TAMBEM (ledger #11): `comissao-id` era `(random-uuid)` — guard ref ORFAO, sem FK
  (§22.10) — e a tela `/parecer/:id` mostrava esse UUID cru onde deveria ir o nome da comissao.
  `comissoes` (as 3 comissoes permanentes REAIS da Casa, de `comissoes-permanentes-do-ente`) agora
  fornece o `comissao-id` de cada parecer, um por comissao (A: CCJ, B: Financas, C: Obras — a ORDEM
  de `comissoes` e' por nome, ver `comissoes-permanentes-do-ente`)."
  [repo registro ente template-id vereadores relator-vereador-id comissoes por-ref]
  (let [relator1 (:id (nth vereadores 0)) relator2 relator-vereador-id relator3 (:id (nth vereadores 2))
        comissao1 (:id (nth comissoes 0)) comissao2 (:id (nth comissoes 1)) comissao3 (:id (nth comissoes 2))]
    ;; A — rascunho
    (let [{pcid :id} (repo-leg/iniciar-parecer! repo ente
                       {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id (get por-ref :em-comissoes-1)
                        :comissao-id comissao1 :template-id template-id})]
      (repo-leg/designar-relator! repo ente {:id pcid :relator-id relator1 :updated-by nil :lock-version 0})
      (repo-leg/nova-versao-parecer! repo ente
        {:id (random-uuid) :parecer-id pcid
         :texto-inline (texto-parecer "Trata-se de projeto de lei que dispõe sobre a criação do Conselho Municipal de Mobilidade Urbana.")
         :origem-versao "redacao" :formato "markdown"}))
    ;; B — aguardando assinatura do relator (relator = o vereador da identidade ':vereador' — ledger #12)
    (let [{pcid :id} (repo-leg/iniciar-parecer! repo ente
                       {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id (get por-ref :aguardando-pauta-1)
                        :comissao-id comissao2 :template-id template-id})]
      (repo-leg/designar-relator! repo ente {:id pcid :relator-id relator2 :updated-by nil :lock-version 0})
      (repo-leg/nova-versao-parecer! repo ente
        {:id (random-uuid) :parecer-id pcid
         :texto-inline (texto-parecer "Trata-se de projeto de lei que institui a Semana Municipal de Combate ao Trabalho Infantil.")
         :origem-versao "redacao" :formato "markdown"})
      (repo-leg/transicionar-parecer! repo ente registro
        {:parecer-id pcid :template-id template-id :gatilho "concluir_relatoria" :updated-by nil :contexto {}}))
    ;; C — emitido (aprovado), assinado
    (let [{pcid :id} (repo-leg/iniciar-parecer! repo ente
                       {:id (random-uuid) :objeto-tipo "proposicao" :objeto-id (get por-ref :em-pauta-1)
                        :comissao-id comissao3 :template-id template-id})]
      (repo-leg/designar-relator! repo ente {:id pcid :relator-id relator3 :updated-by nil :lock-version 0})
      (repo-leg/nova-versao-parecer! repo ente
        {:id (random-uuid) :parecer-id pcid
         :texto-inline (texto-parecer "Trata-se de projeto de lei que institui o Programa Municipal de Arborização Urbana.")
         :origem-versao "redacao" :formato "markdown"})
      (repo-leg/transicionar-parecer! repo ente registro
        {:parecer-id pcid :template-id template-id :gatilho "concluir_relatoria" :updated-by nil :contexto {}})
      ;; lock-version FRESCO (nao hardcoded): le' o que o CAS acumulado ate' aqui realmente deixou.
      (let [lv (:lock-version (repo-leg/buscar-parecer repo ente pcid))]
        (repo-leg/emitir-parecer! repo ente registro
          {:parecer-id pcid :template-id template-id :gatilho "emitir" :voto-relator "favoravel"
           :updated-by nil :agora hoje :contexto {} :lock-version lv
           :assinador (assinador-icp/assinador-stub)})))))

;; ---------- pos-aprovacao: 2 autografos visiveis (1 aguardando, 1 sancionado) + 4 normas ----------

(defn- semear-pos-aprovacao!
  [repo ente por-ref]
  (let [{aid-a :id} (repo-leg/gerar-autografo! repo ente
                      {:id (random-uuid) :proposicao-id (get por-ref :aprovada-1) :ano 2026
                       :texto-versao-id (random-uuid) :destinatario-texto "Prefeito Municipal de Fortaleza"})]
    (repo-leg/iniciar-tramitacao-executiva! repo ente {:id (random-uuid) :autografo-id aid-a}))
  (let [{aid-b :id} (repo-leg/gerar-autografo! repo ente
                      {:id (random-uuid) :proposicao-id (get por-ref :aprovada-2) :ano 2026
                       :texto-versao-id (random-uuid) :destinatario-texto "Prefeito Municipal de Fortaleza"})
        {tid-b :id} (repo-leg/iniciar-tramitacao-executiva! repo ente {:id (random-uuid) :autografo-id aid-b})]
    (repo-leg/registrar-resposta-executivo! repo ente {:id tid-b :resultado "sancionado" :updated-by nil :lock-version 0})))

(defn- promulgar-e-publicar!
  "1 autografo PROPRIO (sancionado, so' pra satisfazer o NOT NULL de `legislativo.norma.autografo_id`) +
  promulga + publica. Devolve o `id` da norma."
  [repo ente pid ementa data-promulgacao]
  (let [{aid :id} (repo-leg/gerar-autografo! repo ente
                    {:id (random-uuid) :proposicao-id pid :ano 2026 :texto-versao-id (random-uuid)
                     :destinatario-texto "Prefeito Municipal de Fortaleza"})
        {tid :id} (repo-leg/iniciar-tramitacao-executiva! repo ente {:id (random-uuid) :autografo-id aid})]
    (repo-leg/registrar-resposta-executivo! repo ente {:id tid :resultado "sancionado" :updated-by nil :lock-version 0})
    (let [{nid :id} (repo-leg/promulgar-norma! repo ente
                      {:id (random-uuid) :proposicao-id pid :autografo-id aid :tipo-norma "lei" :ano 2026
                       :uf "CE" :municipio-nome "Fortaleza" :data-promulgacao data-promulgacao
                       :ementa ementa :texto-versao-id (random-uuid)})]
      (repo-leg/publicar-norma! repo ente
        {:id nid :veiculo-publicacao "Diário Oficial do Município de Fortaleza" :updated-by nil :lock-version 0})
      nid)))

(defn- semear-normas!
  "4 normas promulgadas e publicadas — 1 por cada uma das 4 proposicoes 'aprovada' restantes (as 2
  primeiras alimentaram os autografos standalone de `semear-pos-aprovacao!`)."
  [repo ente por-ref materias-por-ref]
  (mapv (fn [ref data]
          (promulgar-e-publicar! repo ente (get por-ref ref) (:ementa (get materias-por-ref ref)) data))
        [:aprovada-3 :aprovada-4 :aprovada-5 :aprovada-6]
        [(LocalDate/of 2026 3 10) (LocalDate/of 2026 4 22) (LocalDate/of 2026 6 5) (LocalDate/of 2026 7 18)]))

;; ---------- a funcao publica ----------

(defn semear!
  "Semeia (ou rele, se ja' semeada) o ACERVO LEGISLATIVO da Casa `ente`. `sistema` e' um sistema
  Component BOOTADO (mesmo contrato de `casa/semear!`) — usa `(:repo-legislativo sistema)` +
  `(:registro-fatos sistema)` (ja' `using`-ados com :datasource/:bus, nada a fiar aqui) + o `:datasource`
  cru so' pra' as 2 leituras que nao tem fn exposta no modulo (`template-do-rito`/`estados-do-template`).

  `identidade-vereador` e' o `:vereador` de `(:identidades (casa/semear! sistema))` — o UUID de
  IDENTIDADE (nao de vereador) do login usado na jornada J3. Resolvido aqui pro vereador-id real via
  `RepoCadastros/vereador-por-identidade` (mesmo seam de `rotas.clj:37`) — ledger #12
  (docs/16-ledger-prontidao.md): sem isso nenhum parecer tinha esse vereador como relator, e
  GET /parecer/:id/assinar respondia 404 pro login vereador.

  IDEMPOTENCIA (mesmo padrao de `casa/ja-semeada?`, carry #6 do briefing — os `db/` de proposicao/
  template/parecer/autografo/norma NAO tem ON CONFLICT): o gate e' a existencia do template
  `rito-chave` v1 neste ente. Se ja' existe, RELE (devolve so' `:template-id`, sem duplicar as 24
  proposicoes) em vez de tentar recriar — chamar de novo NAO cria um segundo acervo.

  Devolve `{:template-id}`."
  [sistema ente identidade-vereador]
  (let [repo (:repo-legislativo sistema)
        registro (:registro-fatos sistema)
        repo-cad (:repo-cadastros sistema)
        ds (get-in sistema [:datasource :ds])
        existente (tenancy/com-tenant* ds ente (fn [tx] (template-do-rito tx ente)))]
    (if existente
      {:template-id existente}
      (let [vereadores (tenancy/com-tenant* ds ente (fn [tx] (vereador/listar tx ente hoje)))
            relator-vereador-id (:id (repo-cadastros/vereador-por-identidade repo-cad ente identidade-vereador))
            comissoes-reais (tenancy/com-tenant* ds ente (fn [tx] (comissoes-permanentes-do-ente tx ente)))
            _ (when (< (count comissoes-reais) 3)
                (throw (ex-info (str "acervo/semear!: precisa de >=3 comissoes permanentes da Casa — "
                                     "rode casa/semear! primeiro")
                                {:encontradas (count comissoes-reais)})))
            template-id (criar-rito! repo ente)
            por-ref (into {}
                      (map-indexed
                        (fn [i m] [(:ref m) (protocolar-e-tramitar! repo registro ente template-id vereadores i m)])
                        materias))
            materias-por-ref (into {} (map (juxt :ref identity) materias))
            template-parecer-id (criar-template-parecer! repo ente)]
        (semear-pareceres! repo registro ente template-parecer-id vereadores relator-vereador-id comissoes-reais por-ref)
        (semear-pos-aprovacao! repo ente por-ref)
        (semear-normas! repo ente por-ref materias-por-ref)
        {:template-id template-id}))))
