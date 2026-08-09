(ns oplenario.sessoes.db.chamada
  "Persistencia do ATO DE CHAMADA CONDUZIDA (§22.6 eixo C, Etapa 2d) — funcoes sobre a `tx` do tenant (RLS
  isola). `chamada_conduzida` e' APPEND-ONLY: registra QUANDO a chamada foi conduzida, QUEM a conduziu, e
  quantos MEMBROS A CASA tinha naquele instante (o denominador do quorum, CONGELADO — resolvido pelo
  Repo-Component DENTRO da tx via `logic/membros-da-casa-da-chamada`, sobre a MESMA uniao que a leitura
  publica; nunca contado aqui, e nunca recebido pronto da borda). Ver o cabecalho da migration 0072 para o
  porque de uma tabela propria em vez de estender `incidente_processual`. HoneySQL schema-qualified; ente_id
  em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.sessoes.logic :as logic]))

(set! *warn-on-reflection* true)

(def ^:private cols
  [:id :ente_id :sessao_id :conduzida_por :membros_da_casa :ocorrido_em :registrado_em])

(defn registrar!
  "Registra o ato de chamada conduzida (append-only). Valida `membros-da-casa` como GUARDA DE PROFUNDIDADE
  (fail-closed antes do INSERT — o Repo-Component ja' o computou nesta mesma tx, pela MESMA uniao que a
  leitura da chamada publica) +
  nil-guard de auditoria (`created-by`, trilha de quem conduziu). Devolve {:id :ocorrido-em :registrado-em}
  por RETURNING — o MESMO par (dominio/audit) de `presenca/registrar-evento!`: enquanto nao existir um tipo
  de evento de RETIFICACAO, e' a unica forma de distinguir 'a chamada aconteceu as 14h' de 'o secretario
  digitou as 14h20 um registro das 14h'."
  [tx {:keys [id ente-id sessao-id conduzida-por membros-da-casa ocorrido-em created-by]}]
  (logic/validar-membros-da-casa membros-da-casa)
  (when (nil? created-by)
    (throw (ex-info "registrar!: created-by e' obrigatorio (trilha de auditoria)" {:id id})))
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:insert-into :sessoes.chamada_conduzida
                  :values [{:id id :ente_id ente-id :sessao_id sessao-id :conduzida_por conduzida-por
                            :membros_da_casa membros-da-casa :ocorrido_em ocorrido-em
                            :created_by created-by :efetivado_em [:now]}]
                  ;; `membros_da_casa` volta no RETURNING (revisao): o denominador deixou de ser computado
                  ;; pelo caller e passou a sair de DENTRO da tx (sobre a mesma uniao que a leitura publica),
                  ;; entao o recibo so' pode dizer a verdade lendo-o de volta — nunca ecoando o que entrou.
                  :returning [:id :ocorrido_em :registrado_em :membros_da_casa]}))))

(defn contar-da-sessao
  "Quantos atos de chamada esta sessao ja registrou. Lido DENTRO da tx da escrita, ao lado do gate de estado,
  para o teto (`logic/teto-de-atos-de-chamada`) ser um invariante e nao um conselho: a tabela e' append-only
  e a mig 0072 nao concede DELETE, entao uma linha a mais e' permanente. Servido pelo
  `idx_chamada_conduzida_sessao (ente_id, sessao_id, ocorrido_em)`."
  [tx ente-id sessao-id]
  (:n (jdbc/execute-one! tx
        (sql/format {:select [[[:count :*] :n]] :from [:sessoes.chamada_conduzida]
                     :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]}))))

(defn ato-recente-do-ator
  "O ato mais recente DESTA sessao conduzido por `conduzida-por` a partir de `desde`, ou nil. E' o insumo da
  DEDUPLICACAO: a rota nao tem corpo, entao o cliente nao tem como sinalizar 'e' o mesmo ato', e um duplo
  clique gravaria dois atos indistinguiveis de uma reconducao regimental. `conduzida_por` entra na chave
  porque dois membros da Mesa conduzindo em sequencia sao dois atos de verdade."
  [tx ente-id sessao-id conduzida-por desde]
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select cols :from [:sessoes.chamada_conduzida]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]
                          [:= :conduzida_por conduzida-por] [:>= :ocorrido_em desde]]
                  :order-by [[:ocorrido_em :desc] [:id :desc]]
                  :limit 1}))))

(defn listar-da-sessao
  "Os atos de chamada conduzida da sessao, em ordem cronologica. Podem ser MAIS DE UM (decisao Etapa 2d /
  A5): a chamada pode ser reconduzida na mesma sessao (apos suspensao, ou para reverificar quorum a pedido
  da Mesa — mesmo racional de `incidente_processual.tipo = 'verificacao_votacao'`), cada ato com o SEU
  denominador congelado. Uma lista VAZIA e' o sinal de 'ninguem conduziu a chamada ainda' — distinto de uma
  lista com um ato e zero presenca_evento ('a chamada aconteceu, a Casa toda faltou').

  LIMIT explicito (`logic/teto-de-atos-de-chamada`): a leitura NUNCA pode depender da cardinalidade da
  escrita. O resultado inteiro e' embutido em `GET /sessoes/:id/chamada` — o endpoint do telao do M4 — e
  passa por tres materializacoes em heap (`linhas->kebab`, `mapv chamada-conduzida->wire`, `m/validate` de um
  `[:sequential ChamadaConduzidaOut]`) num monolito COMPARTILHADO por todos os tenants. O teto de ESCRITA
  (validado na tx de `registrar-chamada-conduzida!`) ja impede a lista de crescer; este LIMIT e' a segunda
  tranca, para uma linha entrada por outro caminho (import de acervo legado) nunca virar OOM da Casa vizinha."
  [tx ente-id sessao-id]
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select cols :from [:sessoes.chamada_conduzida]
                  :where [:and [:= :ente_id ente-id] [:= :sessao_id sessao-id]]
                  :order-by [[:ocorrido_em :asc] [:id :asc]]
                  :limit logic/teto-de-atos-de-chamada}))))
