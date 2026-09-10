(ns oplenario.legislativo.db.votacao
  "Persistencia da votacao (eixo G) — funcoes sobre a `tx` do tenant (RLS isola). HoneySQL schema-qualified;
  ente_id em toda query. `abrir!` cria a votacao 'aberta'; `registrar-voto!`/`registrar-voto-secreto!` sao
  APPEND-ONLY (o trigger congela; correcao = nova votacao); `encerrar!` apura (votos OU votos_secretos
  conforme a modalidade), computa o resultado pela aritmetica EXATA do quorum (logic) e grava o snapshot
  (CAS por lock_version). `anular!` leva a 'anulada' (terminal) — usado na correcao (nova votacao aponta a
  corrigida via votacao_corrige_id)."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.db.texto-versao :as texto]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private colunas
  [:id :ente_id :objeto_tipo :objeto_id :modalidade :quorum_tipo :estado :resultado
   :total_sim :total_nao :total_abstencao :base_membros :votacao_corrige_id
   :sessao_id :pauta_item_id :texto_versao_id :lock_version])

(defn abrir!
  "Abre uma votacao (estado 'aberta') sobre o objeto polimorfico (objeto-tipo,objeto-id). `sessao-id` +
  `pauta-item-id` (ambos forward-ref a sessoes, §22.10) sao CONTEXTO TEMPORAL — a votacao e' sobre a
  MATERIA (objeto), nao sobre o item (§22.6 eixo B). RETURNING `lock_version` (ledger de prontidao Fase
  8 achado #2): esta e' a UNICA leitura que existe da votacao recem-aberta — nao ha' rota GET de
  detalhe — entao o recibo de abertura tem de carregar o token de CAS que `POST .../encerramento`
  exige, ou o encerramento fica impossivel de montar so' pela API. Devolve {:id :lock-version}."
  [tx {:keys [id ente-id objeto-tipo objeto-id modalidade quorum-tipo votacao-corrige-id
              sessao-id pauta-item-id created-by]}]
  (let [;; T3-A2 (mig 0075) — CONGELA a versao de texto posta em deliberacao. Resolvida AQUI, server-side,
        ;; dentro da mesma tx do INSERT: nunca vem do corpo do request (mesma disciplina de
        ;; `destinatario-texto` do autografo) e nao ha' janela entre resolver e gravar. O instante certo e' a
        ;; ABERTURA, nao o encerramento: o texto sobre o qual o plenario delibera e' o que esta' na mesa
        ;; quando a votacao abre. So' faz sentido p/ 'proposicao' — o objeto e' POLIMORFICO, e emenda/parecer/
        ;; requerimento nao tem versao de texto de proposicao; nesses casos fica nil, e quem consome falha
        ;; fechada (ver aprovada-em-votacao?/aprovacao-vigente).
        texto-versao-id (when (= "proposicao" objeto-tipo)
                          (:id (texto/vigente tx ente-id objeto-id)))
        r (comum/linha->kebab
           (jdbc/execute-one! tx
             (sql/format {:insert-into :legislativo.votacoes
                          :values [{:id id :ente_id ente-id :objeto_tipo objeto-tipo :objeto_id objeto-id
                                    :modalidade modalidade :quorum_tipo quorum-tipo :estado "aberta"
                                    :votacao_corrige_id votacao-corrige-id :sessao_id sessao-id
                                    :pauta_item_id pauta-item-id :texto_versao_id texto-versao-id
                                    :created_by created-by :efetivado_em [:now]}]
                          :returning [:lock_version]})))]
    {:id id :lock-version (:lock-version r)}))

(defn registrar-voto!
  "Registra um voto NOMINAL (atribuido). Append-only; a UNIQUE (ente_id,votacao_id,vereador_id) barra voto
  duplo do mesmo vereador. RETURNING `registrado_em` (Onda E fatia 2 carry, mesmo racional de
  tramitacao/registrar-transicao!): o carimbo REAL do voto, devolvido p/ o Repo incluir no evento de dominio
  (voto.registrado :ocorrido-em) — a jusante, o perfil publico do vereador em `transparencia` usa isto em vez
  do momento em que o consumer PROJETA. Devolve {:id :ocorrido-em}."
  [tx {:keys [id ente-id votacao-id vereador-id voto created-by]}]
  (let [r (comum/linha->kebab
           (jdbc/execute-one! tx
             (sql/format {:insert-into :legislativo.votos
                          :values [{:id id :ente_id ente-id :votacao_id votacao-id :vereador_id vereador-id
                                    :voto voto :created_by created-by :efetivado_em [:now]}]
                          :returning [:registrado_em]})))]
    {:id id :ocorrido-em (:registrado-em r)}))

(defn registrar-voto-secreto!
  "Registra um voto SECRETO (anonimo — a tabela nao tem vereador_id/created_by). Append-only. Devolve {:id}."
  [tx {:keys [id ente-id votacao-id voto]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.votos_secretos
                 :values [{:id id :ente_id ente-id :votacao_id votacao-id :voto voto :efetivado_em [:now]}]}))
  {:id id})

(defn buscar [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.votacoes]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn aprovacao-vigente
  "T3-A2 — a votacao que APROVOU `proposicao-id`, ou nil. Devolve {:votacao-id :texto-versao-id}; o
  `:texto-versao-id` e' a versao que estava na mesa quando a votacao ABRIU (congelada por `abrir!`, mig
  0075) e pode ser nil (votacao anterior a' migration, ou materia que foi a plenario sem texto vigente).

  E' desta fn que sai `aprovada-em-votacao?` — mesma consulta, mesmas exclusoes, uma so' fonte de verdade.
  A separacao existe porque as duas perguntas do sistema sao distintas e nao devem colapsar:
  'a Casa aprovou?' (o read-model, que gateia botao) e 'entao QUAL texto ela aprovou?' (o autografo, que
  precisa do conteudo). Quem precisa do conteudo FALHA FECHADA quando `:texto-versao-id` e' nil — nao se
  emite ato juridico sem saber o que foi deliberado —, enquanto o read-model segue dizendo a verdade: a
  materia foi mesmo aprovada."
  [tx ente-id proposicao-id]
  (some-> (jdbc/execute-one! tx
           (sql/format {:select [[:v.id :votacao_id] :v.texto_versao_id] :from [[:legislativo.votacoes :v]]
                        :where [:and [:= :v.ente_id ente-id]
                                     [:= :v.objeto_tipo "proposicao"]
                                     [:= :v.objeto_id proposicao-id]
                                     [:= :v.estado "encerrada"]
                                     [:= :v.resultado "aprovada"]
                                     [:not [:exists {:select [[[:inline 1]]]
                                                     :from [[:legislativo.votacoes :c]]
                                                     :where [:and [:= :c.ente_id ente-id]
                                                                  [:= :c.votacao_corrige_id :v.id]]}]]]
                        :limit 1}))
          comum/linha->kebab))

(defn aprovada-em-votacao?
  "T3-A (guarda-autografo-votacao) — a proposicao `proposicao-id` foi APROVADA pela Casa? Devolve booleano.

  A pergunta que o autografo (artefato legal, numeracao gapless) tem de fazer NAO e' sobre o rotulo
  `proposicoes.estado`: aquilo e' texto livre (sem CHECK, default 'protocolada'), e' chave de estado de
  TEMPLATE — config do tenant, nao vocabulario de sistema (Inv.4) — e nenhuma rota HTTP o move (o unico
  chamador de `db/tramitacao.clj/transicionar!` e' a semente da demo). A pergunta e' sobre o ATO: existe
  votacao ENCERRADA sobre esta proposicao cujo resultado foi 'aprovada'.

  Tres exclusoes, todas deliberadas:
  - `estado = 'encerrada'` deixa de fora a votacao 'aberta' (ainda apurando) e a 'anulada' (terminal por
    correcao — migration 0021 L8: correcao de voto nunca e' UPDATE, anula-se e abre-se outra).
  - `NOT EXISTS (... votacao_corrige_id = v.id)` deixa de fora a votacao que uma OUTRA veio corrigir. A
    anulacao e a abertura da corretiva sao dois atos distintos; entre um e outro a corrigida ainda esta'
    'encerrada' e sozinha ela mentiria.
  - `objeto_tipo = 'proposicao'` amarra ao objeto certo: `objeto_id` e' polimorfico (emenda/parecer/
    requerimento/redacao_final compartilham a coluna) e sem isso uma emenda aprovada de id colidente
    responderia pela materia-mae.

  LIMITES CONHECIDOS, todos declarados (a lista cresceu com a revisao adversarial ecc — o que este
  predicado NAO responde e' tao importante quanto o que responde):
  - 'houve UMA aprovacao', nao 'o rito se completou': dois turnos e redacao final passam com um turno so'.
  - votacao 'simbolica' (aclamacao) tem o `resultado` vindo do CORPO do request (ver `encerrar!` abaixo) —
    entao uma aprovacao com ZERO votos registrados satisfaz este predicado. Idem 'nominal' com um voto so'
    em maioria_simples, e com `base-membros` do corpo (carry sec MEDIUM-1).
  - a votacao pode ter sido aberta e encerrada numa sessao 'agendada' que nunca se realizou:
    `estados-sessao-fechada` e' so' #{encerrada nao_realizada arquivada}.
  - `objeto_tipo='redacao_final'` aprovada NAO conta (conservador de proposito, mas nao e' obvio).
  - (RESOLVIDO em T3-A2, mig 0075) qual TEXTO foi aprovado deixou de ser incognita: `abrir!` congela a
    versao posta em deliberacao, e quem precisa do conteudo usa `aprovacao-vigente` logo acima.
  E' [GAP] regimental (mesmo bolso de admissibilidade-de-emenda-de-plenario), e a forma aqui aceita o
  refino sem refactor — o predicado ganha criterio, os chamadores nao mudam."
  [tx ente-id proposicao-id]
  (some? (aprovacao-vigente tx ente-id proposicao-id)))

(defn buscar-com-lock
  "Como `buscar`, mas sob `SELECT ... FOR UPDATE` — serializa contra `encerrar!` (que tambem toma o lock via
  `votacao+lock`). Onda C3 (`registrar-meu-voto!`): fecha a janela de corrida entre AUTORIZAR um voto
  self-service e a Mesa encerrar a MESMA votacao no meio do caminho — so' usar dentro de uma tx que em
  seguida ESCREVE com base neste snapshot (nunca uma leitura solta)."
  [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select colunas :from [:legislativo.votacoes]
                  :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))))

(defn votos-da-votacao
  "Os votos NOMINAIS (atribuidos) da votacao. NOMINAL apenas — voto secreto NAO e' atribuivel por design
  (sigilo no schema; votos_secretos nao tem vereador_id). P/ votacao secreta devolve [] (sem votos nominais)."
  [tx ente-id votacao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :votacao_id :vereador_id :voto :registrado_em]
                  :from [:legislativo.votos]
                  :where [:and [:= :ente_id ente-id] [:= :votacao_id votacao-id]]
                  :order-by [[:registrado_em :asc]]}))))

(defn- apurar
  "Apura {:sim n :nao n :abstencao n} contando os votos da `tabela` (votos | votos_secretos) por valor.
  Usa linhas->kebab (padrao do modulo): a chave do `voto` fica `:voto` independente da tabela de origem —
  robusto vs. o namespace por-tabela do next.jdbc (review F3.7 clojure-MAJOR)."
  [tx ente-id votacao-id tabela]
  (let [por-voto (->> (jdbc/execute! tx
                        (sql/format {:select [:voto [[:count :*] :n]] :from [tabela]
                                     :where [:and [:= :ente_id ente-id] [:= :votacao_id votacao-id]]
                                     :group-by [:voto]}))
                      comum/linhas->kebab
                      (into {} (map (juxt :voto :n))))]
    {:sim (get por-voto "sim" 0) :nao (get por-voto "nao" 0) :abstencao (get por-voto "abstencao" 0)}))

(defn- votacao+lock
  [tx ente-id id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version :modalidade :quorum_tipo] :from [:legislativo.votacoes]
                     :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
      comum/linha->kebab))

(defn encerrar!
  "Encerra a votacao (estado 'encerrada'): apura os votos (nominal->votos, secreta->votos_secretos), computa
  o resultado pela aritmetica EXATA do quorum (logic/resultado-votacao com `base-membros` = composicao da
  Casa, resolvida UPSTREAM e passada explicita — nao JOIN), e grava o snapshot (totais+base+resultado) com
  CAS por lock_version. Modalidade 'simbolica' (aclamacao) nao apura individual: passe `:resultado` explicito.
  Lanca em conflito de lock OU votacao inexistente. Fail-closed: parecer terminal nao reabre (trigger)."
  [tx {:keys [id ente-id base-membros resultado updated-by lock-version]}]
  (let [{:keys [estado modalidade quorum-tipo]} (votacao+lock tx ente-id id)]
    (when (nil? estado)
      (throw (ex-info "encerrar!: votacao inexistente" {:id id :ente-id ente-id})))
    ;; fail-closed (review F3.7 clojure-MENOR): votacao terminal nao reabre — erro inspecionavel em vez de
    ;; um "conflito de lock" enganoso (o trigger tambem barra, mas a mensagem aqui e' a causa real).
    (when (contains? logic/estados-votacao-terminais estado)
      (throw (ex-info "encerrar!: votacao ja em estado terminal" {:id id :estado estado})))
    (let [tally (case modalidade
                  "nominal" (apurar tx ente-id id :legislativo.votos)
                  "secreta" (apurar tx ente-id id :legislativo.votos_secretos)
                  "simbolica" nil)
          res (if (= "simbolica" modalidade)
                (or resultado (throw (ex-info "encerrar!: votacao simbolica exige :resultado explicito" {:id id})))
                (logic/resultado-votacao quorum-tipo tally base-membros))
          r (jdbc/execute-one! tx
              (sql/format {:update :legislativo.votacoes
                           :set {:estado "encerrada" :resultado res
                                 :total_sim (:sim tally) :total_nao (:nao tally) :total_abstencao (:abstencao tally)
                                 :base_membros base-membros :updated_by updated-by :atualizado_em [:now]
                                 :lock_version [:+ :lock_version 1]}
                           :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
      (when (zero? (:next.jdbc/update-count r 0))
        (throw (ex-info "encerrar!: conflito de lock_version ou votacao inexistente"
                        {:id id :lock-version lock-version})))
      (merge {:id id :estado "encerrada" :resultado res :base-membros base-membros} tally))))

(defn anular!
  "Anula a votacao (estado 'anulada', terminal) com CAS. Usada na correcao: anula a votacao errada e abre-se
  uma NOVA apontando a corrigida (votacao_corrige_id). O trigger trava anular uma JA terminal."
  [tx {:keys [id ente-id updated-by lock-version]}]
  (let [r (jdbc/execute-one! tx
            (sql/format {:update :legislativo.votacoes
                         :set {:estado "anulada" :updated_by updated-by :atualizado_em [:now]
                               :lock_version [:+ :lock_version 1]}
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
    (when (zero? (:next.jdbc/update-count r 0))
      (throw (ex-info "anular!: conflito de lock_version ou votacao inexistente"
                      {:id id :lock-version lock-version})))
    {:id id :estado "anulada"}))
