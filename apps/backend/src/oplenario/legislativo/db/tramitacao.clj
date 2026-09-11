(ns oplenario.legislativo.db.tramitacao
  "Persistencia + ENGINE da tramitacao por motor declarativo (eixo C). O regimento e' DADO
  (template/estado/transicao); `transicionar!` carrega as transicoes candidatas (de_estado+gatilho),
  avalia o GUARD de cada uma com o MESMO avaliador do motor (disciplina 5, motor/api/guarda-dsl) e, na
  primeira que passa, grava o historico (append-only) + muda o estado da proposicao — tudo na MESMA tx.
  Importa db/proposicao (db->db mesmo modulo) e motor/api (modulo->motor) — ambos permitidos (§22.10/§3-bis).
  Acao (handler em codigo) e' GRAVADA mas a execucao rica fica p/ F3.3b; aqui o efeito e' a mudanca de estado."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.legislativo.logic :as logic]
            [oplenario.motor.api :as motor]))

(set! *warn-on-reflection* true)

;; ---- montagem do template (config tenant; usada no onboarding/fixture/import) ----
(defn criar-template!
  "Persiste um template de tramitacao. `:sujeito` ('proposicao' default | 'parecer') e' o discriminador
  do tipo de entidade que o template governa (F3.6a): as tabelas de template sao subject-agnosticas, o
  sujeito e' validado no service do sujeito (ex.: parecer/criar! recusa template de 'proposicao'). Omitir
  = 'proposicao' (preserva os callers do eixo C).

  `:tipo` (mig 0077) e' a ESPECIE DE MATERIA que este rito governa — e' por ele que
  `db/proposicao/resolver-rito!` escolhe o rito de uma materia nova. Omitir = rito GENERICO da Casa (o
  que vale para as especies nao particularizadas); e' o que todo acervo existente tem hoje.

  GATEIA O VOCABULARIO NO SAVE, mesma disciplina de `criar-transicao!` com o guard: especie fora de
  `logic/tipos` NAO entra no banco. Sem este gate um typo ('projeto-lei' com hifen) produziria um rito
  que nunca casa especie nenhuma — e o sintoma seria SILENCIOSO na outra ponta (materia nascendo sem
  rito, que e' um desfecho legitimo), em vez de uma recusa no momento de configurar. O CHECK da mig 0077
  cuida do outro par incoerente (especie num template de parecer); aqui o gate e' de vocabulario, que
  deliberadamente NAO foi duplicado em SQL (ja' mora em `logic/tipos` e no CHECK da mig 0013).

  TEMPLATE NASCE SEMPRE ATIVO. `:ativo` foi REMOVIDO do contrato: o versionamento de rito e' por COPIA
  INTEGRAL (mig 0016), e a unica transicao de `ativo` que o produto suporta e' a baixa —
  `aposentar-template!`. Deixar o nascimento escolher `ativo` fabricava um estado que nada mais no
  produto podia produzir NEM desfazer (nao ha' caminho de re-ativacao), e um teste chegou a validar esse
  estado fantasma. Quem troca de versao faz `aposentar-template!` + `criar-template!` NA MESMA tx — a
  janela entre os dois com zero ritos ativos nao pode existir, porque nela as materias nasceriam sem
  rito em silencio."
  [tx {:keys [id ente-id chave versao nome estado-inicial sujeito tipo template-pai-id]}]
  (let [sujeito (or sujeito "proposicao")]
    ;; fail-closed: quem nao decide, NEGA (mesma postura do gate de guard logo abaixo).
    (when (and (some? tipo) (not (contains? logic/tipos tipo)))
      (throw (ex-info "especie desconhecida no template de tramitacao (rejeitada no save)"
                      {:tipo :validacao/invalido :especie tipo :chave chave})))
    (when (and (some? tipo) (not= "proposicao" sujeito))
      (throw (ex-info "template de sujeito != 'proposicao' nao governa especie de materia"
                      {:tipo :validacao/invalido :especie tipo :sujeito sujeito :chave chave})))
    (jdbc/execute-one! tx
      (sql/format {:insert-into :legislativo.template_tramitacao
                   :values [{:id id :ente_id ente-id :chave chave :versao (or versao 1) :nome nome
                             :estado_inicial estado-inicial :sujeito sujeito :tipo tipo
                             :template_pai_id template-pai-id :efetivado_em [:now]}]}))))

(defn aposentar-template!
  "Baixa `ativo` de UM template do tenant — A VALVULA que a resolucao automatica de rito PRESSUPOE.

  Por que ela precisou existir: o versionamento de template e' por COPIA INTEGRAL (mig 0016), entao a
  primeira Casa que bumpa o proprio rito passa a ter DUAS linhas com a mesma especie. `resolver-rito!`
  so' olha as ATIVAS justamente por isso — mas ate' aqui nenhum codigo de `src/` jamais escrevia
  `ativo = false`: a valvula nao existia no produto e mesmo assim um teste a validava, com uma fixture
  fabricando um template que ja' nascia aposentado. Ou a valvula passava a existir, ou a dependencia em
  `ativo` tinha de sair. Ela existe agora.

  Idempotente por construcao (`WHERE ativo` — reaplicar nao e' erro) e devolve `true` somente quando ESTA
  chamada foi a que aposentou. Nao ha' caminho de RE-ativacao, e a ausencia e' deliberada: rito aposentado
  que volta a valer significaria materias nascendo sob um rito que a Casa ja' revogou. O caminho de
  troca e' criar a versao nova, nao ressuscitar a velha."
  [tx {:keys [ente-id id]}]
  (some?
   (jdbc/execute-one! tx
     (sql/format {:update :legislativo.template_tramitacao
                  :set {:ativo false}
                  :where [:and [:= :ente_id ente-id] [:= :id id] [:= :ativo true]]
                  :returning [:id]}))))

(defn criar-estado! [tx {:keys [id ente-id template-id chave nome terminal ordem]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.template_estado
                 :values [{:id id :ente_id ente-id :template_id template-id :chave chave :nome nome
                           :terminal (boolean terminal) :ordem (or ordem 0) :efetivado_em [:now]}]})))

(defn criar-transicao!
  "Persiste uma transicao do template. GATEIA no save (Inv.4, motor/validar-guarda) AS DUAS expressoes —
  `guarda` e `autorizacao` (3-A) — pelo mesmo criterio: expressao que nao parseia NAO entra no banco, e a
  falha sai do caminho critico (rejeitada na config, nao no meio de um fluxo). Lanca ex-info
  :guarda-invalida / :autorizacao-invalida com a causa do erro de sintaxe.

  AS DUAS PERGUNTAS SAO DIFERENTES, e e' por isso que sao duas colunas e nao uma:
    · `guarda`      — 'ISTO ACONTECEU?'  Fato sobre o mundo: houve votacao aprovada, o prazo correu, a
                      comissao opinou. Nao fala do ator. (3-B)
    · `autorizacao` — 'VOCE PODE DECLARAR QUE ACONTECEU?'  Fato sobre QUEM pede: e' o presidente da Mesa,
                      e' o relator da materia, exerce a presidencia hoje. Nao fala do mundo. (3-A)
  Colapsa-las numa expressao so' obrigaria a Casa a repetir a condicao de fato em cada regra de pessoa (e
  vice-versa), e faria a recusa perder a causa: 'a Casa nao permite agora' e 'voce nao pode' sao respostas
  diferentes, para pessoas diferentes, com consertos diferentes."
  [tx {:keys [id ente-id template-id de-estado para-estado gatilho guarda autorizacao acao ordem]}]
  ;; fail-closed: SO "VALIDA" passa — qualquer outro status (incl. valor inesperado) REJEITA o save
  ;; (quem nao decide, NEGA; mesma postura do check! de authz). String casa a convencao de verificar-fonte.
  (let [{:keys [status erros]} (motor/validar-guarda guarda)]
    (when (not= "VALIDA" status)
      (throw (ex-info "guard da transicao mal-formado (rejeitado no save, Inv.4)"
                      {:erro :guarda-invalida :de-estado de-estado :gatilho gatilho :erros erros}))))
  ;; MESMO gate p/ a autorizacao. `validar-guarda` e' o validador de expressao BOOLEANA da DSL — nao e'
  ;; especifico de guard —, entao reusa-lo aqui e' a disciplina 5 e nao um atalho: uma expressao de
  ;; autorizacao mal-formada que entrasse no banco so' falharia no meio de uma sessao, e `check!` traduz
  ;; lance em NEGACAO — ou seja, config quebrada viraria "ninguem pode tramitar", descoberto no pior
  ;; momento possivel.
  (let [{:keys [status erros]} (motor/validar-guarda autorizacao)]
    (when (not= "VALIDA" status)
      (throw (ex-info "expressao de autorizacao da transicao mal-formada (rejeitada no save, Inv.4)"
                      {:erro :autorizacao-invalida :de-estado de-estado :gatilho gatilho :erros erros}))))
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.template_transicao
                 :values [{:id id :ente_id ente-id :template_id template-id :de_estado de-estado
                           :para_estado para-estado :gatilho gatilho :guarda guarda
                           :autorizacao autorizacao :acao acao
                           :ordem (or ordem 0) :efetivado_em [:now]}]})))

(defn transicoes-de
  "Transicoes candidatas (ordenadas) de `de-estado` sob `gatilho` no template."
  [tx ente-id template-id de-estado gatilho]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:id :ente_id :template_id :de_estado :para_estado :gatilho :guarda :autorizacao
                         :acao :ordem]
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
      (sql/format {:select [:id :de_estado :para_estado :gatilho :guarda :autorizacao :acao :ordem]
                   :from [:legislativo.template_transicao]
                   :where [:and [:= :ente_id ente-id] [:= :template_id template-id]
                           [:= :de_estado de-estado]]
                   :order-by [[:ordem :asc] [:id :asc]]}))))

;; `estado-no-template` e `estado+lock+rito` DESCERAM p/ `db/proposicao.clj` (ver as docstrings de la').
;; Motivo: a mesma consulta passou a ter tres leitores — esta engine, o guard de `proposicao/editar!` e a
;; leitura composta do Repo — e o guard nao podia requerer ESTE ns sem fechar um ciclo (tramitacao ->
;; proposicao ja' existe, e o inverso nao pode). Chamadas aqui pelo nome qualificado, sem alias local: a
;; consulta tem UM dono, e um alias so' esconderia de quem le' a engine onde ela mora.

(defn registrar-transicao!
  "Append-only: grava a transicao OCORRIDA no historico (a prova duravel, Inv.10). RETURNING `ocorrido_em`
  (F7 carry): o carimbo REAL da transicao, devolvido p/ `transicionar!` incluir no evento de dominio
  (proposicao.transicionou :ocorrido-em) — a jusante, paineis/tramitacao-board usa isto em vez do momento em
  que o consumer PROJETA, evitando que atraso comum do relay resete o sinal de estagnacao.

  `:contexto` e' a carga do gatilho como ela chegou — `transicionar!` passa aqui o `alegado` (fatia 4). Os
  dois nomes sao deliberados e nao se contradizem: no AMBIENTE DE AVALIACAO o nome tem de declarar
  CONFIANCA (`alegado` = o que o operador afirma), na AUDITORIA tem de declarar CONTEUDO (`contexto` = a
  carga do ato, guardada como veio). A coluna nao foi renomeada porque e' append-only e espelha
  `parecer_transicao_historico`; renomea-la nao tornaria nenhum guard mais legivel."
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

(defn transicionar!
  "ENGINE da tramitacao (eixo C). Do estado ATUAL da proposicao, sob `gatilho`, escolhe a 1a transicao
  candidata cujo GUARD passa (guard nil = sempre passa; senao avalia via motor/guarda-dsl com a proposicao
  no `amb`) e aplica: historico append-only + muda o estado (CAS por lock_version). Atomico na tx (o caller
  abre via Repo/transacao, passando o RegistroFatos do motor). Devolve {:transicionou? bool :de :para
  :transicao-id} — nao transicionar e' resultado NORMAL de dominio, nao erro.
  DISTINTO disso: guard que LANCA (fato ausente no registry, tipo nao-booleano no runtime — os dois
  `{:erro :runtime}`, ver `motor/api/exigir-booleano!`) e o CAS de lock_version (conflito de escrita)
  PROPAGAM como excecao — o controller distingue: resultado = dominio normal; excecao = erro de
  avaliacao/conflito. O `tipo nao-booleano LANCA` era FALSO ate' a fatia 4: o seam do motor fechava com
  `(boolean …)`, que converte em vez de checar, e um guard que avaliasse para string/UUID/numero passava
  como `true`. Fail-ABERTO num ponto que so' existe p/ negar. Hoje a frase e' verdadeira, e a prova de
  que ela pode reprovar esta' em `guarda-nao-booleano-falha-FECHADO` (tramitacao-db-test).

  OS DOIS CANAIS DO `amb`, e por que eles tem NOMES diferentes (fatia 4). O guard e' uma regra do tenant
  lendo o mundo, e nem todo pedaco desse mundo vale o mesmo:

    · `proposicao`  -> a LINHA, lida pelo servidor nesta tx sob FOR UPDATE. Verdade apurada.
    · fatos por nome (`aprovada_em_votacao(proposicao.id)`, …) -> resolvidos pelo RegistroFatos contra a
      tx do tenant. Verdade apurada — e' o canal que a decisao 3-B abriu.
    · `alegado`     -> o corpo do POST. E' o que o OPERADOR AFIRMA, e nada mais que isso.

  Ate' aqui o terceiro canal chamava-se `contexto`, um nome neutro que o punha no mesmo plano dos outros
  dois. Com um rito que declarasse `contexto.parecer_favoravel == verdadeiro` na saida de 'em_comissoes',
  o secretario mandava um corpo com `contexto: {parecer_favoravel: true}` e AFIRMAVA A PROPRIA
  PRECONDICAO — pulava a comissao por escrito, com a bencao do rito. O guard continuava tecnicamente
  correto; quem escreveu o rito e' que nao tinha como ver, olhando para ele, que estava confiando no
  cliente. O nome passa a dizer: `alegado.x` e' alegacao, `proposicao.x` e fato-por-nome sao apuracao.

  Isto NAO proibe ler o cliente — ha' uso legitimo (escolher destino por `alegado.comissao`, carimbar
  quem pediu). Proibir seria decidir pelo regimento, que e' justamente o que o Inv.4 veda. O que a fatia
  entrega e' que a escolha fique VISIVEL na propria expressao. E ela e' MECANICA, nao convencao: a chave
  `contexto` sumiu do `amb`, entao um rito antigo que a referencie nao le' silenciosamente o corpo do
  cliente — o avaliador lanca `{:erro :runtime}` (identificador sem valor) e a materia NAO tramita.
  O corpo HTTP e a coluna `proposicao_transicao_historico.contexto` seguem com o nome antigo de proposito:
  o primeiro descreve a carga do ato, a segunda e' auditoria append-only compartilhada com o engine do
  parecer, e renomear um deles nao tornaria nenhum guard mais legivel — a troca de nome vale no ponto em
  que a confianca e' decidida, que e' o `amb`.

  QUEM DIZ O QUE E' TERMINAL E' O RITO (Fatia 2). Ate' a mig 0078 quem dizia era o SQL: o trigger
  `trg_proposicoes_imut_estado` (mig 0013) cravava `imut_trava_estado_terminal('publicada','arquivada')` —
  duas palavras de camara em codigo, Inv.4 violado no schema, e o DESARQUIVAMENTO (ato corriqueiro de
  praticamente todo regimento) impossivel mesmo com o rito declarando a transicao. A trava saiu do banco e
  entrou AQUI, vinda do DADO: `template_estado.terminal` do estado ATUAL. O campo era DECORATIVO na escrita
  — so' a leitura (fatia 3) o consultava, e o anunciava ao cliente como se fosse garantia.

  [CUSTO DECLARADO — a protecao e' tao forte quanto esta fn ser o UNICO escritor] Com a trava fora do
  banco nao ha' mais rede embaixo: qualquer outro caminho que escreva `proposicoes.estado` sai por baixo da
  declaracao do rito, e em SILENCIO. Verificado nesta fatia: os escritores de `proposicoes.estado` sao
  `db/proposicao/protocolar!` (INSERT, nasce no `estado_inicial` do rito) e `db/proposicao/mudar-estado!`,
  cujo unico chamador em `src/` e' esta funcao — o outro, `RepoLegislativo/mudar-estado-proposicao!`, nao
  tinha um so' chamador e foi REMOVIDO do protocolo na mesma fatia, para que a verificacao nao dependesse de
  ninguem reler esta docstring. Quem adicionar um escritor novo tem de trazer a checagem junto.

  OS QUATRO DESFECHOS DE `{:transicionou? false}` vem com `:motivo`, porque pedem acoes DIFERENTES de quem
  recebe e colapsa-los numa frase so' ('a Casa nao permite este ato agora') e' informacao inutil:
    · `:estado-terminal`       -> o rito declara TERMINAL o estado atual. A Casa ENCERROU o processo; nao
                                  ha' o que esperar nem outro ato a tentar. Recusado ANTES de olhar
                                  candidata ou avaliar guard (fim de rito vence transicao declarada — se o
                                  rito diz as duas coisas, a config e' incoerente e a leitura fail-closed).
    · `:guarda-recusou`        -> ha' candidata declarada, o guard e' que negou AGORA. Pode passar depois,
                                  ou com o `contexto` certo. E' o unico dos quatro que muda com o tempo.
    · `:gatilho-nao-declarado` -> o rito conhece o estado mas nao declara ESTE ato a partir dele. Tentar de
                                  novo nunca funciona; procure outro ato (a fatia 3 lista os possiveis).
    · `:estado-fora-do-rito`   -> o rito nem declara o estado atual (materia anterior ao rito, ou rito
                                  trocado sob os pes dela — o versionamento e' por copia integral, mig
                                  0016). Alguem tem de reconciliar rito e estado na CONFIG.
  O ultimo e' DIAGNOSTICO, nunca recusa: `template_transicao.de_estado` nao tem FK p/ `template_estado.chave`
  (mig 0016), entao um rito PODE declarar transicao a partir de um estado que nao listou, e recusar ali
  quebraria rito ja' em producao. `(:terminal nil)` e' nil — estado desconhecido nao e' terminal."
  [tx {:keys [registro ente-id proposicao-id template-id gatilho ator ator-id alegado agora updated-by]}]
  (let [linha (proposicao/estado+lock+rito tx ente-id proposicao-id)
        ;; fail-closed (Fatia 2; ESPELHO do fix que `transicionar-parecer!` ganhou na review F3.6a — o
        ;; [CARRY disc.6] daquele ns pede paridade explicita entre os dois sujeitos). Materia inexistente
        ;; no tenant lia estado `nil`, nao casava candidata nenhuma e saia como `{:transicionou? false}` —
        ;; que o contrato define como "o guard bloqueou". A borda entao responderia "a Casa nao permite
        ;; este ato agora" sobre uma materia que NAO EXISTE: resposta plausivel, confiante e errada.
        _ (when (nil? linha)
            (throw (ex-info "transicionar!: proposicao inexistente no tenant"
                            {:tipo :conflito/transicao :proposicao-id proposicao-id :ente-id ente-id})))
        ;; O RITO DO ARGUMENTO TEM DE SER O RITO DA LINHA (fatia 4). Ate' a mig 0076 a materia nao tinha
        ;; onde guardar o proprio rito e `template-id` SO' podia vir do caller — o argumento era a unica
        ;; fonte, e conferi-lo era impossivel. Com a coluna, ele passou a ser uma SEGUNDA fonte da mesma
        ;; verdade, e duas fontes que ninguem casa e' um caminho para a materia andar por um rito que nao
        ;; e' o dela: as candidatas viriam do template errado, o guard avaliaria a regra de outra especie,
        ;; e o historico gravaria esse template — `proposicao_transicao_historico.template_id` NAO tem FK
        ;; que amarre ao da materia, so' ao tenant, entao nem o banco barraria depois.
        ;;
        ;; Nao e' paranoia sobre o caller de hoje (o controller ja' injeta `(:template-id linha)`): e' que
        ;; a protecao de `transicionar!` ser o UNICO escritor de `proposicoes.estado` — declarada logo
        ;; acima — vale exatamente enquanto ninguem entrar por aqui com o rito errado. Quem chamar a engine
        ;; com outro template recebe EXCECAO, nao um `{:transicionou? false}` que se disfarcaria de recusa
        ;; de dominio. Sem tag de `:conflito/*`: nenhuma borda HTTP pode produzir isto (o corpo nao aceita
        ;; `template-id`, wire :closed), entao e' bug de programacao do lado do servidor e o desfecho certo
        ;; e' 500 — o que a borda NAO faria e' devolver 409 dizendo que a Casa nao permite o ato.
        _ (when (not= template-id (:template-id linha))
            (throw (ex-info "transicionar!: o rito passado nao e' o rito DESTA materia (template divergente)"
                            {:erro :rito-divergente :proposicao-id proposicao-id :ente-id ente-id
                             :template-id-argumento template-id :template-id-da-linha (:template-id linha)})))
        {:keys [estado lock-version]} linha
        ;; A DECLARACAO do estado atual NO RITO — a fonte da trava que saiu do SQL (mig 0078). Nao ha'
        ;; `case`/`when` sobre nome de estado aqui e nao pode haver: a unica pergunta e' o que o DADO diz.
        estado-declarado (proposicao/estado-no-template tx ente-id template-id estado)]
    (if (:terminal estado-declarado)
      ;; fim de rito: nem carrega candidata nem avalia guard. Avaliar guard aqui gastaria I/O de fato p/
      ;; decidir o que ja' esta' decidido, e um guard que LANCA transformaria "o processo acabou" (dominio
      ;; normal, 409) em incidente de config (500) — trocando o diagnostico certo pelo errado.
      {:transicionou? false :motivo :estado-terminal :de estado :gatilho gatilho}
      (let [candidatas (transicoes-de tx ente-id template-id estado gatilho)
            passa? (fn [t]
                     (or (nil? (:guarda t))
                         ((motor/guarda-dsl {:registro registro :tx tx :expr (:guarda t)
                                             :agora agora :ente-id ente-id})
                          ;; DOIS CANAIS, e o NOME diz de qual: `proposicao` (a linha, lida pelo servidor
                          ;; sob FOR UPDATE) e os FATOS de relacao sao verdade apurada; `alegado` e' o
                          ;; corpo do POST — o que o operador AFIRMA. Ver a secao "OS DOIS CANAIS" na
                          ;; docstring. `contexto` NAO existe mais neste mapa, de proposito.
                          {"proposicao" {:id proposicao-id :estado estado} "alegado" (or alegado {})})))
            escolhida (first (filter passa? candidatas))]
        (cond
          (some? escolhida)
          ;; 3-A — QUEM pode disparar. Roda DEPOIS de escolher a candidata e ANTES de aplicar, e as duas
          ;; metades dessa frase foram decididas, nao herdadas:
          ;;
          ;; DEPOIS de escolher, nao como filtro junto do guard: se a autorizacao filtrasse candidatas, um
          ;; ator sem permissao na 1a transicao CAIRIA na 2a — o destino da materia passaria a depender de
          ;; QUEM pediu, e duas pessoas com o mesmo gatilho levariam a materia a estados diferentes. Quem
          ;; escolhe o destino e' o rito (`ordem`); a autorizacao e' porta sobre o destino escolhido, nunca
          ;; desempate. Sem permissao na escolhida = NEGA, ponto — nao tenta a proxima.
          ;;
          ;; ANTES de aplicar e DENTRO desta tx, contra o snapshot ja' travado por `estado+lock+rito`
          ;; (`FOR UPDATE`): e' o achado CRITICO da Onda C3 (authz-tx != write-tx). Autorizar fora da tx da
          ;; escrita deixa a janela em que o mundo muda entre o "pode" e o "fez" — la' era a Mesa encerrar a
          ;; votacao no meio; aqui seria o mandato cair, a comissao ser recomposta ou a presidencia mudar
          ;; entre autorizar e gravar.
          ;;
          ;; `check!` do kernel traduz LANCE em NEGACAO (`:tipo :autorizacao/negado` -> 403 no interceptor):
          ;; expressao que nao consegue decidir NEGA, nunca fica indeterminada. E' a mesma postura do gate
          ;; do save acima e do `passa?` do guard.
          (do
            ;; `str/blank?`, nao `when-let` (achado IMPORTANTE-7): `validar-guarda ""` devolve VALIDA — o
            ;; gate do save deixa passar string vazia —, e `""` e' TRUTHY em Clojure, entao o `when-let`
            ;; disparava e `parse-expr ""` lancava. Um `autorizacao = ''` vindo de import ou de formulario
            ;; que mande vazio em vez de NULL transformava aquele gatilho em 500 PERMANENTE, descoberto no
            ;; meio de uma sessao — exatamente o que o gate do save existe para evitar.
            (when-not (str/blank? (:autorizacao escolhida))
              (let [expr (:autorizacao escolhida)]
              (authz/check! ator :legislativo/tramitar
                            {:tipo "proposicao" :id proposicao-id :estado estado
                             :gatilho gatilho :para (:para-estado escolhida)}
                              (motor/politica-dsl {:registro registro :tx tx :expr expr :agora agora}))))
            (let [{:keys [ocorrido-em]} (registrar-transicao! tx {:id (random-uuid) :ente-id ente-id
                                                                  :proposicao-id proposicao-id
                                                                  :template-id template-id :de-estado estado
                                                                  :para-estado (:para-estado escolhida)
                                                                  :gatilho gatilho :contexto alegado
                                                                  :ator-id ator-id})]
              (proposicao/mudar-estado! tx {:id proposicao-id :ente-id ente-id
                                            :estado (:para-estado escolhida)
                                            :updated-by updated-by :lock-version lock-version})
              {:transicionou? true :de estado :para (:para-estado escolhida) :transicao-id (:id escolhida)
               :acao (:acao escolhida) :ocorrido-em ocorrido-em}))

          ;; havia ato declarado e o guard negou — o unico dos desfechos que o TEMPO pode mudar.
          (seq candidatas)
          {:transicionou? false :motivo :guarda-recusou :de estado :gatilho gatilho}

          ;; sem candidata: ou o rito nao declara ESTE ato aqui, ou nao declara o ESTADO. Coisas diferentes.
          (nil? estado-declarado)
          {:transicionou? false :motivo :estado-fora-do-rito :de estado :gatilho gatilho}

          :else
          {:transicionou? false :motivo :gatilho-nao-declarado :de estado :gatilho gatilho})))))
