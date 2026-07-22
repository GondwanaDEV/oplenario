(ns oplenario.transparencia.db.parlamentar
  "Persistencia das projecoes de ATUACAO PARLAMENTAR do portal (Onda E fatia 2, mig 0064) — voto PUBLICO e
  presenca — mais a companheira `sessao_com_chamada` (mig 0067, carry I-5 fatia 5), que e' derivada da MESMA
  torrente de eventos de presenca e existe so' para reduzir a cardinalidade do denominador. Funcoes sobre a
  `tx` corrente (FORCE RLS isola). ESCRITA chamada pelo consumer dentro da tx do relay; LEITURA pelo
  Repo-Component. Voto SECRETO nunca chega aqui: o payload do evento (uniao discriminada por :modalidade)
  nem carrega identidade no ramo secreto."
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

(defn registrar-sessao-com-chamada!
  "Projeta a COMPANHEIRA `transparencia.sessao_com_chamada` (mig 0067): UMA linha por SESSAO que teve ao menos
  um registro de presenca de ALGUEM, com a DATA CIVIL dela. Chamada pelo consumer de `presenca.registrada`,
  na MESMA tx do relay em que `registrar-presenca!` roda — as duas escritas commitam juntas ou nenhuma.

  POR QUE UMA SEGUNDA TABELA. E' a peca que a fatia 6 do carry I-5 gasta: o denominador do numero-card sai de
  `COUNT(DISTINCT sessao_id)` sobre `presenca_parlamentar` (uma linha por sessao POR VEREADOR, ~21x o volume)
  para um `count(*)` aqui, servido por range scan em `(ente_id, data)` — que e' tambem o predicado da janela
  de exercicio do mandato. Ate' a fatia 6 esta tabela NAO TEM LEITOR: sobe primeiro de proposito, para que a
  troca do denominador seja um commit isolado e bisectavel.

  `LEAST` NO CONFLITO, e nao o gate de monotonicidade de `registrar-presenca!`: aqui o que se quer e' o
  PRIMEIRO evento da sessao, nao o ultimo. `LEAST` e' idempotente e COMUTATIVO — a data converge para o
  minimo independentemente da ordem em que o relay drenar os eventos, inclusive num redrive fora de ordem.
  O gate COPIADO de `registrar-presenca!` (`WHERE ocorrido_em <`) seria errado aqui: vetaria justamente o
  evento mais antigo, que e' o que define a data.

  MAS HA' GATE, e ele e' outro (revisao da fatia 5): `WHERE excluded.data < data`. Sem ele, `DO UPDATE`
  executa um UPDATE REAL em TODO evento que nao seja o primeiro da sessao — o Postgres nunca pula um UPDATE
  por valor identico, grava versao nova de tupla e registro de WAL mesmo quando `LEAST` devolve o que ja'
  estava la'. Como todos os ~21 eventos de uma chamada colidem na MESMA linha, eram ~20 escritas inuteis por
  sessao, pagas na tx do relay SINGLE-FLIGHT e COMPARTILHADO, numa tabela cujo unico proposito e' ser barata
  de VARRER na fatia 6 (pagina suja = nao all-visible = sem index-only scan, que e' literalmente o modo de
  falha que fez a mig 0065 medir PIOR que o baseline). O gate tem a MESMA semantica do `LEAST` — so' escreve
  quando o minimo de fato muda — e continua deixando a data RECUAR no redrive fora de ordem
  (`upsert-da-companheira-nao-reescreve-a-linha-quando-a-data-nao-muda` pina os dois lados, por `ctid`).

  O QUE `LEAST` **NAO** DA' (recorte honesto): ele e' ABSORVENTE. O gate de MAXIMO de `registrar-presenca!`
  AUTO-CURA um instante errado — o proximo evento, correto e mais novo, sobrescreve. Aqui e' o oposto: um
  `ocorrido_em` errado para MENOS (evento de fonte `manual_secretaria` com o ano digitado 2025 em vez de
  2026) fixa a data da sessao PARA SEMPRE, porque nenhum evento posterior passa pelo minimo. `presenca_evento`
  e' append-only sem anulacao e nao ha re-projecao no repo. E' o carry SENSIBILIDADE A UMA LINHA da decisao
  do I-5, na sua forma mais aguda: uma linha errada move uma sessao inteira para dentro/fora do denominador
  de TODOS os vereadores cuja janela cobre aquela data.

  `data` e' derivada em CLOJURE (`tempo/hoje-de` + `tempo/zona-civil-padrao`) e chega pronta — o fuso NAO
  aparece no SQL do consumer. O `AT TIME ZONE` que existe no backfill da migration e' o MESMO fuso, mas e'
  codigo de uma vez so'; o caminho vivo tem um lugar so' de fuso, que e' o pre-requisito de transforma-lo em
  atributo do ente (carry escrito).

  DATA BACKFILLADA NAO TEM ESTA SEMANTICA. As linhas escritas pelo backfill da mig 0067 e pelo reconciliador
  da 0068 nao vem daqui: elas derivam de `min(ocorrido_em) GROUP BY sessao` sobre `presenca_parlamentar`, que
  NAO e' um log — e' o ESTADO ATUAL por (sessao, vereador), com o MAXIMO por vereador. Ou seja, para essas
  linhas a data e' a do PRIMEIRO dos ULTIMOS eventos por vereador, que pode ser POSTERIOR a' do primeiro
  evento (medido: 7 dias, numa sessao suspensa e reaberta). Nao ha conserto dentro desta forma — o log so'
  existe em `sessoes.presenca_evento` e le-lo daqui seria JOIN cross-schema (§22.10). A divergencia esta'
  escrita no COMMENT do catalogo (mig 0068) e pinada por
  `linha-reconstruida-usa-o-primeiro-dos-ULTIMOS-eventos-e-pode-ser-POSTERIOR-a-do-caminho-vivo`.

  CUSTO DE ESCRITA — MEDIDO, nao estimado (2.000 eventos = 100 sessoes x 20 vereadores, mesma tx, apos
  aquecimento, papel oplenario_app com RLS ativa): o UPSERT de presenca custa ~0,364 ms/evento e ESTE custava
  ~0,342 ms/evento — o consumer de `presenca.registrada` passava a custar praticamente o DOBRO (+94%) por
  evento. Com o gate acima o que sobra por evento e' o round-trip JDBC + a busca no indice unico; a ESCRITA
  (versao de tupla + WAL) so' acontece uma vez por sessao. O `+94%` era o numero do shape SEM gate e nao foi
  re-medido com a mesma bancada — nao repetir aquele numero como se ainda valesse. E' pequeno em absoluto de
  qualquer modo, mas o relay e' SINGLE-FLIGHT e COMPARTILHADO por
  todos os modulos — o preco e' pago pela fila inteira, nao so' por esta projecao. Aceito de olhos abertos:
  a alternativa (derivar a mesma linha na LEITURA, a cada request da rota publica anonima e sem cache) e' o
  custo que a fatia 6 existe para eliminar. O regime de escrita de presenca e' rajada curta durante a sessao
  (~21 eventos por chamada), nao fluxo continuo."
  [tx {:keys [ente-id sessao-id data]}]
  {:pre [(some? ente-id) (some? sessao-id) (some? data)]}
  (jdbc/execute-one! tx
    (sql/format {:insert-into :transparencia.sessao_com_chamada
                 :values [{:ente_id ente-id :sessao_id sessao-id :data data}]
                 :on-conflict [:ente_id :sessao_id]
                 :do-update-set {:fields {:data [:least :transparencia.sessao_com_chamada.data :excluded.data]}
                                 :where [:< :excluded.data :transparencia.sessao_com_chamada.data]}})))

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

(defn- predicado-de-janela
  "OR dos intervalos de exercicio sobre a coluna `data` — INCLUSIVO nos dois lados (`fim` nil = em aberto,
  logo so' a borda esquerda entra no predicado). UM unico WHERE, nunca um JOIN contra a lista: com OR, dois
  intervalos sobrepostos nao podem fazer a MESMA sessao contar duas vezes (`multiplos-stints-nao-contam-a
  -mesma-sessao-duas-vezes` pina isso com janelas cruas, sem passar pela normalizacao do kernel).

  Intervalo com `:inicio` nil nao chega aqui (`kernel/tempo/normalizar-intervalos` e' fail-closed e lanca);
  se chegasse, `data >= NULL` e' NULL e o ramo simplesmente nao casa — fail-closed tambem neste lado."
  [janelas]
  (into [:or]
        (map (fn [{:keys [inicio fim]}]
               (if (some? fim)
                 [:and [:>= :data inicio] [:<= :data fim]]
                 [:>= :data inicio])))
        janelas))

(defn resumo-presenca
  "Numero-card de presenca RECORTADO PELA JANELA DE EXERCICIO (fecha o carry I-5). Devolve os DOIS numeros —
  a UI mostra a fracao, nunca um percentual sem denominador (um 100% de 1 sessao mente por omissao) — mais
  `:janela-de-exercicio-conhecida`, que diz se ha' periodo de exercicio registrado.

  AS JANELAS CHEGAM PRONTAS DA BORDA. O host as le de `cadastros` (`rotas/janelas-de-exercicio`, sobre
  `mandatos-do-vereador` + `licencas-de-mandatos`) e as passa como intervalos INCLUSIVOS de data civil,
  `:fim` nil = em aberto. §22.10: este modulo nunca importa `cadastros` e nao tem como saber o que e' um
  mandato — para ele isto e' uma lista de intervalos anonimos.

  JANELA VAZIA -> 0/0 com `:janela-de-exercicio-conhecida false` e SEM TOCAR O BANCO. `[]` significa 'sem
  periodo de exercicio registrado' (vereador sem mandato, eleito nao empossado), e a tela DEVE dizer isso —
  jamais '0%'. NUNCA cair no denominador do ente inteiro como fallback: seria republicar o I-5 justamente
  onde ninguem esta' olhando.

  DENOMINADOR = `count(*)` sobre `transparencia.sessao_com_chamada` (mig 0067: UMA linha por sessao que teve
  ao menos um registro de presenca de ALGUEM) cuja `data` cai em alguma janela. Ele NAO tem `vereador_id` no
  predicado, e isso e' o coracao da correcao: e' o que faz o faltoso cronico publicar '0 de 40' em vez de
  sumir num '0 de 0'. Quem meter `vereador_id` aqui derruba
  `faltoso-cronico-sem-nenhuma-linha-publica-zero-de-denominador-cheio`.

  NUMERADOR = `count(*)` do JOIN entre as linhas DESTE vereador em `presenca_parlamentar` e o MESMO conjunto
  elegivel. Numerador <= denominador por construcao (as duas PKs garantem unicidade — nenhum `DISTINCT`
  sobra). TER LINHA == COMPARECEU, e a AUSENCIA DE FILTRO POR `tipo` E' DELIBERADA:
  ausencia nunca e' gravada ('sem evento ate' la' = ausente', `sessoes/relacoes/presenca`), nao existe chamada
  em lote, e os QUATRO tipos do vocabulario real (entrada|saida|retorno|mudanca_modalidade, `sessoes/logic` +
  CHECK da mig 0029) sao todos registro de que a pessoa esteve na sessao. Ate' a Onda E/fatia 1 o predicado
  aqui era `tipo = 'presente'`, valor que PRODUTOR NENHUM emite: o numerador valia ZERO para todo parlamentar
  em producao, com o denominador cheio. Nao reintroduzir o filtro sem antes derrubar
  `numerador-conta-sessao-cujo-unico-evento-projetado-e-saida` — e' um numero publico e nominal.

  O QUE O PRODUTOR **NAO** GARANTE (revisao da fatia 1 — a versao anterior desta docstring afirmava que
  'saida so' existe depois de uma entrada', e isso e' FALSO): `sessoes/db/presenca/registrar-evento!` valida
  SO' os enums de tipo/modalidade/fonte — nao ha check de evento anterior, e a borda da Mesa aceita `tipo` E
  `vereador-id` do CORPO do cliente. Uma `saida` lancada no nome errado cria linha e conta comparecimento; e
  `presenca_evento` e' append-only SEM caminho de anulacao/retificacao e sem ferramenta de re-projecao no
  repo, entao a linha errada e' PERMANENTE. A escolha 'ter linha == compareceu' aceita esse risco de proposito:
  filtrar por `tipo` nao protegeria contra o caso realmente frequente (misatribuicao de `entrada`, que passa
  por qualquer filtro) e custaria o bug que acabou de ser corrigido.

  O QUE ESTE NUMERO **NAO** E':
  (a) NAO e' 'sessoes realizadas' — sessao sem NENHUM check-in nao existe no read-model e some dos DOIS
      lados da fracao (o rotulo da tela tem de dizer 'sessoes com registro de presenca');
  (b) a data da sessao e' derivada do evento de presenca, nao ha data de sessao em `transparencia` (JOIN
      cross-schema e' proibido) — ver `registrar-sessao-com-chamada!` para as duas semanticas (linha viva
      vs. linha de backfill/reconciliador) e a divergencia medida entre elas;
  (c) a janela vem do estado TRANSACIONAL de `cadastros` no instante da requisicao, NAO de evento
      projetado: corrigir uma `vigencia_inicio` muda este numero publicado no mesmo segundo, e um replay do
      outbox nao reproduz o numero de ontem. Mesmo regime, ja' sancionado, de `membros-da-casa` como
      denominador do painel da Mesa;
  (d) nada foi projetado antes de `adapters/out/parlamentar/presenca-projetada-desde` e nao ha replay — o
      denominador de um mandato anterior a essa data e' MENOR que a realidade, dos dois lados.

  CUSTO — MEDIDO, nao estimado (42.000 linhas sinteticas = 2.000 sessoes x 21 vereadores + 2.000 linhas na
  companheira, RLS ativa, papel `oplenario_app`, PG16). Baseline da fatia 1 (denominador global, um
  `COUNT(DISTINCT)` sobre `presenca_parlamentar`): ~605 buffers / ~12 ms. Depois desta fatia, titular de
  mandato inteiro (janela cobre as 2.000 sessoes): ~40 buffers / ~1,2 ms — cerca de 15x menos buffers e 10x
  menos tempo. Suplente de 3 sessoes: ~20 buffers / ~0,15 ms. Vereador sem janela: ZERO statement.
  A causa do ganho e' CARDINALIDADE, nao indice: o denominador deixou de ordenar 42.000 valores e passou a
  contar ~1/21 do volume por range scan em `(ente_id, data)`; o numerador le so' as linhas de UM vereador
  pelo indice `(ente_id, vereador_id, sessao_id)` da mig 0069. Corolario que vale escrever para nao ser
  re-descoberto: 'plano sem nenhum `Sort`' e' um gate VAZIO — o sort de um agregado `DISTINCT` e' INTERNO e
  nunca aparece como no do plano. Medir `Buffers` e `Execution Time`."
  [tx ente-id vereador-id janelas]
  {:pre [(some? ente-id) (some? vereador-id)]}
  (if (empty? janelas)
    {:sessoes-com-chamada 0 :sessoes-presente 0 :janela-de-exercicio-conhecida false}
    (assoc
     (comum/linha->kebab
      (jdbc/execute-one! tx
        (sql/format
         {:with [[:elegivel {:select [:sessao_id]
                             :from [:transparencia.sessao_com_chamada]
                             :where [:and [:= :ente_id ente-id] (predicado-de-janela janelas)]}]]
          :select [[{:select [[[:count :*]]] :from [:elegivel]} :sessoes_com_chamada]
                   [{:select [[[:count :*]]]
                     :from [[:transparencia.presenca_parlamentar :p]]
                     :join [[:elegivel :e] [:= :e.sessao_id :p.sessao_id]]
                     :where [:and [:= :p.ente_id ente-id] [:= :p.vereador_id vereador-id]]}
                    :sessoes_presente]]})))
     :janela-de-exercicio-conhecida true)))
