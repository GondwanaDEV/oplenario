(ns oplenario.cadastros.db.vereador
  "Persistencia de vereador + mandato (entidade com estado, §22.5 eixo C) + licenca + suplencia.
  Funcoes sobre a `tx` do tenant (RLS isola). HoneySQL."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum])
  (:import (java.time LocalDate)))

(set! *warn-on-reflection* true)

;; ---- vereador (registro institucional; identidade-id = guard ref ao modulo identidade) ----
(defn inserir!
  ;; criacao NATIVA nasce efetivada (efetivado_em = now()); import (admin_sistema) e' que estaga. Fundacao #2.
  [tx {:keys [id ente-id identidade-id nome nome-parlamentar]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.vereador
                 :values [{:id id :ente_id ente-id :identidade_id identidade-id
                           :nome nome :nome_parlamentar nome-parlamentar :efetivado_em [:now]}]})))

(defn buscar
  "Defesa em profundidade (RLS ja isola por tenant): filtra tambem por `ente-id` explicito — o unico read
   de 1 vereador so' com `id` na aggregate; ambos os callers (repositorio.clj) ja tem `ente-id` em escopo."
  [tx ente-id id]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :ente_id :identidade_id :nome :nome_parlamentar]
                   :from [:cadastros.vereador]
                   :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn por-identidade
  "O vereador vinculado a uma identidade (CPF) neste ente — base da autorizacao por relacao (F2)."
  [tx ente-id identidade-id]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :ente_id :identidade_id :nome :nome_parlamentar]
                   :from [:cadastros.vereador]
                   :where [:and [:= :ente_id ente-id] [:= :identidade_id identidade-id]]}))))

(defn ligar-identidade!
  "Liga o vereador a' identidade (Onda D Slice 5 Task 9 — passo (2) do provisionamento; GUARD ref, sem FK
   cross-schema, §22.10 — a existencia de `identidade-id` e' checada pelo guard de servico injetado na
   borda, nunca aqui). `ente_id` no WHERE alem da RLS (defesa em profundidade, mesmo padrao de atualizar!/
   mudar-estado!). Idempotente (re-ligar a MESMA identidade e' um no-op valido). Devolve o update-count (0 =
   vereador inexistente/de-outro-tenant). Colisao com o indice UNIQUE parcial (outro vereador desta Casa ja'
   ligado a esta identidade) sobe como PSQLException 23505 — o Repo (components/repositorio.clj) converte
   p/ :conflito/identidade-ja-vinculada, nunca tratada aqui (mesmo padrao de inserir-mandato!/
   registrar-mandato!)."
  [tx ente-id id identidade-id]
  (:next.jdbc/update-count
   (jdbc/execute-one! tx
     (sql/format {:update :cadastros.vereador
                  :set {:identidade_id identidade-id}
                  :where [:and [:= :ente_id ente-id] [:= :id id]]}))))

(defn atualizar!
  "UPDATE de nome/nome-parlamentar da linha EFETIVADA do vereador. So' seta as chaves PRESENTES em `campos`
   (:nome / :nome-parlamentar) — um PATCH parcial nunca zera o campo que o cliente nao mandou. Devolve o
   update-count (0 = linha inexistente/outro-tenant, ja' filtrada pela RLS). Defesa em profundidade: casa
   ente_id explicito + efetivado_em NOT NULL (a RLS ja' esconde staging, isto e' cinto-e-suspensorio)."
  [tx ente-id id campos]
  (let [set-map (cond-> {}
                  (contains? campos :nome)             (assoc :nome (:nome campos))
                  (contains? campos :nome-parlamentar) (assoc :nome_parlamentar (:nome-parlamentar campos)))
        r (jdbc/execute-one! tx
            (sql/format {:update :cadastros.vereador
                         :set set-map
                         :where [:and [:= :ente_id ente-id] [:= :id id] [:is-not :efetivado_em nil]]}))]
    (:next.jdbc/update-count r)))

;; ---- mandato ----
(defn inserir-mandato!
  [tx {:keys [id ente-id vereador-id legislatura-id partido estado natureza
              vigencia-inicio vigencia-fim fim-efetivo]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.mandato
                 :values [{:id id :ente_id ente-id :vereador_id vereador-id :legislatura_id legislatura-id
                           :partido partido :estado (or estado "vigente") :natureza (or natureza "titular")
                           :vigencia_inicio vigencia-inicio :vigencia_fim vigencia-fim
                           :fim_efetivo fim-efetivo :efetivado_em [:now]}]})))

(defn mudar-estado!
  "Transicao de estado do mandato (cassacao/renuncia/licenca/...). fim-efetivo opcional. Defesa em
  profundidade: casa ente_id explicito no WHERE (a RLS ja' filtra o tenant — cinto-e-suspensorio, mesmo
  padrao de atualizar!)."
  [tx ente-id {:keys [id estado fim-efetivo]}]
  (jdbc/execute-one! tx
    (sql/format {:update :cadastros.mandato
                 :set {:estado estado :fim_efetivo [:coalesce fim-efetivo :fim_efetivo]}
                 :where [:and [:= :ente_id ente-id] [:= :id id]]})))

(defn mandatos-do-vereador [tx ente-id vereador-id]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:id :ente_id :vereador_id :legislatura_id :partido :estado :natureza
                            :vigencia_inicio :vigencia_fim :fim_efetivo]
                   :from [:cadastros.mandato]
                   :where [:and [:= :ente_id ente-id] [:= :vereador_id vereador-id]]
                   :order-by [[:vigencia_inicio]]}))))

(defn mandato-vigente
  "O mandato do vereador que COBRE `data` pela vigencia (qualquer estado — licenciado ainda e' o corrente).
   O mais recente se houver mais de um; tie-break deterministico por :id (mesmo padrao de
   relacoes/cadastro.clj `quem-exerce-presidencia`) — sem DB constraint que impeca vigencias sobrepostas.
   nil se nenhum cobre `data`."
  [tx ente-id vereador-id data]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :vereador_id :legislatura_id :partido :estado :natureza
                            :vigencia_inicio :vigencia_fim :fim_efetivo]
                   :from [:cadastros.mandato]
                   :where [:and [:= :ente_id ente-id] [:= :vereador_id vereador-id]
                           [:<= :vigencia_inicio data]
                           [:or [:is :vigencia_fim nil] [:>= :vigencia_fim data]]]
                   :order-by [[:vigencia_inicio :desc] [:id]] :limit 1}))))

(defn mandato-vigente-de-vereador
  "O mandato com estado='vigente' que COBRE `data` (p/ a licenca resolver o alvo). nil se nenhum — cobre
   'sem mandato vigente' E 'ja' licenciado' (um licenciado tem estado != 'vigente'). Tie-break por :id."
  [tx ente-id vereador-id data]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :vereador_id :estado :vigencia_inicio :vigencia_fim]
                   :from [:cadastros.mandato]
                   :where [:and [:= :ente_id ente-id] [:= :vereador_id vereador-id]
                           [:= :estado "vigente"]
                           [:<= :vigencia_inicio data]
                           [:or [:is :vigencia_fim nil] [:>= :vigencia_fim data]]]
                   :order-by [[:vigencia_inicio :desc] [:id]] :limit 1}))))

(defn mandato-licenciado-de-vereador
  "O mandato com estado='licenciado' que COBRE `data` (p/ a REASSUNCAO resolver o alvo). nil se nenhum —
   e' esse nil que impede a ressurreicao: um mandato 'cassado'/'renunciado'/'falecido' NUNCA aparece aqui,
   entao nenhum POST de reassuncao consegue anular uma cassacao. Tie-break por :id.

   Espelho DELIBERADO de `mandato-vigente-de-vereador` (mesma cobertura por vigencia, mesmo ORDER BY),
   e NAO uma generalizacao das duas com o estado por parametro: cada uma carrega na docstring o PORQUE do
   estado que busca (aqui, 'quem esta' fora e vai voltar'; la', 'quem esta' dentro e vai sair'), e e' isso
   que impede o proximo leitor de trocar uma pela outra num refactor de aparencia inocente.

   `FOR UPDATE` (o que este espelho tem A MAIS que o irmao): esta leitura e' o GUARD de uma transicao de
   estado que a mesma tx vai escrever, e `com-tenant*` abre a tx sem `:isolation` = READ COMMITTED, em que
   cada statement tira snapshot novo — sem o lock, duas reassuncoes concorrentes leem o MESMO 'licenciado',
   a perdedora fecha ZERO linhas (o `fim IS NULL` do UPDATE e' reavaliado depois do lock de linha), nao acha
   sobra na releitura e devolve SUCESSO com um `fim` que nunca foi gravado — publicando na janela de
   exercicio a data do vencedor arbitrario. Com o lock, a perdedora espera o COMMIT, rele' a linha ja'
   'vigente' e cai em :conflito/sem-mandato-licenciado. Mesma forma de `legislativo/db/tramitacao`,
   `votacao`, `emenda`, `compliance/db/obrigacao` e `sessoes/db/pauta` — toda transicao de estado da casa
   trava a linha que vai escrever. Pinado por `mandato-licenciado-de-vereador-trava-a-linha-que-vai-escrever`
   (o detector e' o BLOQUEIO: sem `FOR UPDATE` o SELECT passa direto por uma linha que outra tx segura)."
  [tx ente-id vereador-id data]
  (comum/linha->kebab
    (jdbc/execute-one! tx
      (sql/format {:select [:id :vereador_id :estado :vigencia_inicio :vigencia_fim]
                   :from [:cadastros.mandato]
                   :where [:and [:= :ente_id ente-id] [:= :vereador_id vereador-id]
                           [:= :estado "licenciado"]
                           [:<= :vigencia_inicio data]
                           [:or [:is :vigencia_fim nil] [:>= :vigencia_fim data]]]
                   :order-by [[:vigencia_inicio :desc] [:id]] :limit 1
                   :for :update}))))

(defn encerrar-licencas-abertas!
  "Fecha em `fim` TODAS as licencas AINDA ABERTAS (`fim IS NULL`) do mandato. Devolve o update-count.
   PRIMEIRO e unico UPDATE de `cadastros.mandato_licenca` no sistema — ate' esta fatia a tabela so' tinha
   INSERT, e por isso o fechamento da janela de exercicio era irreversivel. O GRANT da mig 0010:290 ja'
   cobre UPDATE (nao ha DELETE — Inv. 10), entao nao ha migration aqui.

   `fim` e' a VESPERA da reassuncao (`reassumiu-em` menos 1 dia) — quem calcula e' o Repo. A convencao vem
   do kernel: `tempo/subtrair-intervalos` e' INCLUSIVO nos dois lados e `subtrair-um` fecha o resto a
   esquerda em `(.minusDays b-inicio 1)`. Gravar aqui o proprio dia da volta comeria esse dia da janela
   PUBLICA de exercicio de todo mundo — um erro de um dia por licenca, silencioso, num numero nominal.

   `inicio <= fim` no WHERE e' o que torna a reassuncao FAIL-CLOSED contra 'voltei antes de sair': a linha
   simplesmente nao casa (nem grava um intervalo invertido, que nao tem CHECK que o barre na mig 0010), e o
   Repo detecta a sobra relendo — e' por isso que o update-count IMPORTA e nao pode ser descartado: o Repo
   so' lanca quando NENHUMA linha fechou, senao a licenca ainda-nao-iniciada que sobra prenderia o mandato
   (ver `reassumir-mandato!`). Fecha TODAS as abertas, nao uma: duas licencas abertas simultaneas nao
   deveriam existir, mas se existirem por defeito de dado antigo, lancar aqui deixaria o mandato preso para
   sempre — e a aritmetica de janela subtrai a uniao dos intervalos de qualquer jeito.

   Defesa em profundidade: `ente_id` explicito no WHERE alem da RLS (mesmo padrao de `mudar-estado!`/
   `atualizar!`)."
  [tx ente-id mandato-id fim]
  (:next.jdbc/update-count
   (jdbc/execute-one! tx
     (sql/format {:update :cadastros.mandato_licenca
                  :set {:fim fim}
                  :where [:and [:= :ente_id ente-id] [:= :mandato_id mandato-id]
                          [:is :fim nil] [:<= :inicio fim]]}))))

(defn mandato-sobreposto?
  "True se JA' existe mandato 'vigente' efetivado do vereador cuja vigencia sobrepoe [inicio, fim] (fim nil
   = aberto = 'infinity'). Guard app-level (UX 409); o EXCLUDE (migration ...59) e' a rede. SQL cru
   parametrizado — o operador `&&` de daterange e' direto assim (o db_test ja' usa SQL cru p/ casos pontuais)."
  [tx ente-id vereador-id inicio fim]
  (some?
    (jdbc/execute-one! tx
      ["SELECT 1 FROM cadastros.mandato
        WHERE ente_id = ? AND vereador_id = ? AND estado = 'vigente' AND efetivado_em IS NOT NULL
          AND daterange(vigencia_inicio, COALESCE(vigencia_fim, 'infinity'::date), '[]')
              && daterange(?::date, COALESCE(?::date, 'infinity'::date), '[]')
        LIMIT 1"
       ente-id vereador-id inicio fim])))

(defn listar
  "Lista de vereadores da Casa com o mandato que cobre `data` (partido/estado) e o cargo na Mesa vigente.
   Vereador sem mandato corrente aparece so' com o nome (estado-mandato/partido nil). Ordena por nome.

   Sem DB constraint que impeca vigencias sobrepostas (mandato/comissao_cargo), um vereador pode ter MAIS
   de uma linha cobrindo `data` — sem dedup, o LEFT JOIN plano faria FAN-OUT (o vereador apareceria
   duplicado na lista). Por isso o mandato e o cargo-na-Mesa vem cada um de um LEFT JOIN LATERAL que ja'
   escolhe o vencedor deterministico (mais recente por vigencia_inicio, tie-break por id — mesmo criterio
   de `mandato-vigente`/`quem-exerce-presidencia`), garantindo UMA linha por vereador."
  [tx ente-id data]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format
        {:select [:v.id :v.nome :v.nome_parlamentar :m.partido
                  [:m.estado :estado_mandato] [:cc.cargo :cargo_mesa]]
         :from [[:cadastros.vereador :v]]
         :left-join [[[:lateral
                       {:select [:mm.partido :mm.estado]
                        :from [[:cadastros.mandato :mm]]
                        :where [:and [:= :mm.vereador_id :v.id] [:= :mm.ente_id :v.ente_id]
                                [:<= :mm.vigencia_inicio data]
                                [:or [:is :mm.vigencia_fim nil] [:>= :mm.vigencia_fim data]]]
                        :order-by [[:mm.vigencia_inicio :desc] [:mm.id]]
                        :limit 1}]
                      :m] true
                     [[:lateral
                       {:select [:cc2.cargo]
                        :from [[:cadastros.comissao_cargo :cc2]]
                        :join [[:cadastros.comissao :mesa2]
                               [:and [:= :mesa2.id :cc2.comissao_id] [:= :mesa2.tipo "mesa"] [:= :mesa2.ente_id :v.ente_id]
                                [:<= :mesa2.vigencia_inicio data]
                                [:or [:is :mesa2.vigencia_fim nil] [:>= :mesa2.vigencia_fim data]]]]
                        :where [:and [:= :cc2.vereador_id :v.id] [:= :cc2.ente_id :v.ente_id]
                                [:<= :cc2.vigencia_inicio data]
                                [:or [:is :cc2.vigencia_fim nil] [:>= :cc2.vigencia_fim data]]]
                        :order-by [[:cc2.vigencia_inicio :desc] [:cc2.id]]
                        :limit 1}]
                      :cc] true]
         :where [:= :v.ente_id ente-id]
         ;; desempate por id: `nome` NAO e' unico (homonimia e' comum em camara municipal — o que distingue
         ;; e' o nome parlamentar). Sem ele o Postgres nao garante ordem relativa entre linhas de mesma
         ;; chave de sort e o plano pode mudar entre duas execucoes identicas: o secretario que confere a
         ;; lista impressa contra a tela marca o vereador errado, e a ata sai com a presenca do homonimo.
         :order-by [[:v.nome :asc] [:v.id :asc]]}))))

(defn- mandato-vigente-lateral
  "O fragmento HoneySQL do LATERAL de mandato — 'o mandato ('vigente' ou 'licenciado') deste vereador que
   COBRE `data-expr`, com 'vigente' vencendo 'licenciado' em empate' — extraido de dentro de `roster-da-casa`
   (Etapa 6 fatia 1) para ser COMPARTILHADO com `roster-da-casa-em-datas`. E' a garantia de I3 (brief da
   Etapa 6): o roster de UMA data e o roster em LOTE de VARIAS datas nao podem decidir 'quem tem mandato'
   por dois caminhos de codigo — se um dos dois ganhar um filtro que o outro nao ganhou, a Mesa le' um
   quorum na chamada e a apuracao de assiduidade conta sobre outra Casa para a MESMA sessao.

   `data-expr` e' uma EXPRESSAO honeysql, nao um valor: o singular passa o parametro `data` (a mesma data
   em toda linha, virando 1 bind param repetido); o lote passa a COLUNA `:d.data` da tabela de datas
   (correlacionada linha a linha pelo LATERAL). O corpo do fragmento e' identico nos dois casos — so' muda
   o que `data-expr` resolve a cada linha. Pressupoe o alias `:v` para `cadastros.vereador` na query externa
   (`:mm.vereador_id = :v.id`), que e' o mesmo em ambos os chamadores."
  [data-expr]
  {:select [:mm.partido :mm.estado]
   :from [[:cadastros.mandato :mm]]
   :where [:and [:= :mm.vereador_id :v.id] [:= :mm.ente_id :v.ente_id]
           [:in :mm.estado ["vigente" "licenciado"]]
           [:<= :mm.vigencia_inicio data-expr]
           [:or [:is :mm.vigencia_fim nil] [:>= :mm.vigencia_fim data-expr]]]
   ;; 'vigente' vence 'licenciado' quando os dois cobrem a data (o EXCLUDE da mig 0059 so' barra dois
   ;; VIGENTES sobrepostos): quem tem mandato vigente nao e' licenciado.
   :order-by [[[:case [:= :mm.estado "vigente"] 0 :else 1]]
              [:mm.vigencia_inicio :desc] [:mm.id]]
   :limit 1})

(defn- cargo-mesa-lateral
  "O fragmento HoneySQL do LATERAL de cargo-na-Mesa — irmao LITERAL de `mandato-vigente-lateral`, extraido
   pelo MESMO motivo (Etapa 6 fatia 1): o lote precisa das MESMAS colunas que o singular, cargo-mesa
   incluso, e um segundo predicado transcrito a mao seria o mesmo risco de divergencia silenciosa. Pressupoe
   os alias `:v` (vereador, query externa) e `:cc2`/`:mesa2` (internos deste fragmento)."
  [data-expr]
  {:select [:cc2.cargo]
   :from [[:cadastros.comissao_cargo :cc2]]
   :join [[:cadastros.comissao :mesa2]
          [:and [:= :mesa2.id :cc2.comissao_id] [:= :mesa2.tipo "mesa"] [:= :mesa2.ente_id :v.ente_id]
           [:<= :mesa2.vigencia_inicio data-expr]
           [:or [:is :mesa2.vigencia_fim nil] [:>= :mesa2.vigencia_fim data-expr]]]]
   :where [:and [:= :cc2.vereador_id :v.id] [:= :cc2.ente_id :v.ente_id]
           [:<= :cc2.vigencia_inicio data-expr]
           [:or [:is :cc2.vigencia_fim nil] [:>= :cc2.vigencia_fim data-expr]]]
   :order-by [[:cc2.vigencia_inicio :desc] [:cc2.id]]
   :limit 1})

(defn roster-da-casa
  "Os vereadores que COMPOEM a Casa em `data` — um por linha, com a identidade que a CHAMADA mostra (nome,
   nome parlamentar, partido) e o estado do mandato. Ordena por nome (a chamada e' lida em voz alta e
   conferida linha a linha; ordem instavel entre dois carregamentos e' erro de conferencia).

   POR QUE NAO E' `listar` COM UM PARAMETRO: `listar` existe para a tela de CADASTRO e por isso parte de
   `cadastros.vereador` com LEFT JOIN — devolve TODO vereador ja' registrado no ente, inclusive o cassado, o
   renunciado, o que nunca foi empossado e o suplente que nunca foi convocado, cada um com `estado_mandato`
   nil. E' o comportamento certo la' e o errado aqui: consumida pela chamada, uma Casa de 21 cadeiras
   listaria 40 nomes. O JOIN aqui e' INNER e o mandato e' filtrado por estado + janela, entao quem nao tem
   assento nao produz linha — nao ha' filtro app-side depois, que e' onde a divergencia costuma nascer.

   O PREDICADO DE JANELA E' O MESMO de `relacoes/cadastro.clj` (`tem-mandato-vigente?`/`membros-da-casa`):
   `vigencia_inicio <= data AND (vigencia_fim IS NULL OR vigencia_fim >= data)`; o de ESTADO e' um
   superconjunto deliberado (`vigente` + `licenciado`, ver abaixo).
   Isto nao e' coincidencia de escrita e nao pode virar: `membros-da-casa` e' o DENOMINADOR que o motor de
   votacao usa para decidir se ha' quorum, e estas linhas sao o conjunto sobre o qual a presenca e' contada.
   Se os dois predicados se separarem, a Mesa le' um numero no telao e a policy decide por outro na MESMA
   votacao. O `t10-roster-tem-exatamente-as-linhas-que-membros-da-casa-conta` (integracao) e' o cruzado que
   pina os dois lados.

   O LICENCIADO APARECE, MARCADO, E FICA FORA DO DENOMINADOR (revisao da Etapa 1 da chamada). O predicado
   do mandato e' `estado IN ('vigente','licenciado')`, mas quem COMPOE a Casa para efeito de quorum segue
   sendo so' o vigente: `sessoes/logic/contar-quorum` remove `:licenciado` de `membros-da-casa`, entao o
   denominador continua identico a `membros-da-casa` de `relacoes/cadastro.clj` (o cruzado T10 pina isso
   comparando as linhas MENOS os licenciados). Por que a linha precisa existir: enquanto o roster so'
   devolvia vigentes, o estado `:licenciado` e o sinalizador `inconsistencia-cadastro` de
   `sessoes/logic/estado-de-presenca` eram INALCANCAVEIS por dado real — o alarme 'o cadastro diz
   licenciado mas ele esta no plenario', que so' existe para o servidor ir consertar o cadastro, nunca
   dispararia em producao (e os testes unitarios daquele ramo cobriam caminho que a borda nao produzia).

   `data` e' parametro, nunca `hoje` implicito (disciplina §22.5.3 disc.5): uma chamada e' relida meses
   depois, e resolver a composicao em 'hoje' faria a ata de junho aparecer com a Casa de julho.

   O LEFT JOIN LATERAL de `listar` vira JOIN LATERAL mas continua LATERAL pelo mesmo motivo dela: escolhe UM
   vencedor deterministico (mais recente por vigencia_inicio, tie-break por id) e garante uma linha por
   vereador. O EXCLUDE `uq_mandato_vigente_sem_overlap` (mig 0059) hoje ja' impede dois mandatos 'vigente'
   sobrepostos do mesmo vereador, mas ele so' cobre linhas EFETIVADAS — o LATERAL e' o que mantem a garantia
   independente disso.

   TRAZ o cargo na Mesa (2a LEFT JOIN LATERAL, COPIA LITERAL da de `listar`): a fatia 1b-WIRE da chamada
   precisa dele (LinhaChamadaOut.cargo-mesa, p/ o telao distinguir o presidente/secretario na lista) — a
   decisao anterior de omitir por custo de um segundo subplano por vereador num hot-path ao vivo NAO se
   sustenta mais: a chamada e' lida uma vez por abertura de sessao, nao a cada evento de presenca, e o
   plano e' o MESMO da tela de cadastro (`listar`) que ja' paga este custo."
  [tx ente-id data]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format
        {:select [[:v.id :vereador_id] :v.nome :v.nome_parlamentar :m.partido [:m.estado :estado_mandato]
                  [:cc.cargo :cargo_mesa]]
         :from [[:cadastros.vereador :v]]
         :join [[[:lateral (mandato-vigente-lateral data)] :m] true]
         :left-join [[[:lateral (cargo-mesa-lateral data)] :cc] true]
         :where [:= :v.ente_id ente-id]
         ;; desempate por id: `nome` NAO e' unico (homonimia e' comum em camara municipal — o que distingue
         ;; e' o nome parlamentar). Sem ele o Postgres nao garante ordem relativa entre linhas de mesma
         ;; chave de sort e o plano pode mudar entre duas execucoes identicas: o secretario que confere a
         ;; lista impressa contra a tela marca o vereador errado, e a ata sai com a presenca do homonimo.
         :order-by [[:v.nome :asc] [:v.id :asc]]}))))

(def teto-de-datas-lote
  "Teto de datas DISTINTAS aceitas por `roster-da-casa-em-datas` (Etapa 6 fatia 1) — cada data vira uma
   linha da tabela VALUES cruzada com toda a Casa mais 2 LATERAL correlacionados por linha; sem teto, um
   periodo de apuracao absurdo (ex.: decadas) multiplicaria o custo da query sem limite. Convencao da casa
   (ver `rotas/teto-de-janelas`): todo predicado de cardinalidade aberta tem teto explicito e declarado.

   366 (nao 400): o PERIODO MAXIMO do brief da Etapa 6 e' de 366 dias, entao a borda nao consegue produzir
   mais de 366 datas civis DISTINTAS — um teto de 400 seria um guard-rail que NUNCA dispara, e guard-rail
   que nao pode disparar nao e' guard-rail (nao ha teste possivel do caminho de rejeicao a partir da rota,
   e o numero de 400 mentiria sobre qual e' o limite real do produto). O teto de 400 do brief e' de SESSOES
   no recorte, nao de datas — duas sessoes no mesmo dia sao UMA data (o dedup por `distinct` abaixo)."
  366)

(def teto-de-linhas-lote
  "Teto de LINHAS que `roster-da-casa-em-datas` aceita materializar na JVM. O teto de datas sozinho nao
   limita o resultado: o produto e' `datas x vereadores-com-mandato`, e o brief fixa 150 vereadores no
   roster do periodo — 366 x 150 = 54.900 linhas, cada uma repetindo nome/nome-parlamentar/partido/cargo do
   MESMO vereador. Sem este teto, uma Casa com cadastro legado inflado (centenas de mandatos vigentes
   simultaneos, que nenhuma constraint impede entre vereadores DIFERENTES) devolveria um resultado sem
   limite superior para um unico request.

   COMO E' APLICADO, e por que nao e' so' um `count` depois: a query vai ao driver com `:max-rows` =
   `teto + 1`, entao o JDBC PARA de materializar em `teto + 1` linhas — a memoria nunca e' gasta com o
   excesso. Ler `teto + 1` linhas e' a PROVA de que o teto foi ultrapassado, e a funcao lanca fail-closed
   (`:limite/linhas-excedido`) em vez de devolver a pagina truncada (I7 do brief: nada e' truncado em
   silencio). O preco honesto disso: quando estoura, sabemos que passou do teto mas NAO por quanto — por
   isso a ex-data traz `:medido-ao-menos`, nunca um `:medido` que fingiria ser a contagem real.

   MEDIDO no pior caso PERMITIDO antes de fixar o numero (150 vereadores x 400 datas = 60.000 linhas,
   `oplenario-postgres-1`, PG16, `oplenario_app` com RLS ativa, apos ANALYZE): 250,005 ms / 366.489 buffers
   COM os indices da migration 0074; 2.178,161 ms / 1.204.901 buffers sem eles. O pior caso permitido cabe;
   o teto existe para o que esta ALEM dele."
  (* 366 150))

(defn normalizar-datas!
  "Valida + dedup + teto da lista de datas de `roster-da-casa-em-datas`. Devolve o VETOR de datas distintas
   (vazio se `datas` e' nil/vazio). Chamada em DOIS lugares de proposito: no metodo do protocolo, ANTES de
   abrir a transacao (rejeicao nao pode custar conexao do pool), e aqui dentro da fn de `db/` como REDE
   (a fn e' publica e um caller futuro pode chama-la direto sobre uma `tx`). E' idempotente — normalizar o
   ja'-normalizado nao muda nada.

   TIPO EXIGIDO: `java.time.LocalDate` em TODO elemento, fail-closed. Nao e' preciosismo de tipo, sao tres
   defeitos medidos:
   - `nil` na lista SOBREVIVE ao `distinct`, vira `VALUES (NULL)`, nenhum LATERAL casa, e a funcao devolvia
     `{nil []}` — SUCESSO com roster vazio. Pior: `[nil]` sozinho estoura no banco (`date <= text`), mas
     `[nil d1]` passa. O comportamento mudava com a COMPANHIA do elemento invalido.
   - `java.sql.Date` na entrada: a query roda, mas `kernel/db_tipos` converte a coluna de volta para
     `LocalDate`, entao as chaves PRE-SEMEADAS (java.sql.Date) nunca casam com as das linhas (LocalDate).
     O mapa saia com 2N chaves e o chamador recebia `[]` para TODAS as datas — apuracao em branco, zero
     erro no log.
   - keyword ou forma HoneySQL (`[:raw \"...\"]`) viram EXPRESSAO SQL injetada na tabela VALUES, nao bind
     param. Nao e' explotavel pela borda de hoje (a rota so' produz LocalDate), mas o contrato frouxo e' o
     que torna a proxima borda explotavel sem ninguem notar.

   `datas` VAZIO (ou nil) devolve `[]` e e' LEGITIMO — significa 'nao ha sessao no recorte', e o chamador
   recebe `{}`. Um ELEMENTO nil e' o oposto: e' erro do CHAMADOR (montou a lista de datas com um buraco), e
   por isso lanca. A diferenca importa porque as duas coisas produziam o mesmo `{}`-ish silencioso antes."
  [datas]
  (doseq [d datas]
    (when-not (instance? LocalDate d)
      (throw (ex-info "roster em lote: cada data tem de ser java.time.LocalDate"
                      {:tipo :validacao/invalido :campo :datas
                       :classe (if (nil? d) "nil" (.getName (class d)))}))))
  (let [distintas (vec (distinct datas))]
    (when (> (count distintas) teto-de-datas-lote)
      (throw (ex-info "numero de datas distintas acima do teto do lote de roster"
                      {:tipo :limite/datas-excedido :medido (count distintas) :teto teto-de-datas-lote})))
    distintas))

(defn roster-da-casa-em-datas
  "O LOTE de `roster-da-casa` para VARIAS datas de uma vez — `{data -> [roster-linha ...]}`, MESMAS colunas
   do singular (inclusive `:estado-mandato`/`:cargo-mesa`). Existe para a apuracao de assiduidade (Etapa 6):
   apurar um periodo com centenas de sessoes NAO PODE reabrir o roster sessao a sessao (o carry N+1 do
   leitor AGREGADO, `i5-decisao.md:277` — 'passa a ser uma leitura em lote, nao um loop de seam singular').

   UMA UNICA query: `cadastros.vereador` CRUZADO (CROSS JOIN) com `datas` materializada como tabela VALUES
   `(VALUES (d1), (d2), ...) AS d(data)`, mais os DOIS LATERAL de `roster-da-casa` — `mandato-vigente-
   lateral`/`cargo-mesa-lateral`, os MESMOS fragmentos, aqui correlacionados por `:d.data` (a coluna, uma
   por linha) em vez do parametro fixo `data` do singular. E' esse compartilhamento que garante I3 do brief:
   as duas leituras nunca podem decidir 'quem tem mandato' por dois caminhos de codigo — o teste
   `t1-lote-e-identico-ao-singular-data-a-data` e' o cruzado que pina isso.

   Toda `data` do parametro aparece como CHAVE do mapa devolvido, MESMO quando nenhum vereador tem mandato
   vigente naquele dia (vetor vazio): o INNER JOIN LATERAL simplesmente nao produz linha nenhuma para essa
   data, e o mapa e' PRE-SEMEADO com todas as datas ANTES de agrupar as linhas — uma chave ausente seria
   lida a jusante como 'nao perguntei por essa data', nunca como 'perguntei e a resposta e' vazia' (e a
   apuracao soma sobre as chaves, entao a diferenca decide o denominador).

   `datas` VAZIO (ou nil) devolve `{}` SEM tocar o banco (mesmo racional de `licencas-de-mandatos` para id
   vazio). Datas DUPLICADAS no parametro sao dedupe'd antes do teto e da query — pedir a mesma data duas
   vezes nao e' um segundo dia de exercicio (a Casa com ordinaria de manha e extraordinaria a tarde manda a
   MESMA data duas vezes, e e' rotina), e conta-la duas vezes contra o teto seria hostil ao chamador.

   TRES rejeicoes FAIL-CLOSED, nenhuma silenciosa (I7 do brief — nada e' truncado nem vazio-por-acidente):
   elemento que nao e' `java.time.LocalDate` (`:validacao/invalido`), datas distintas acima de
   `teto-de-datas-lote` (`:limite/datas-excedido`) — as duas em `normalizar-datas!`, ver la' o porque de
   cada uma — e resultado acima de `teto-de-linhas-lote` (`:limite/linhas-excedido`).

   O AGRUPAMENTO LANCA em data desconhecida, e nao a CRIA: o `reduce` usava `update`, que cria a chave
   ausente. Uma linha cuja `data` nao esta entre as pre-semeadas so' pode significar que o tipo devolvido
   pelo driver nao casa com o tipo pedido (foi assim que `java.sql.Date` na entrada produzia um mapa de 2N
   chaves, metade delas nunca consultada pelo chamador, que entao lia `[]` para TUDO). Fabricar a chave
   transforma um erro de contrato em apuracao em branco; `:invariante/data-desconhecida` o torna visivel."
  [tx ente-id datas]
  (let [distintas (normalizar-datas! datas)]
    (if (empty? distintas)
      {}
      (let [linhas (comum/linhas->kebab
                    (jdbc/execute! tx
                      (sql/format
                        {:select [[:d.data :data] [:v.id :vereador_id] :v.nome :v.nome_parlamentar :m.partido
                                  [:m.estado :estado_mandato] [:cc.cargo :cargo_mesa]]
                         :from [[:cadastros.vereador :v]
                                [{:values (mapv vector distintas)} [[:d :data]]]]
                         :join [[[:lateral (mandato-vigente-lateral :d.data)] :m] true]
                         :left-join [[[:lateral (cargo-mesa-lateral :d.data)] :cc] true]
                         :where [:= :v.ente_id ente-id]
                         :order-by [[:d.data :asc] [:v.nome :asc] [:v.id :asc]]})
                      ;; `:max-rows` = teto+1: o driver PARA de materializar no primeiro excedente. Ler
                      ;; teto+1 e' a prova do estouro; a pagina truncada NUNCA e' devolvida (ver
                      ;; `teto-de-linhas-lote`).
                      {:max-rows (inc teto-de-linhas-lote)}))]
        (when (> (count linhas) teto-de-linhas-lote)
          (throw (ex-info "roster em lote acima do teto de linhas"
                          {:tipo :limite/linhas-excedido
                           :medido-ao-menos (count linhas) :teto teto-de-linhas-lote
                           :datas (count distintas)})))
        (reduce (fn [acc {:keys [data] :as linha}]
                  (when-not (contains? acc data)
                    (throw (ex-info "linha do lote de roster com data fora das datas pedidas"
                                    {:tipo :invariante/data-desconhecida
                                     :classe (if (nil? data) "nil" (.getName (class data)))})))
                  (update acc data conj (dissoc linha :data)))
                (zipmap distintas (repeat []))
                linhas)))))

;; ---- licenca + suplencia ----
(defn licencas-de-mandatos
  "As licencas registradas de um CONJUNTO de mandatos (I-5 fatia 3 — os buracos a subtrair da janela de
   exercicio). Servido pelo `idx_mandato_licenca_mandato (ente_id, mandato_id)` da mig 0010:253.

   `mandato-ids` VAZIO/nil devolve `[]` SEM tocar o banco: alem de `IN ()` ser SQL invalido, o caminho
   'vereador sem nenhum mandato' e' justamente o que nao deve gastar round-trip numa rota publica anonima.

   Devolve so' `{:mandato-id :inicio :fim}` — `fim` nil e' licenca EM CURSO (aberta), nao ausencia de dado
   (`mandato_licenca.fim` e' nullable na mig 0010; `inicio` e' `date NOT NULL`). Colunas `date` chegam como
   `java.time.LocalDate` (`kernel/db_tipos` estende ReadableColumn p/ `java.sql.Date`). NAO devolve
   `mandato_suplente_id` nem `motivo`: o unico leitor (a janela de exercicio) so' precisa do intervalo, e
   `motivo` e' dado potencialmente sensivel de saude que nao deve escorrer p/ uma leitura publica.

   Defesa em profundidade: `ente_id` explicito no WHERE alem da RLS (mesmo padrao de `buscar`/`atualizar!`).
   Nenhum teste COMPORTAMENTAL consegue falsificar esse predicado — a RLS (FORCE + policy sobre o GUC
   `app.ente_id`) ja' bloqueia o cross-tenant em qualquer formato de tx, e a mutacao `[:= 1 1]` deixa o ns
   verde; quem o pina e' o guard de FONTE `licencas-de-mandatos-tem-ente-id-explicito-no-where`.

   Ordem deterministica por (inicio, id): o consumidor previsto (`kernel/tempo/normalizar-intervalos`)
   ordena e nao depende dela, mas a ordem e' pinada por assercao em
   `ficha-e-mandatos-devolve-licencas-do-vereador-e-nenhuma-de-outro` (duas licencas inseridas fora de
   ordem) — remover o `:order-by` fica vermelho."
  [tx ente-id mandato-ids]
  (if (empty? mandato-ids)
    []
    (comum/linhas->kebab
      (jdbc/execute! tx
        (sql/format {:select [:mandato_id :inicio :fim]
                     :from [:cadastros.mandato_licenca]
                     :where [:and [:= :ente_id ente-id] [:in :mandato_id (vec mandato-ids)]]
                     :order-by [[:inicio] [:id]]})))))

(defn inserir-licenca! [tx {:keys [id ente-id mandato-id mandato-suplente-id inicio fim motivo]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.mandato_licenca
                 :values [{:id id :ente_id ente-id :mandato_id mandato-id :mandato_suplente_id mandato-suplente-id
                           :inicio inicio :fim fim :motivo motivo :efetivado_em [:now]}]})))

(defn inserir-suplencia! [tx {:keys [id ente-id legislatura-id partido vereador-id ordem]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :cadastros.suplencia
                 :values [{:id id :ente_id ente-id :legislatura_id legislatura-id :partido partido
                           :vereador_id vereador-id :ordem ordem :efetivado_em [:now]}]})))
