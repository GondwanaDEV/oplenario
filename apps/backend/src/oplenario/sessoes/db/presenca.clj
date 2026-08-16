(ns oplenario.sessoes.db.presenca
  "Persistencia da PRESENCA (§22.6 eixo C, F4.3a) — funcoes sobre a `tx` do tenant (RLS isola). presenca_evento
  e' APPEND-ONLY (registrar-evento! / listar-eventos = auditoria). A presenca DERIVADA (esta-presente-em? + os
  agregadores de quorum) vive em sessoes/relacoes/presenca (camada de relacao, F4.3b — ADR-0001 §3-bis: o db/
  nao e' importado por relacoes; ambos escrevem HoneySQL). justificativa_ausencia e' ato apartado com state
  machine (decidir-justificativa! = CAS + transicao validada). HoneySQL schema-qualified; ente_id em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.sessoes.logic :as logic]))

(set! *warn-on-reflection* true)

;; ---------- presenca_evento (append-only) ----------

(defn registrar-evento!
  "Grava um evento de presenca (append-only). `ocorrido-em` = instante de DOMINIO (quando ocorreu); o
  efetivado_em=now() e' o instante de AUDIT. Valida tipo/modalidade/fonte (fail-closed).

  Devolve {:id :ocorrido-em :registrado-em} — os DOIS carimbos, por RETURNING (o `registrado_em` e' DEFAULT
  do banco; le-lo de volta e' a unica forma de o recibo dizer a verdade sem uma segunda consulta). O par
  existe no contrato porque, enquanto nao houver um tipo de evento de RETIFICACAO, e' o unico jeito de o
  juridico distinguir 'o vereador saiu as 15h' de 'a secretaria digitou as 17h um registro das 15h'."
  [tx {:keys [id ente-id sessao-id vereador-id tipo modalidade fonte ocorrido-em created-by]}]
  (logic/validar-tipo-evento tipo)
  (logic/validar-modalidade-presenca modalidade)
  (logic/validar-fonte fonte)
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :sessoes.presenca_evento
                  :values [{:id id :ente_id ente-id :sessao_id sessao-id :vereador_id vereador-id
                            :tipo tipo :modalidade modalidade :fonte fonte :ocorrido_em ocorrido-em
                            :created_by created-by :efetivado_em [:now]}]
                  :returning [:id :ocorrido_em :registrado_em]}))))

(defn registrar-lote!
  "Grava N eventos de presenca (append-only) NUM UNICO statement `INSERT ... VALUES` multi-linha (Etapa 2c) —
  a atomicidade real vem de rodar dentro da MESMA `tx` que o caller (Repo-Component) ja abre para o lote
  inteiro (o gate de estado/janela roda ANTES de qualquer INSERT); este statement unico e' so' a forma mais
  barata de inserir N linhas, nao o mecanismo de atomicidade.

  Valida tipo/modalidade/fonte de TODO `registros` ANTES do INSERT (fail-closed sobre o lote inteiro, nao so'
  a primeira linha ruim — um `doseq` que parasse na 2a linha ruim ainda teria inserido a 1a).

  Devolve os recibos na MESMA ORDEM de `registros` — casados por `:id` (gerado pelo caller, um por linha) e
  NAO pela ordem crua do RETURNING: Postgres tipicamente preserva a ordem do VALUES num INSERT simples sem
  ON CONFLICT, mas casar por `:id` nao depende dessa garantia implicita."
  [tx ente-id sessao-id registros]
  (doseq [{:keys [tipo modalidade fonte]} registros]
    (logic/validar-tipo-evento tipo)
    (logic/validar-modalidade-presenca modalidade)
    (logic/validar-fonte fonte))
  (let [linhas (comum/linhas->kebab
                (jdbc/execute! tx
                  (sql/format {:insert-into :sessoes.presenca_evento
                               :values (mapv (fn [{:keys [id vereador-id tipo modalidade fonte ocorrido-em created-by]}]
                                               {:id id :ente_id ente-id :sessao_id sessao-id :vereador_id vereador-id
                                                :tipo tipo :modalidade modalidade :fonte fonte :ocorrido_em ocorrido-em
                                                :created_by created-by :efetivado_em [:now]})
                                             registros)
                               :returning [:id :ocorrido_em :registrado_em]})))
        por-id (into {} (map (juxt :id identity)) linhas)]
    (mapv (fn [{:keys [id]}] (por-id id)) registros)))

(defn listar-eventos
  "Todos os eventos da sessao em ordem cronologica (auditoria; a presenca corrente e' derivada, nao listada)."
  [tx ente-id sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :ente_id :sessao_id :vereador_id :tipo :modalidade :fonte :ocorrido_em :efetivado_em]
                  :from [:sessoes.presenca_evento]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                  :order-by [[:ocorrido_em :asc] [:id :asc]]}))))

(defn serie-de-eventos-da-sessao
  "Todos os eventos de presenca da sessao NA JANELA [piso, teto] — a MESMA janela que a chamada usa
  (`logic/piso-da-janela-de-presenca` como piso, `logic/instante-de-avaliacao` como teto), ordenados por
  (vereador_id asc, ocorrido_em asc, fonte_precedencia desc, id asc). E' a SERIE cronologica de CADA
  vereador ('entrou 14h03, saiu 15h10, retornou 15h40') — insumo cru da FOLHA (Etapa 5 fatia 1). Nao
  confundir com `presenca-corrente` (o ULTIMO evento) nem com `listar-eventos` (a sessao inteira, sem
  janela — a auditoria).

  Ambos os limites INCLUSIVOS: um evento gravado exatamente no instante em que a sessao fechou e' o que a
  chamada final capturou (`instante-de-avaliacao` usa `ocorrido_em <= instante` do mesmo jeito), e nao pode
  desaparecer da folha por uma fronteira estrita. `fonte_precedencia` e' a coluna GENERATED da migration
  0056 — a MESMA usada em `logic/ordem-ultimo-evento`, aqui so' como desempate de MESMO instante (nao
  particiona por vereador: a serie mostra todo evento, nao so' o vencedor)."
  [tx ente-id sessao-id piso teto]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :ente_id :sessao_id :vereador_id :tipo :modalidade :fonte :ocorrido_em :efetivado_em]
                  :from [:sessoes.presenca_evento]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]
                          [:>= :ocorrido_em piso] [:<= :ocorrido_em teto]]
                  :order-by [[:vereador_id :asc] [:ocorrido_em :asc] [:fonte_precedencia :desc] [:id :asc]]}))))

(defn presenca-corrente
  "O ULTIMO evento de presenca de CADA vereador da sessao ate' `instante` — uma linha por vereador
  (DISTINCT ON), o insumo cru da CHAMADA. Nao confundir com `listar-eventos`: aquele e' a AUDITORIA (todos os
  eventos, append-only), este e' o estado corrente derivado. A presenca corrente nunca e' materializada.

  A subquery vem da fonte CANONICA (`logic/ultimos-eventos-por-vereador-q`), a mesma de `presentes-na-sessao`
  aqui e dos agregadores de `relacoes/presenca` que o motor de votacao alcanca por nome. Transcrever a ordem
  de desempate a mao aqui seria a TERCEIRA copia — e a fatia que fechou essa porta existiu porque divergir
  fazia a TELA anunciar um quorum e a POLICY usar outro na MESMA sessao. A chamada e' justamente a tela.

  A PROJECAO e' mais larga que a dos agregadores porque a chamada mostra mais que 'presente s/n':
  `fonte` distingue o que a Mesa marcou do que o vereador confirmou pelo celular (e e' o que sustenta a
  precedencia visivel), e `ocorrido_em` (instante de DOMINIO) e `registrado_em` (AUDIT) sao tempos
  diferentes que a ata precisa separar — 'entrou as 10h' nao e' 'a secretaria digitou as 11h'.

  Indice: `idx_presenca_evento_corrente (ente_id, sessao_id, vereador_id, ocorrido_em DESC,
  fonte_precedencia DESC, id DESC)` casa o WHERE e o ORDER BY inteiros (IndexScan+Unique, sem Sort). O
  INCLUDE cobre so' (tipo, modalidade), entao `fonte`/`registrado_em` custam a visita ao heap que os
  agregadores nao pagam — tradeoff aceito: sao as linhas de UMA sessao (dezenas), nao um agregado
  cross-sessao, e sem esses campos a chamada nao existe."
  [tx ente-id sessao-id instante]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format (logic/ultimos-eventos-por-vereador-q
                  {:sessao-id sessao-id :instante instante :ente-id ente-id
                   :projecao [:vereador_id :tipo :modalidade :fonte :ocorrido_em :registrado_em]})))))

;; ---------- Etapa 6 fatia 2 — leituras EM LOTE (insumo da apuracao de assiduidade) ----------
;; A apuracao le' um PERIODO inteiro (ate' 400 sessoes) de uma vez — reabrir `presenca-corrente`/
;; `listar-justificativas-da-sessao` sessao a sessao seria o mesmo carry N+1 que a fatia 1 fechou para o
;; roster (`i5-decisao.md:277`). As duas funcoes abaixo sao as irmas em LOTE.

(defn- agrupar-por-sessao-pedida
  "Agrupa `linhas` por `:sessao-id` PRE-SEMEANDO toda `sessao-id` pedida (vetor vazio quando nao ha' linha) —
  chave AUSENTE seria lida a jusante como 'nao perguntei por essa sessao', nunca como 'perguntei e a resposta
  e' vazia'.

  E LANCA se o banco devolveu um grupo cuja chave NAO estava entre as pedidas. Antes desta checagem o grupo
  simplesmente sumia: um `(get por-sessao sid [])` sobre as chaves pedidas descarta em SILENCIO tudo o que o
  banco trouxe com outra chave — e o unico jeito de isso acontecer e' o tipo devolvido pelo driver nao casar
  com o tipo pedido, que e' precisamente o defeito que a fatia 1 pagou com `java.sql.Date`. Descartar o grupo
  transforma um erro de contrato numa apuracao em branco; lancar o torna visivel."
  [linhas ids rotulo]
  (let [pedidas (set ids)
        por-sessao (group-by :sessao-id linhas)]
    (when-let [intrusas (seq (remove pedidas (keys por-sessao)))]
      (throw (ex-info "lote de presenca devolveu sessao que nao foi pedida (tipo de chave divergente?)"
                      {:tipo :invariante/sessao-desconhecida :leitura rotulo
                       :classes (into #{} (map #(some-> % class .getName)) intrusas)
                       :quantas (count intrusas)})))
    (into {} (map (fn [sid] [sid (get por-sessao sid [])])) pedidas)))

(defn presencas-correntes-das-sessoes
  "O ULTIMO evento de presenca de CADA vereador, para um LOTE de sessoes de uma vez — cada sessao com o SEU
  PROPRIO instante de corte (I5: um instante GLOBAL do periodo fabricaria presenca). UMA UNICA query, via
  `logic/ultimos-eventos-por-sessao-e-vereador-q` (a fonte CANONICA compartilhada com `presenca-corrente`
  singular e com os agregadores do motor de votacao — I2: nunca redigitar a ordem de desempate).

  `sessoes-com-instante` = colecao de pares `[sessao-id instante]` (o instante ja' resolvido por
  `logic/instante-de-avaliacao`, um por sessao — o CHAMADOR decide, esta funcao so' le').

  Devolve `{sessao-id -> [presenca-linha ...]}`, PRE-SEMEADO com toda `sessao-id` de `sessoes-com-instante`
  (vetor vazio quando a sessao nao teve nenhum evento) — mesma disciplina de
  `vereador/roster-da-casa-em-datas`: chave AUSENTE seria lida a jusante como 'nao perguntei por essa
  sessao', nunca como 'perguntei e a resposta e' vazia' (e a apuracao itera sobre as sessoes pedidas, nao
  sobre as chaves que a query devolveu).

  `sessoes-com-instante` VAZIO devolve `{}` SEM tocar o banco (mesmo racional de `licencas-de-mandatos` para
  id vazio — o periodo sem nenhuma sessao fechada e' o caso normal do primeiro mes de uma Casa nova).

  TETO fail-closed (`logic/teto-de-linhas-de-lote-de-presenca`, 400x150) via `:max-rows` = teto+1, no molde
  de `vereador/teto-de-linhas-lote` da fatia 1 — ver la' por que o teto de SESSOES nao basta (`vereador_id`
  sem FK; o `DISTINCT ON` e' limitado pelos ids que aparecem nos EVENTOS, nao pelos do roster)."
  [tx ente-id sessoes-com-instante]
  (if (empty? sessoes-com-instante)
    {}
    (let [teto logic/teto-de-linhas-de-lote-de-presenca
          linhas (comum/linhas->kebab
                  (jdbc/execute! tx
                    (sql/format (logic/ultimos-eventos-por-sessao-e-vereador-q
                                 {:sessoes-e-instantes sessoes-com-instante :ente-id ente-id
                                  :projecao [:vereador_id :tipo :modalidade :fonte
                                             :ocorrido_em :registrado_em]}))
                    {:max-rows (inc teto)}))]
      (when (> (count linhas) teto)
        (throw (ex-info "lote de presenca do periodo acima do teto de linhas"
                        {:tipo :limite/linhas-excedido :medido-ao-menos (count linhas) :teto teto
                         :sessoes (count sessoes-com-instante)})))
      (agrupar-por-sessao-pedida linhas (map first sessoes-com-instante) :presencas))))

(defn justificativas-das-sessoes
  "As justificativas de ausencia de um LOTE de sessoes de uma vez — irma em lote de
  `listar-justificativas-da-sessao`. UMA UNICA query, filtrando `sessao_id IN (...)` — o volume ja' esta'
  bounded pelo teto de sessoes do periodo (400), entao um `IN` simples e' barato aqui (ao contrario do lote
  de presenca, que precisa de um instante DIFERENTE por sessao e por isso nao pode ser um `IN` simples).

  PROJECAO MINIMA — `[:sessao_id :vereador_id :estado]`, e NAO a da irma singular. O unico consumidor e'
  `logic/estado-de-presenca`, que le' `:estado`; `motivo`, `decidido-por`, `decidido-em` e `lock-version`
  atravessavam a fronteira do modulo sem sair em lugar nenhum do payload. `motivo` e' onde o vereador
  escreve POR QUE faltou — na pratica dado de saude (LGPD art. 11), e este repo ja' teve um incidente com
  ele indo parar em log via ex-data (Etapa 5, ver `mensagem-de-recusa-de-justificativa`). Copiar a projecao
  da leitura irma e' exatamente como esse dado viaja para onde ninguem pediu.

  TETO fail-closed (`logic/teto-de-linhas-de-lote-de-presenca`) via `:max-rows` = teto+1, mesma disciplina
  de `presencas-correntes-das-sessoes`: a UNIQUE (ente, sessao, vereador) limita a uma justificativa por
  vereador por sessao, mas `vereador_id` aqui tambem nao tem FK.

  Devolve `{sessao-id -> [justificativa-linha ...]}`, PRE-SEMEADO com toda `sessao-id` pedida (vetor vazio
  quando a sessao nao tem justificativa nenhuma) — mesma disciplina de `presencas-correntes-das-sessoes`.
  `sessao-ids` VAZIO devolve `{}` sem tocar o banco."
  [tx ente-id sessao-ids]
  (if (empty? sessao-ids)
    {}
    (let [ids (vec (distinct sessao-ids))
          teto logic/teto-de-linhas-de-lote-de-presenca
          linhas (comum/linhas->kebab
                  (jdbc/execute! tx
                    (sql/format {:select [:sessao_id :vereador_id :estado]
                                 :from [:sessoes.justificativa_ausencia]
                                 :where [:and [:= :ente_id ente-id] [:in :sessao_id ids]]
                                 :order-by [[:sessao_id :asc] [:vereador_id :asc]]})
                    {:max-rows (inc teto)}))]
      (when (> (count linhas) teto)
        (throw (ex-info "lote de justificativas do periodo acima do teto de linhas"
                        {:tipo :limite/linhas-excedido :medido-ao-menos (count linhas) :teto teto
                         :sessoes (count ids)})))
      (agrupar-por-sessao-pedida linhas ids :justificativas))))

;; ---------- justificativa_ausencia (ato apartado, state machine) ----------

(defn buscar-justificativa-do-vereador
  "A justificativa daquele vereador NAQUELA sessao (a UNIQUE (ente, sessao, vereador) garante no maximo uma),
  ou nil. Existe p/ o 409 da abertura ser ACIONAVEL — devolver o id da que ja' existe, em vez de so' dizer
  'conflito' e deixar a tela sem para onde mandar o operador. Servida pelo indice UNIQUE da mig 0029."
  [tx ente-id sessao-id vereador-id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [:id :estado :lock_version]
                  :from [:sessoes.justificativa_ausencia]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]
                          [:= :vereador_id vereador-id]]}))))

(defn criar-justificativa!
  "Cria a justificativa de ausencia 'pendente'. Devolve {:id :estado :lock-version} por RETURNING — o
  `lock_version` e' DEFAULT do banco, e le-lo de volta e' o que permite ao 201 ja' entregar o token de CAS
  (a Mesa decide sem uma segunda leitura) sem que a borda ADIVINHE o zero.

  DUAS camadas contra a duplicata, e elas nao sao redundantes:
    - a UNIQUE (ente_id, sessao_id, vereador_id) da mig 0029 e' a GARANTIA (checar-e-inserir sozinho e' uma
      corrida: duas requisicoes simultaneas leem 'nao existe' e as duas inserem);
    - o pre-check aqui e' a MENSAGEM (o 23505 nao diz QUAL linha colidiu, e depois dele a tx esta abortada —
      nao da' para consultar). Ele perde a corrida em silencio e tudo bem: quem perde cai no 23505, que o
      Repo-Component traduz na mesma tag `:conflito/justificativa`, so' sem o id da existente."
  [tx {:keys [id ente-id sessao-id vereador-id motivo created-by]}]
  (when-let [existente (buscar-justificativa-do-vereador tx ente-id sessao-id vereador-id)]
    (throw (ex-info (logic/mensagem-de-recusa-de-justificativa :ja-existe)
                    {:tipo :conflito/justificativa :motivo :ja-existe
                     :justificativa-id (:id existente) :estado (:estado existente)})))
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :sessoes.justificativa_ausencia
                  :values [{:id id :ente_id ente-id :sessao_id sessao-id :vereador_id vereador-id
                            :estado "pendente" :motivo motivo :created_by created-by :efetivado_em [:now]}]
                  :returning [:id :estado :lock_version]}))))

(defn buscar-justificativa [tx ente-id id]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [:id :ente_id :sessao_id :vereador_id :estado :motivo :decidido_por :decidido_em :lock_version]
                  :from [:sessoes.justificativa_ausencia]
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn listar-justificativas-da-sessao
  "As justificativas de ausencia da sessao — o TERCEIRO insumo da chamada (cadastro + evento + este ato). E'
  por linha de vereador, nao um agregado: a derivacao precisa saber se a justificativa daquele vereador esta
  `aprovada` (ausencia justificada) ou ainda `pendente`, que e' um estado PROPRIO — publicar 'ausente' sobre
  uma justificativa que a Mesa ainda nao apreciou e' acusacao falsa que vai para a ata.

  `lock_version` entra na projecao porque a borda que DECIDE (Etapa 2) faz CAS com ele; sem devolve-lo aqui
  a tela precisaria de uma segunda leitura por linha so' para poder deferir. `motivo` e' texto da propria
  justificativa (nao ha' dado de terceiro aqui) e o consumidor da chamada e' a Mesa, nao o portal publico.

  Servida pelo `idx_justificativa_ausencia_sessao (ente_id, sessao_id)` (mig 0029), que casa o WHERE inteiro.
  Ordem deterministica por `vereador_id` — a chamada e' conferida linha a linha e nao pode reordenar entre
  dois carregamentos."
  [tx ente-id sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :vereador_id :estado :motivo :decidido_por :decidido_em :lock_version]
                  :from [:sessoes.justificativa_ausencia]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                  :order-by [[:vereador_id :asc]]}))))

(defn- estado+lock [tx ente-id id]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [:estado :lock_version] :from [:sessoes.justificativa_ausencia]
                     :where [:and [:= :ente_id ente-id] [:= :id id]] :for :update}))
      comum/linha->kebab))

(defn decidir-justificativa!
  "Decide a justificativa: estado alvo aprovada|indeferida (terminal), via maquina logic/transicao-justificativa-valida?
  (fail-closed) com CAS por lock_version, carimbando decisor + instante. Devolve {:de :para}.

  TODO modo de falha do USUARIO usa a MESMA tag `:tipo :conflito/justificativa` (inexistente, lock stale,
  transicao invalida) — e' isso que permite ao handler HTTP fazer um `catch` unico -> 409, sem uma arvore de
  tags por caso. Estado-alvo fora dos terminais e `decidido-por` nil sao BUG DE SERVIDOR (a borda ja' os
  barra): ficam sem tag, viram 500 opaco, que e' o certo — nao ha' nada que o operador possa corrigir."
  [tx {:keys [ente-id id estado decidido-por lock-version]}]
  (when-not (contains? logic/estados-justificativa-terminais estado)
    (throw (ex-info "decidir-justificativa!: estado alvo deve ser aprovada|indeferida" {:estado estado})))
  (when (nil? decidido-por)
    (throw (ex-info "decidir-justificativa!: decidido-por e' obrigatorio (trilha de quem decidiu)" {:id id})))
  (let [{atual :estado db-lock :lock-version} (estado+lock tx ente-id id)]
    (when (nil? atual)
      (throw (ex-info (logic/mensagem-de-recusa-de-justificativa :inexistente)
                      {:tipo :conflito/justificativa :motivo :inexistente :id id :ente-id ente-id})))
    (when (not= db-lock lock-version)
      (throw (ex-info (logic/mensagem-de-recusa-de-justificativa :lock-stale)
                      {:tipo :conflito/justificativa :motivo :lock-stale :id id})))
    (when-not (logic/transicao-justificativa-valida? atual estado)
      (throw (ex-info (logic/mensagem-de-recusa-de-justificativa :transicao-invalida)
                      {:tipo :conflito/justificativa :motivo :transicao-invalida :id id :de atual :para estado})))
    (let [r (jdbc/execute-one! tx
              (sql/format {:update :sessoes.justificativa_ausencia
                           :set {:estado estado :decidido_por decidido-por :decidido_em [:now]
                                 :updated_by decidido-por :atualizado_em [:now] :lock_version [:+ :lock_version 1]}
                           :where [:and [:= :ente_id ente-id] [:= :id id] [:= :lock_version lock-version]]}))]
      ;; rede de seguranca redundante (corrida entre o `estado+lock` FOR UPDATE e o UPDATE) — mesma tag.
      (when (zero? (:next.jdbc/update-count r 0))
        (throw (ex-info (logic/mensagem-de-recusa-de-justificativa :lock-stale)
                        {:tipo :conflito/justificativa :motivo :lock-stale :id id :lock-version lock-version})))
      {:de atual :para estado})))

;; ---------- presenca agregada (read-model barato, FE Onda A1) ----------

(defn- sessoes-encerradas-recentes
  "As `teto` sessoes mais RECENTES do tenant com `estado`='encerrada' e encerrada_em carimbado —
  janela autocontida (nao depende de 'legislativa vigente', que exigiria cruzar cadastros)."
  [tx ente-id teto]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:id :encerrada_em] :from [:sessoes.sessao]
                  :where [:and [:= :ente_id ente-id] [:= :estado [:inline "encerrada"]]
                          [:is-not :encerrada_em nil]]
                  :order-by [[:encerrada_em :desc]] :limit teto}))))

(def ^:private positivos (vec (sort logic/tipos-presenca-positiva)))

(defn- presentes-na-sessao
  "Total de vereadores com ULTIMO evento positivo ate' `instante` (qualquer modalidade) — generaliza
  contar-presentes de sessoes/relacoes/presenca (que filtra por modalidade) p/ o agregado cross-sessao.
  `ente-id` filtra tanto a subquery quanto a contagem externa — defense-in-depth mesmo sob RLS (ente_id em
  toda query, ver docstring do ns).

  A subquery vem da fonte CANONICA (`logic/ultimos-eventos-por-vereador-q`), a mesma que o caminho do motor
  de votacao usa: a ordem de desempate do 'ultimo evento por vereador' era transcrita a mao aqui, e divergir
  dela faria a TELA anunciar um quorum e a POLICY usar outro na MESMA sessao."
  [tx ente-id sessao-id instante]
  (-> (jdbc/execute-one! tx
        (sql/format {:select [[[:count :*] :n]]
                     :from [[(logic/ultimos-eventos-por-vereador-q
                              {:sessao-id sessao-id :instante instante :ente-id ente-id
                               :projecao [:vereador_id :tipo :ente_id]})
                             :u]]
                     :where [:and [:= :u.ente_id ente-id] [:in :u.tipo positivos]]}))
      comum/linha->kebab :n))

(defn resumo-presenca
  "Presenca agregada (F7/FE Onda A1, barata): media de presenca das ultimas `teto` sessoes ENCERRADAS do
  tenant. numerador = soma de presentes por sessao; denominador = (n de sessoes) x `membros-da-casa`
  (resolvido pelo CALLER via cadastros, injecao cross-modulo — este ns nao importa cadastros). Devolve
  {:media-percentual :sessoes-consideradas :membros-da-casa} — media nil se nao houve sessao encerrada
  ainda (0/0 e' indefinido, nao 0%). CLAMPED a [0,100] (review final): `membros-da-casa` e' resolvido HOJE,
  mas o numerador conta presenca de sessoes passadas — se a composicao da Casa mudou (vaga aberta/fechada)
  a razao crua pode passar de 100%; a vitrine de comprador (§16.11) mostra uma media, nunca 'mais que
  todo mundo presente'."
  [tx ente-id membros-da-casa teto]
  (let [sessoes (sessoes-encerradas-recentes tx ente-id teto)
        n-sessoes (count sessoes)
        total-presentes (reduce + 0 (map #(presentes-na-sessao tx ente-id (:id %) (:encerrada-em %)) sessoes))]
    {:media-percentual (when (and (pos? n-sessoes) (pos? membros-da-casa))
                         (-> (* 100.0 (/ total-presentes (* n-sessoes membros-da-casa)))
                             Math/round
                             int
                             (max 0)
                             (min 100)))
     :sessoes-consideradas n-sessoes
     :membros-da-casa membros-da-casa}))
