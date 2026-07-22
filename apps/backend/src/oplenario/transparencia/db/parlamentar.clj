(ns oplenario.transparencia.db.parlamentar
  "Persistencia das projecoes de ATUACAO PARLAMENTAR do portal (Onda E fatia 2, mig 0064) — voto PUBLICO e
  presenca. Funcoes sobre a `tx` corrente (FORCE RLS isola). ESCRITA chamada pelo consumer dentro da tx do
  relay; LEITURA pelo Repo-Component. Voto SECRETO nunca chega aqui: o payload do evento (uniao discriminada
  por :modalidade) nem carrega identidade no ramo secreto."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

(def ^:private teto-votos
  "Teto server-side da secao 'como votou' (anti unbounded-read; mesmo racional dos tetos de materia/comentario)."
  50)

(defn registrar-voto!
  "Projeta um voto NOMINAL. ON CONFLICT DO NOTHING: idempotente sob redrive (a chave e' de negocio, nao a
  idempotency-key do envelope)."
  [tx {:keys [ente-id votacao-id vereador-id proposicao-id voto ocorrido-em]}]
  {:pre [(some? ente-id) (some? votacao-id) (some? vereador-id) (some? voto) (some? ocorrido-em)]}
  (jdbc/execute-one! tx
    (sql/format {:insert-into :transparencia.voto_parlamentar
                 :values [{:ente_id ente-id :votacao_id votacao-id :vereador_id vereador-id
                           :proposicao_id proposicao-id :voto voto :ocorrido_em ocorrido-em}]
                 :on-conflict [:ente_id :votacao_id :vereador_id]
                 :do-nothing []})))

(defn registrar-presenca!
  "Projeta o ESTADO ATUAL de presenca por (sessao, vereador). UPSERT: o evento e' log de entrada/saida, a
  vista publica quer o ultimo. `ocorrido_em` do DOMINIO decide — um evento fora de ordem no redrive nao
  sobrescreve um mais recente (mesmo gate de monotonicidade de paineis/db/sli_sessao)."
  [tx {:keys [ente-id sessao-id vereador-id tipo modalidade ocorrido-em]}]
  {:pre [(some? ente-id) (some? sessao-id) (some? vereador-id) (some? tipo) (some? ocorrido-em)]}
  (jdbc/execute-one! tx
    (sql/format {:insert-into :transparencia.presenca_parlamentar
                 :values [{:ente_id ente-id :sessao_id sessao-id :vereador_id vereador-id
                           :tipo tipo :modalidade modalidade :ocorrido_em ocorrido-em}]
                 :on-conflict [:ente_id :sessao_id :vereador_id]
                 :do-update-set {:fields {:tipo :excluded.tipo :modalidade :excluded.modalidade
                                          :ocorrido_em :excluded.ocorrido_em}
                                 :where [:< :transparencia.presenca_parlamentar.ocorrido_em :excluded.ocorrido_em]}})))

(defn votos-do-vereador
  "Secao 'como votou': votos PUBLICOS do vereador, mais recentes primeiro (desempate por votacao_id — achado
  M-6, revisao Task 2: `ocorrido_em` vem de `registrado_em DEFAULT now()`, o instante de INICIO da tx, entao
  votos proximos podem empatar; sem desempate estavel a ordem fica nao-deterministica assim que a Task 3
  paginar), com a ementa da materia (mesmo schema — JOIN permitido, nao e' cross-schema)."
  [tx ente-id vereador-id limite]
  {:pre [(some? ente-id) (some? vereador-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx
     (sql/format {:select [:v.votacao_id :v.proposicao_id :v.voto :v.ocorrido_em
                           [:m.tipo :materia_tipo] [:m.ano :materia_ano]
                           [:m.sequencial :materia_sequencial] [:m.ementa :materia_ementa]]
                  :from [[:transparencia.voto_parlamentar :v]]
                  :left-join [[:transparencia.materia :m]
                              [:and [:= :m.ente_id :v.ente_id] [:= :m.proposicao_id :v.proposicao_id]]]
                  :where [:and [:= :v.ente_id ente-id] [:= :v.vereador_id vereador-id]]
                  :order-by [[:v.ocorrido_em :desc] [:v.votacao_id :desc]]
                  ;; achado I-3 (revisao Task 2): teto RIGIDO — `(or limite teto-votos)` deixava o CHAMADOR
                  ;; passar um limite MAIOR que o teto (so' usava teto-votos quando limite era nil). `min`
                  ;; capa de verdade, mesmo precedente de db/materia.clj:listar-em-tramitacao et al.
                  :limit (min (or limite teto-votos) teto-votos)}))))

(defn contar-votos-do-vereador
  "Universo INTEIRO da secao 'como votou' — o denominador de `votos-do-vereador`, que trunca em
  `teto-votos` (50). Sem este numero a borda nao tem como dizer 'mostrando 50 de N' e o `:closed` do wire
  fecha qualquer outra via de o cliente descobrir o truncamento (achado C-4, revisao Task 4). Mesmo par
  lista+total de `db/materia/listar-por-autor`+`contar-por-autor`. Sem teto de proposito: e' um
  `count(*)` servido pelo prefixo (ente_id, vereador_id) de `idx_voto_parlamentar_vereador`."
  [tx ente-id vereador-id]
  {:pre [(some? ente-id) (some? vereador-id)]}
  (:contagem
   (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [[[:count :*] :contagem]]
                   :from [:transparencia.voto_parlamentar]
                   :where [:and [:= :ente_id ente-id] [:= :vereador_id vereador-id]]})))))

(defn resumo-presenca
  "Numero-card de presenca. Devolve os DOIS numeros — a UI mostra a fracao, nunca um percentual sem
  denominador (um 100% de 1 sessao mente por omissao).

  DENOMINADOR = sessoes do ENTE INTEIRO que tiveram chamada (COUNT DISTINCT sessao_id). Ele NAO tem
  `vereador_id` no predicado, e isso e' deliberado nos DOIS sentidos: e' o que faz o faltoso cronico publicar
  '0 de 40' em vez de sumir num '0 de 0' — e e', ao mesmo tempo, o carry I-5 AINDA ABERTO, porque o
  denominador tambem nao e' recortado pela janela de exercicio do mandato. Ou seja: HOJE o suplente
  convocado para 3 sessoes recebe o denominador da legislatura inteira. A janela de mandato entra na fatia 6
  do plano do I-5; ate' la' o recorte publicado e' o do ente inteiro, e nao ha como esta funcao saber
  diferente (§22.10 proibe `transparencia` de importar `cadastros`).

  NUMERADOR = as sessoes DESSE MESMO conjunto em que ELE tem linha em `presenca_parlamentar`, via
  `FILTER (WHERE vereador_id = ?)` — os dois agregados sobre o MESMO argumento `sessao_id`. TER LINHA ==
  COMPARECEU, e a AUSENCIA DE FILTRO POR `tipo` E' DELIBERADA: ausencia nunca e' gravada ('sem evento ate' la'
  = ausente', `sessoes/relacoes/presenca`), nao existe chamada em lote, e os QUATRO tipos do vocabulario real
  (entrada|saida|retorno|mudanca_modalidade, `sessoes/logic` + CHECK da mig 0029) implicam que a pessoa foi
  registrada naquela sessao — inclusive 'saida', que so' existe depois de uma entrada. Ate' a Onda E/fatia 1
  o predicado aqui era `tipo = 'presente'`, valor que PRODUTOR NENHUM emite: o numerador valia ZERO para todo
  parlamentar em producao, com o denominador cheio. Nao reintroduzir o filtro sem antes derrubar
  `numerador-conta-sessao-cujo-unico-evento-projetado-e-saida` — e' um numero publico e nominal.

  Numerador <= denominador por construcao (mesma tabela, mesmo argumento, predicado so' restringe)."
  [tx ente-id vereador-id]
  {:pre [(some? ente-id) (some? vereador-id)]}
  (comum/linha->kebab
   (jdbc/execute-one! tx
     (sql/format {:select [[[:count [:distinct :sessao_id]] :sessoes_com_chamada]
                           [[:filter [:count [:distinct :sessao_id]]
                             {:where [:= :vereador_id vereador-id]}]
                            :sessoes_presente]]
                  :from [:transparencia.presenca_parlamentar]
                  :where [:= :ente_id ente-id]}))))
