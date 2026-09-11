(ns oplenario.legislativo.db.tramitacao
  "Persistencia + ENGINE da tramitacao por motor declarativo (eixo C). O regimento e' DADO
  (template/estado/transicao); `transicionar!` carrega as transicoes candidatas (de_estado+gatilho),
  avalia o GUARD de cada uma com o MESMO avaliador do motor (disciplina 5, motor/api/guarda-dsl) e, na
  primeira que passa, grava o historico (append-only) + muda o estado da proposicao — tudo na MESMA tx.
  Importa db/proposicao (db->db mesmo modulo) e motor/api (modulo->motor) — ambos permitidos (§22.10/§3-bis).
  Acao (handler em codigo) e' GRAVADA mas a execucao rica fica p/ F3.3b; aqui o efeito e' a mudanca de estado."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.motor.api :as motor]))

(set! *warn-on-reflection* true)

;; ---- montagem do template (config tenant; usada no onboarding/fixture/import) ----
(defn criar-template!
  "Persiste um template de tramitacao. `:sujeito` ('proposicao' default | 'parecer') e' o discriminador
  do tipo de entidade que o template governa (F3.6a): as tabelas de template sao subject-agnosticas, o
  sujeito e' validado no service do sujeito (ex.: parecer/criar! recusa template de 'proposicao'). Omitir
  = 'proposicao' (preserva os callers do eixo C).

  `:ativo` (omitir = true, o default da coluna) e' o que APOSENTA uma versao de rito: o versionamento e'
  por COPIA INTEGRAL (mig 0016), entao apos o primeiro bump a Casa tem mais de uma linha com o mesmo
  `sujeito`. `db/proposicao/protocolar!` so' considera os ATIVOS ao resolver o rito de uma materia nova —
  sem poder gravar `ativo` aqui nao havia como montar (nem testar) esse cenario."
  [tx {:keys [id ente-id chave versao nome estado-inicial sujeito ativo template-pai-id]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.template_tramitacao
                 :values [{:id id :ente_id ente-id :chave chave :versao (or versao 1) :nome nome
                           :estado_inicial estado-inicial :sujeito (or sujeito "proposicao")
                           :ativo (if (some? ativo) (boolean ativo) true)
                           :template_pai_id template-pai-id :efetivado_em [:now]}]})))

(defn criar-estado! [tx {:keys [id ente-id template-id chave nome terminal ordem]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.template_estado
                 :values [{:id id :ente_id ente-id :template_id template-id :chave chave :nome nome
                           :terminal (boolean terminal) :ordem (or ordem 0) :efetivado_em [:now]}]})))

(defn criar-transicao!
  "Persiste uma transicao do template. GATEIA o GUARD no save (Inv.4, motor/validar-guarda): guard que
  nao parseia NAO entra no banco — a falha de tramitacao sai do caminho critico (rejeitada na config, nao
  no meio de um fluxo). Lanca ex-info :guarda-invalida com a causa do erro de sintaxe."
  [tx {:keys [id ente-id template-id de-estado para-estado gatilho guarda acao ordem]}]
  ;; fail-closed: SO "VALIDA" passa — qualquer outro status (incl. valor inesperado) REJEITA o save
  ;; (quem nao decide, NEGA; mesma postura do check! de authz). String casa a convencao de verificar-fonte.
  (let [{:keys [status erros]} (motor/validar-guarda guarda)]
    (when (not= "VALIDA" status)
      (throw (ex-info "guard da transicao mal-formado (rejeitado no save, Inv.4)"
                      {:erro :guarda-invalida :de-estado de-estado :gatilho gatilho :erros erros}))))
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.template_transicao
                 :values [{:id id :ente_id ente-id :template_id template-id :de_estado de-estado
                           :para_estado para-estado :gatilho gatilho :guarda guarda :acao acao
                           :ordem (or ordem 0) :efetivado_em [:now]}]})))

(defn transicoes-de
  "Transicoes candidatas (ordenadas) de `de-estado` sob `gatilho` no template."
  [tx ente-id template-id de-estado gatilho]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:id :ente_id :template_id :de_estado :para_estado :gatilho :guarda :acao :ordem]
                   :from [:legislativo.template_transicao]
                   :where [:and [:= :ente_id ente-id] [:= :template_id template-id]
                           [:= :de_estado de-estado] [:= :gatilho gatilho]]
                   :order-by [[:ordem :asc] [:id :asc]]}))))

(defn transicoes-do-estado
  "Fatia 3 (a LEITURA) — TODAS as transicoes declaradas a partir de `de-estado`, de TODOS os gatilhos, na
  ORDEM do rito. E' a irma de `transicoes-de`: aquela filtra por gatilho porque a ENGINE ja' sabe qual ato
  foi pedido; esta nao filtra porque o OPERADOR ainda nao sabe qual pedir — sem ela ele teria de adivinhar
  a string do gatilho e a rota de escrita ficaria inutilizavel pela interface.

  Mesmo `ORDER BY` e o MESMO indice de `transicoes-de` (`idx_template_transicao_origem`, prefixo
  ente_id+template_id+de_estado — o `gatilho` a mais no indice nao atrapalha a poda).

  `:guarda` vem na projecao e NAO e' detalhe interno vazado: e' o unico dado que separa um ato que o rito
  declara incondicional de um que tem condicao a verificar no disparo. Quem le' a lista precisa dessa
  distincao p/ nao prometer o que o guard pode recusar.

  SEM TETO, deliberadamente. Todo o resto do modulo empurra `limite` ao SQL, mas aqui truncar seria ESCONDER
  UM ATO QUE A CASA DECLARA — o operador nao veria a opcao e concluiria que o rito nao a tem. A tabela e'
  config escrita por humano e o recorte e' 'saidas de UM estado de UM rito': a cardinalidade e' de unidades."
  [tx ente-id template-id de-estado]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:id :de_estado :para_estado :gatilho :guarda :acao :ordem]
                   :from [:legislativo.template_transicao]
                   :where [:and [:= :ente_id ente-id] [:= :template_id template-id]
                           [:= :de_estado de-estado]]
                   :order-by [[:ordem :asc] [:id :asc]]}))))

(defn estado-no-template
  "O estado `chave` COMO O RITO O DECLARA, ou nil se o rito nao o declara.

  Existe p/ desambiguar a lista de gatilhos VAZIA, que tem causas que pedem acoes OPOSTAS do operador:
    - `{:terminal true}`  -> o rito acabou aqui. Nada a fazer, e esta' certo.
    - `{:terminal false}` -> beco: o rito conhece o estado mas nao declara saida dele. Alguem tem de mexer
                             na CONFIG; a materia esta' presa.
    - `nil`               -> o rito nem conhece este estado. Materia anterior ao rito, ou rito trocado sob
                             os pes dela (o versionamento de template e' por copia integral, mig 0016).
  Sem este dado a borda so' poderia dizer 'nenhum ato disponivel' — verdadeiro e inutil."
  [tx ente-id template-id chave]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:chave :nome :terminal :ordem]
                   :from [:legislativo.template_estado]
                   :where [:and [:= :ente_id ente-id] [:= :template_id template-id] [:= :chave chave]]}))))

(defn registrar-transicao!
  "Append-only: grava a transicao OCORRIDA no historico (a prova duravel, Inv.10). RETURNING `ocorrido_em`
  (F7 carry): o carimbo REAL da transicao, devolvido p/ `transicionar!` incluir no evento de dominio
  (proposicao.transicionou :ocorrido-em) — a jusante, paineis/tramitacao-board usa isto em vez do momento em
  que o consumer PROJETA, evitando que atraso comum do relay resete o sinal de estagnacao."
  [tx {:keys [id ente-id proposicao-id template-id de-estado para-estado gatilho contexto ator-id]}]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :legislativo.proposicao_transicao_historico
                  :values [{:id id :ente_id ente-id :proposicao_id proposicao-id :template_id template-id
                            :de_estado de-estado :para_estado para-estado :gatilho gatilho
                            :contexto (some-> contexto comum/->jsonb) :ator_id ator-id :efetivado_em [:now]}]
                  :returning [:ocorrido_em]}))))

(defn historico-da-proposicao
  "Sem `limite`: TODO o historico, ASC (comportamento historico, callers existentes preservados). Com
  `limite` (review MAJOR fe-9-ficha-materia): empurra o teto ao SQL — `ORDER BY ocorrido_em DESC LIMIT
  limite` aproveita `idx_transicao_hist_proposicao` (ente_id,proposicao_id,ocorrido_em) e traz os N MAIS
  RECENTES (nao os N mais antigos que um `take` em memoria sobre o resultado ASC descartaria); revertido a
  ASC antes de devolver — o CONTRATO de ordem (cronologica) e' o MESMO com ou sem limite, so' o conjunto
  muda (recente-o-bastante em vez de tudo)."
  ([tx ente-id proposicao-id] (historico-da-proposicao tx ente-id proposicao-id nil))
  ([tx ente-id proposicao-id limite]
   ;; :contexto incluido (o payload do gatilho — quem/refs externas) p/ a visao de auditoria nao
   ;; perder a carga da transicao (e' persistido por registrar-transicao!).
   (let [base {:select [:id :proposicao_id :template_id :de_estado :para_estado :gatilho :contexto :ator_id :ocorrido_em]
               :from [:legislativo.proposicao_transicao_historico]
               :where [:and [:= :ente_id ente-id] [:= :proposicao_id proposicao-id]]}
         linhas (comum/linhas->kebab
                  (jdbc/execute! tx
                    (sql/format (if limite
                                  (assoc base :order-by [[:ocorrido_em :desc]] :limit limite)
                                  (assoc base :order-by [[:ocorrido_em :asc]])))))]
     (if limite (vec (reverse linhas)) linhas))))

(defn- estado+lock
  "Le estado+lock_version da proposicao SOB FOR UPDATE: serializa transicoes concorrentes na MESMA
  proposicao (dois clerks no mesmo ato). Sem isso, ambas leem o mesmo lock_version e a 2a perde o CAS
  com excecao de conflito (saida nao-documentada do contrato). Com o lock, a 2a espera, le o estado
  fresco e reavalia o guard deterministicamente."
  [tx ente-id proposicao-id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version] :from [:legislativo.proposicoes]
                     :where [:and [:= :ente_id ente-id] [:= :id proposicao-id]]
                     :for :update}))
      comum/linha->kebab))

(defn transicionar!
  "ENGINE da tramitacao (eixo C). Do estado ATUAL da proposicao, sob `gatilho`, escolhe a 1a transicao
  candidata cujo GUARD passa (guard nil = sempre passa; senao avalia via motor/guarda-dsl com a proposicao
  no `amb`) e aplica: historico append-only + muda o estado (CAS por lock_version). Atomico na tx (o caller
  abre via Repo/transacao, passando o RegistroFatos do motor). Devolve {:transicionou? bool :de :para
  :transicao-id} — guard que bloqueia TODAS as candidatas e' resultado normal (transicionou? false), nao erro.
  DISTINTO disso: guard que LANCA (fato ausente no registry, tipo nao-booleano no runtime) e o CAS de
  lock_version (conflito de escrita) PROPAGAM como excecao — o controller distingue: resultado = dominio
  normal; excecao = erro de avaliacao/conflito."
  [tx {:keys [registro ente-id proposicao-id template-id gatilho ator-id contexto agora updated-by]}]
  (let [linha (estado+lock tx ente-id proposicao-id)
        ;; fail-closed (Fatia 2; ESPELHO do fix que `transicionar-parecer!` ganhou na review F3.6a — o
        ;; [CARRY disc.6] daquele ns pede paridade explicita entre os dois sujeitos). Materia inexistente
        ;; no tenant lia estado `nil`, nao casava candidata nenhuma e saia como `{:transicionou? false}` —
        ;; que o contrato define como "o guard bloqueou". A borda entao responderia "a Casa nao permite
        ;; este ato agora" sobre uma materia que NAO EXISTE: resposta plausivel, confiante e errada.
        _ (when (nil? linha)
            (throw (ex-info "transicionar!: proposicao inexistente no tenant"
                            {:tipo :conflito/transicao :proposicao-id proposicao-id :ente-id ente-id})))
        {:keys [estado lock-version]} linha
        candidatas (transicoes-de tx ente-id template-id estado gatilho)
        passa? (fn [t]
                 (or (nil? (:guarda t))
                     ((motor/guarda-dsl {:registro registro :tx tx :expr (:guarda t)
                                         :agora agora :ente-id ente-id})
                      {"proposicao" {:id proposicao-id :estado estado} "contexto" (or contexto {})})))
        escolhida (first (filter passa? candidatas))]
    (if-not escolhida
      {:transicionou? false :de estado :gatilho gatilho}
      (let [{:keys [ocorrido-em]} (registrar-transicao! tx {:id (random-uuid) :ente-id ente-id :proposicao-id proposicao-id
                                                            :template-id template-id :de-estado estado
                                                            :para-estado (:para-estado escolhida)
                                                            :gatilho gatilho :contexto contexto :ator-id ator-id})]
        (proposicao/mudar-estado! tx {:id proposicao-id :ente-id ente-id :estado (:para-estado escolhida)
                                      :updated-by updated-by :lock-version lock-version})
        {:transicionou? true :de estado :para (:para-estado escolhida) :transicao-id (:id escolhida)
         :acao (:acao escolhida) :ocorrido-em ocorrido-em}))))
