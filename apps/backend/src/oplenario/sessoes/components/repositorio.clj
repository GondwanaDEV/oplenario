(ns oplenario.sessoes.components.repositorio
  "Component de PERSISTENCIA do modulo SESSOES — banco DISPONIBILIZADO como Stuart Sierra Component
  (ADR-0001 §3). O protocolo RepoSessoes expoe as ACOES (tenant-aware: trata `com-tenant*` por dentro); o
  record segura o :datasource (via `using`); o db/ e' a IMPL. O controller depende DESTE Component, nunca do
  db/ direto. (Eventos de dominio Sessao*/real-time = eixos posteriores do F4.)"
  (:require [oplenario.kernel.tenancy :as tenancy]
            [oplenario.sessoes.diplomat.producers :as producers]
            [oplenario.sessoes.db.chamada :as chamada]
            [oplenario.sessoes.db.folha :as db-folha]
            [oplenario.sessoes.db.gravacao :as gravacao]
            [oplenario.sessoes.db.incidente :as incidente]
            [oplenario.sessoes.db.pauta :as pauta]
            [oplenario.sessoes.db.presenca :as presenca]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sessoes.db.tribuna :as tribuna]
            [oplenario.sessoes.logic :as logic]
            [oplenario.sessoes.relacoes.presenca :as rel-presenca])
  (:import (java.time Instant)
           (org.postgresql.util PSQLException)))

(defprotocol RepoSessoes
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant — compoe acoes atomicamente.")
  ;; §22.6 eixo A — sessao
  (agendar-sessao! [this ente-id m] "Numera gapless + resolve capabilities + insere 'agendada', atomico.")
  (transicionar-sessao! [this ente-id m] "Move o estado pela maquina (fail-closed) com CAS.")
  (buscar-sessao [this ente-id id])
  (sessoes-da-legislativa [this ente-id sessao-legislativa-id])
  ;; §22.6 eixo B — pauta (camada viva)
  (criar-pauta! [this ente-id m] "Cria a pauta 1:1 da sessao.")
  (buscar-pauta-por-sessao [this ente-id sessao-id])
  (adicionar-item-na-sessao! [this ente-id m] "Get-or-create do container 1:1 da sessao + insere item, atomico (uma tx).")
  (adicionar-item! [this ente-id m] "Insere item (ordem=max+1) + LOGA inclusao, atomico.")
  (reordenar-item! [this ente-id m] "Move item p/ nova ordem + LOGA inversao, atomico.")
  (remover-item! [this ente-id m] "Remocao soft (ativo=false) + LOG, atomico (nunca DELETE).")
  (buscar-item [this ente-id id])
  (listar-itens [this ente-id pauta-sessao-id] "Itens ATIVOS em ordem.")
  (listar-alteracoes [this ente-id pauta-sessao-id])
  ;; §22.6 eixo B — versionamento canonico (snapshots append-only)
  (publicar-versao! [this ente-id m] "Congela a pauta num snapshot canonico (numera local), append-only.")
  (buscar-versao [this ente-id id])
  (listar-versoes [this ente-id pauta-sessao-id])
  (versao-publica-corrente [this ente-id pauta-sessao-id] "Maior numero_versao com publica=true.")
  ;; §22.6 eixo C — presenca e quorum (camada de fatos)
  (registrar-presenca! [this ente-id m]
    "Grava evento de presenca append-only (entrada/saida/retorno/mudanca). `m` exige `:ocorrido-em` (o fato) e
     `:agora` (o relogio ja lido na borda): o GATE de estado + a janela da hora rodam DENTRO desta tx. Recusa
     lanca `:conflito/sessao-nao-aceita-presenca` (a borda mapeia 409). Devolve {:id :ocorrido-em :registrado-em}.")
  (registrar-presenca-lote! [this ente-id m]
    "Grava N eventos de presenca (Etapa 2c) NUMA UNICA transacao — ou as N linhas entram, ou nenhuma entra.
     `m` = {:sessao-id :registros [{:id :vereador-id :tipo :modalidade :fonte :ocorrido-em :created-by} ...]
     :agora}. O MESMO gate de estado + janela de `registrar-presenca!` vale para CADA linha do lote — a
     primeira reprovada recusa o lote inteiro, lancando `:conflito/sessao-nao-aceita-presenca` (409), e
     NENHUMA linha grava. Devolve os N recibos na ordem de `registros`.")
  (listar-presenca [this ente-id sessao-id] "Eventos da sessao em ordem cronologica (auditoria).")
  (presenca-corrente [this ente-id sessao-id instante]
    "Ultimo evento de CADA vereador da sessao ate' `instante` (uma linha por vereador) — insumo cru da CHAMADA.")
  (listar-justificativas [this ente-id sessao-id] "Justificativas de ausencia da sessao (3o insumo da chamada).")
  (chamada-da-sessao [this ente-id sessao-id agora]
    "As QUATRO leituras da chamada (sessao + presenca corrente + justificativas + atos de chamada conduzida)
     numa UNICA tx do tenant, com o INSTANTE de avaliacao resolvido DENTRO dela a partir da sessao fresca
     (`logic/instante-de-avaliacao`, recebendo `agora` = o relogio ja lido pelo controller). Devolve {:sessao
     :instante :presencas :justificativas :chamadas-conduzidas}, ou nil se a sessao nao existe neste ente (a
     borda traduz em 404). O roster de `cadastros` NAO entra aqui: e' outro modulo, resolvido por seam no
     host (§22.10).")
  (folha-da-sessao [this ente-id sessao-id agora]
    "As leituras da FOLHA (Etapa 5 fatia 1) numa UNICA tx do tenant: tudo o que `chamada-da-sessao` le'
     MAIS a SERIE de eventos da janela. Devolve {:sessao :instante :presencas :justificativas
     :chamadas-conduzidas :piso :serie}, ou nil se a sessao nao existe neste ente (404 na borda).

     E' UM METODO SO' — e nao o controller encadeando `chamada-da-sessao` + `buscar-sessao` +
     `serie-de-eventos-da-sessao` + `listar-justificativas` — porque cada chamada ao Repo abre uma tx NOVA
     (`transacao` = `tenancy/com-tenant*` = `jdbc/with-transaction`), e leituras em tx diferentes veem
     snapshots MVCC diferentes. A justificativa de ausencia e' escrita por um ATO APARTADO, deliberadamente
     SEM gate de estado de sessao (`controllers`, §JUSTIFICATIVA): a Mesa pode decidi-la no meio do request
     da folha. Em 4 tx, a MESMA justificativa saia 'pendente' na linha derivada (`:linhas`, lida na 1a) e
     'aprovada' no bloco cru (`:justificativas`, lida na 4a) — o documento formal, que a Fatia 4 congela em
     PDF, mentindo contra si mesmo. E' o TOCTOU que `chamada-da-sessao` ja' fechou uma vez (o comentario
     dela descreve o mesmo defeito com presenca) e que a folha reabria pela porta da composicao.

     `:piso` = `logic/piso-da-janela-de-presenca` da sessao lida AQUI (nil quando ela nao tem marco nenhum);
     `:serie` so' e' lida quando ha' piso — sem janela nao ha' serie, e o controller e' quem decide se isso
     e' erro (na folha, e'). O teto e' o mesmo `:instante` da chamada.")
  ;; Etapa 5 fatia 4 (D2/D3/D7/D9) — os CINCO primitivos de persistencia de `sessoes.folha_sessao` (mig 0073).
  ;; A ORQUESTRACAO (compoe o documento via `folha-da-sessao` acima + os ports de serializacao + o LACO de
  ;; retry de D7) mora em `sessoes.controllers/gerar-folha!`, NAO aqui: ela precisa da COMPOSICAO com o
  ;; roster (seam cross-modulo, §22.10) que so' o controller resolve, e RENDERIZAR entre uma leitura e uma
  ;; escrita nao pode acontecer dentro de uma UNICA tx deste Repo (prenderia o pool durante a renderizacao do
  ;; PDF — exatamente o que D7 rejeita como alternativa a `SELECT ... FOR UPDATE`). Este metodo continua
  ;; sendo o dono do formato dos DADOS (a tabela, o schema das linhas); a ORDEM das chamadas e' do chamador.
  (max-versao-da-folha [this ente-id sessao-id]
    "A maior versao ja' congelada de (ente, sessao), ou 0 (nunca congelada) — D7: o CHAMADOR le' isto ANTES
     de renderizar, propoe `(inc max)` como a versao a imprimir no papel.")
  (inserir-folha! [this ente-id row]
    "Insere a linha do congelamento com a versao EXPLICITA que `row` traz (D7 — nao MAX+1 no proprio INSERT).
     `UNIQUE (ente_id, sessao_id, versao)` detecta a corrida: 23505 sob duas propostas concorrentes para o
     MESMO numero. O chamador re-tenta (rele o max, re-renderiza, re-propoe). Devolve a linha inserida.
     ESCRITA CRUA, sem dedup — o caminho de producao e' `inserir-folha-dedup!`; este fica para semear e para
     escritor que NAO representa um pedido de ator (o competidor dos testes de corrida).")
  (inserir-folha-dedup! [this ente-id row desde]
    "O INSERT de D7 com a checagem de D9 DENTRO DA MESMA tx (revisao adversarial da fatia 4): le'
     `recente-do-ator` e, se ja' existe folha desta (sessao, ator) a partir de `desde`, devolve-a com
     `:ja-congelada true` em vez de inserir; senao insere a versao explicita de `row`.

     Por que a checagem TEM de morar aqui, e nao no controller: o pre-check do controller acontece ANTES da
     renderizacao (HTML+PDF, I/O lento), entao dois pedidos CONCORRENTES do MESMO ator — o duplo-clique que
     D9 nomeia como motivacao — liam ambos `nil` e ambos congelavam; o `UNIQUE` resolvia so' o NUMERO (v1 e
     v2), e o acervo ficava com DUAS linhas imutaveis do mesmo clique. Com a checagem aqui, o perdedor da
     corrida ou ja' enxerga a linha commitada (dedup), ou colide em 23505 e, no retry (tx nova), enxerga —
     nos DOIS caminhos converge para uma linha so'. E' o MESMO desenho de `registrar-chamada-conduzida!`:
     check-then-act de append-only vive dentro da tx que escreve, nunca fora dela.")
  (buscar-folha [this ente-id sessao-id versao] "Uma versao especifica da folha (metadados), ou nil.")
  (folhas-da-sessao [this ente-id sessao-id] "Todas as versoes congeladas da sessao, mais recente primeiro.")
  (folha-recente-do-ator [this ente-id sessao-id gerada-por desde]
    "A folha mais recente gerada por `gerada-por` para esta sessao a partir de `desde`, ou nil — D9: o
     insumo da deduplicacao de 30s (mesmo desenho de `chamada/ato-recente-do-ator`).")
  (registrar-chamada-conduzida! [this ente-id m]
    "Registra o ATO de chamada conduzida (Etapa 2d): o MESMO gate de estado+janela de `registrar-presenca!`
     roda DENTRO desta tx, sobre a sessao lida AQUI com `FOR SHARE` — nao sobre a leitura de authz do
     controller. `m` exige `:roster` (as linhas cruas do seam, resolvidas na data de referencia pelo
     controller) e `:agora` (o relogio ja lido na borda). O DENOMINADOR CONGELADO e' computado AQUI DENTRO,
     sobre a mesma uniao que `chamada-da-sessao` publica — nunca recebido pronto do caller (revisao: a conta
     roster-only divergia da tela e o registro e' append-only). Recusa lanca `:conflito/chamada` (a borda
     mapeia 409), inclusive por denominador zero e por teto de atos. Um REENVIO dentro de
     `logic/janela-de-deduplicacao-de-chamada` devolve o ato existente com `:ja-registrado true` (a borda
     responde 200). Devolve {:id :ocorrido-em :registrado-em :membros-da-casa}.")
  (listar-chamadas-conduzidas [this ente-id sessao-id] "Os atos de chamada da sessao, em ordem cronologica.")
  (resumo-presenca [this ente-id membros-da-casa]
    "Presenca agregada (F7/FE Onda A1) das ultimas 10 sessoes encerradas do tenant.")
  (esta-presente? [this ente-id sessao-id vereador-id instante] "Presenca DERIVADA do ultimo evento ate o instante.")
  (presentes-plenario [this ente-id sessao-id instante] "Quorum presencial em `instante` (insumo da DSL do motor).")
  (presentes-remoto [this ente-id sessao-id instante] "Quorum remoto em `instante`.")
  (criar-justificativa! [this ente-id m]
    "Abre justificativa de ausencia 'pendente' (ato apartado). Devolve {:id :estado :lock-version}. Duplicata
     p/ o mesmo (sessao, vereador) lanca `:conflito/justificativa` (409) — nunca sobrescreve a existente.")
  (buscar-justificativa [this ente-id id])
  (decidir-justificativa! [this ente-id m]
    "aprovada|indeferida (terminal) via maquina + CAS. Devolve {:de :para}; lock stale / ja terminal /
     inexistente lancam `:conflito/justificativa` (409).")
  ;; §22.6 eixo D — gravacao (audio/video)
  (registrar-segmento! [this ente-id m] "Grava segmento de gravacao (captura/ingestao); sessao_id opcional (Opcao A).")
  (vincular-segmento! [this ente-id m] "Vincula um segmento a sessao (uma-vez, CAS). `forcar-acesso-restrito` (sigilo §22.6) eleva acesso_restrito; emite gravacao.segmento-vinculado (core->IA) com o sigilo definitivo, atomico.")
  (buscar-segmento [this ente-id id])
  (listar-segmentos-da-sessao [this ente-id sessao-id] "Segmentos da sessao em ordem cronologica (read-model).")
  ;; §22.6 eixo F — tribuna: inscricao de oradores (intencao)
  (inscrever! [this ente-id m] "Inscreve um orador (intencao); numera a fila por (sessao, fase). Devolve {:id :ordem}.")
  (buscar-inscricao [this ente-id id])
  (listar-inscricoes [this ente-id sessao-id] "Fila de oradores da sessao (por fase + ordem).")
  (desistir! [this ente-id m] "Move a inscricao para 'desistencia' (terminal) via maquina + CAS.")
  ;; §22.6 eixo F — tribuna: fala executada + cronometro (execucao)
  (iniciar-fala! [this ente-id m] "Inicia a fala + loga 'iniciada', atomico. Devolve {:id}.")
  (registrar-evento-cronometro! [this ente-id m] "Evento manual do cronometro (pausada/retomada/aparte/tempo-adicional).")
  (encerrar-fala! [this ente-id m] "Encerra a fala, COMPUTA o tempo dos eventos + loga 'encerrada' (uma vez, CAS).")
  (buscar-fala [this ente-id id])
  (listar-falas-da-sessao [this ente-id sessao-id] "Falas da sessao em ordem cronologica (read-model + diarizacao).")
  (listar-apartes [this ente-id fala-pai-id] "Apartes de uma fala-mae.")
  (listar-eventos-cronometro [this ente-id fala-id] "Eventos do cronometro da fala (a projecao le daqui).")
  ;; §22.6 eixo F — tribuna: decisao da mesa (questao de ordem)
  (registrar-decisao-mesa! [this ente-id m] "Registra a decisao do presidente sobre questao de ordem (ato p/ ata, append-only).")
  (buscar-decisao-mesa [this ente-id id])
  (listar-decisoes-mesa [this ente-id sessao-id] "Decisoes da mesa da sessao em ordem cronologica (ata).")
  ;; §22.6 eixo F — tribuna: a LEITURA AGREGADA (read-model do telao, GET /sessoes/:id/tribuna)
  (tribuna-da-sessao [this ente-id sessao-id]
    "A leitura inteira da tribuna (sessao + fala em curso + marcos do cronometro DAQUELA fala + fila de
     inscritos) numa UNICA tx — molde de `chamada-da-sessao`. Devolve {:sessao :fala-em-curso :marcos
     :inscricoes} ou nil (sessao inexistente neste tenant -> 404 no diplomat).")
  ;; §16.13 — incidentes processuais (mesa de conducao ao vivo)
  (registrar-incidente! [this ente-id m] "Registra incidente processual (append-only) + emite incidente.registrado (SSE) na MESMA tx.")
  (buscar-incidente [this ente-id id])
  (listar-incidentes [this ente-id sessao-id] "Incidentes da sessao em ordem cronologica (ata + painel da mesa).")
  ;; Etapa 6 fatia 2 — a apuracao de assiduidade
  (leituras-assiduidade [this ente-id periodo]
    "As leituras de SESSOES + PRESENCAS + JUSTIFICATIVAS de um PERIODO (Etapa 6 fatia 2), NUMA UNICA tx —
     `periodo` = {:de :ate :tipos}. `sessao/listar-fechadas-no-periodo` aplica os DOIS tetos (dias do
     periodo, sessoes do recorte) fail-closed ANTES de qualquer leitura cara; o instante de CADA sessao
     (I5) e' resolvido daqui com `logic/instante-de-avaliacao` — nunca um instante global do periodo.
     O ROSTER (cadastros, cross-modulo, §22.10) NAO entra aqui: chega por SEAM, no controller, IGUAL a'
     `chamada-da-sessao`/`folha-da-sessao` — precisa saber quais DATAS pedir, que so' se sabe depois desta
     leitura. Devolve {:sessoes [...] :sessoes-sem-data-de-referencia N :presencas-por-sessao {...}
     :justificativas-por-sessao {...}} — o contador vem de `sessao/contar-fechadas-sem-data-de-referencia`,
     na MESMA tx e sobre a MESMA janela: e' o que o filtro de data EXCLUIU, e sem ele o denominador de todos
     os vereadores encolheria sem explicacao (ver la' por que a decisao e' CONTAR e nao consertar o COALESCE)."))

(defrecord RepoSessoesPg [datasource bus]
  RepoSessoes
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  ;; §22.6 eixo G + F7 E3 — compoe o ato de agendar + a emissao de sessao.agendada na MESMA tx (§22.9 E2): o
  ;; SLI de janela de sessao (paineis) materializa a linha ja' no nascimento da sessao, fechando a cegueira ao
  ;; no-show silencioso. `ocorrido-em` = instante do ato (efetivado_em, RETURNING) semeia o gate do SLI.
  (agendar-sessao! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (sessao/agendar! tx (assoc m :ente-id ente-id))]
          (producers/emitir-sessao-agendada! bus tx ente-id
            {:sessao-id (:id m) :agendada-para (some-> (:agendada-para m) str) :ocorrido-em (str (:ocorrido-em r))})
          r))))
  ;; §22.6 eixo G — compoe o ato + a emissao do evento de tempo real na MESMA tx (atomicidade §22.9 E2).
  (transicionar-sessao! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (sessao/transicionar! tx (assoc m :ente-id ente-id))]
          ;; so emite numa MUDANCA real de estado (de != para) — guard explicito contra evento espurio
          ;; (a maquina hoje lanca em transicao invalida/redundante, mas o contrato fica explicito aqui).
          (when (not= (:de r) (:para r))
            (producers/emitir-sessao-transicionou! bus tx ente-id
              ;; :ocorrido-em (F7 E3): string ISO do Instant real da transicao (RETURNING de atualizado_em em
              ;; db/sessao/transicionar!) — o SLI de janela de sessao carimba a janela DAQUI, nao do momento
              ;; de projecao (mirror do legislativo/proposicao).
              (cond-> {:sessao-id (:id m) :de (:de r) :para (:para r) :ocorrido-em (str (:ocorrido-em r))}
                (:updated-by m) (assoc :ator-id (:updated-by m)))))
          r))))
  (buscar-sessao [this ente-id id] (transacao this ente-id #(sessao/buscar % ente-id id)))
  (sessoes-da-legislativa [this ente-id slid] (transacao this ente-id #(sessao/listar-por-sessao-legislativa % ente-id slid)))
  (criar-pauta! [this ente-id m] (transacao this ente-id #(pauta/criar-pauta! % (assoc m :ente-id ente-id))))
  (buscar-pauta-por-sessao [this ente-id sessao-id] (transacao this ente-id #(pauta/buscar-pauta-por-sessao % ente-id sessao-id)))
  ;; get-or-create do container 1:1 + insere o item na MESMA tx (a pauta e' transparente: a borda adiciona item
  ;; a sessao, nao a um container que o cliente cria a parte). `sessao-id` resolve/cria a pauta; o resto de `m`
  ;; (id, fase, tipo-item, proposicao-id/texto-descricao, created-by) vai p/ adicionar-item!.
  (adicionar-item-na-sessao! [this ente-id {:keys [sessao-id created-by] :as m}]
    (transacao this ente-id
      (fn [tx]
        (let [pauta (pauta/garantir-pauta! tx {:ente-id ente-id :sessao-id sessao-id :created-by created-by})]
          ;; sob READ COMMITTED garantir-pauta! sempre resolve (insere ou re-le o vencedor); o guard cobre um
          ;; futuro REPEATABLE READ/SERIALIZABLE, onde o re-read poderia nao ver o commit concorrente -> nil ->
          ;; pauta-sessao-id nil -> NOT NULL nao-controlado (500). Falha controlada em vez disso (review clj).
          (when-not pauta
            (throw (ex-info "garantir-pauta!: container nao resolvivel" {:tipo :servidor/erro :sessao-id sessao-id})))
          (pauta/adicionar-item! tx (-> m (dissoc :sessao-id)
                                        (assoc :ente-id ente-id :pauta-sessao-id (:id pauta))))))))
  (adicionar-item! [this ente-id m] (transacao this ente-id #(pauta/adicionar-item! % (assoc m :ente-id ente-id))))
  (reordenar-item! [this ente-id m] (transacao this ente-id #(pauta/reordenar-item! % (assoc m :ente-id ente-id))))
  (remover-item! [this ente-id m] (transacao this ente-id #(pauta/remover-item! % (assoc m :ente-id ente-id))))
  (buscar-item [this ente-id id] (transacao this ente-id #(pauta/buscar-item % ente-id id)))
  (listar-itens [this ente-id pauta-sessao-id] (transacao this ente-id #(pauta/listar-itens % ente-id pauta-sessao-id)))
  (listar-alteracoes [this ente-id pauta-sessao-id] (transacao this ente-id #(pauta/listar-alteracoes % ente-id pauta-sessao-id)))
  (publicar-versao! [this ente-id m] (transacao this ente-id #(pauta/publicar-versao! % (assoc m :ente-id ente-id))))
  (buscar-versao [this ente-id id] (transacao this ente-id #(pauta/buscar-versao % ente-id id)))
  (listar-versoes [this ente-id pauta-sessao-id] (transacao this ente-id #(pauta/listar-versoes % ente-id pauta-sessao-id)))
  (versao-publica-corrente [this ente-id pauta-sessao-id] (transacao this ente-id #(pauta/versao-publica-corrente % ente-id pauta-sessao-id)))
  ;; O GATE DE ESTADO + a JANELA DA HORA rodam DENTRO desta tx, sobre a sessao lida AQUI com `FOR SHARE` —
  ;; nunca sobre a leitura que o controller fez antes para a authz. Com a checagem na leitura anterior, a
  ;; Mesa encerrava a sessao entre a checagem e o INSERT e o evento entrava assim mesmo, mudando o quorum de
  ;; uma votacao ja realizada. E' o mesmo defeito ("sessao stale entre transacoes") que a Etapa 1 fechou na
  ;; leitura da chamada; aqui ele fecha na ESCRITA.
  (registrar-presenca! [this ente-id {:keys [sessao-id ocorrido-em agora] :as m}]
    (transacao this ente-id
      (fn [tx]
        ;; ausencia de instante/relogio e' bug de servidor (a borda sempre os fornece), nao conflito do
        ;; usuario: 500 opaco em vez de um 409 que mandaria o operador corrigir o que nao e' dele.
        (when (or (nil? ocorrido-em) (nil? agora))
          (throw (ex-info "registrar-presenca!: ocorrido-em e agora sao obrigatorios (gate de janela)"
                          {:tipo :servidor/erro :sessao-id sessao-id})))
        (let [s (sessao/janela-para-registro tx ente-id sessao-id)]
          (when (nil? s)
            (throw (ex-info "registrar-presenca!: sessao inexistente neste ente"
                            {:tipo :conflito/sessao-nao-aceita-presenca :motivo :sessao-inexistente
                             :sessao-id sessao-id})))
          (when-let [motivo (logic/motivo-recusa-de-presenca s ocorrido-em agora)]
            (throw (ex-info (logic/mensagem-de-recusa-de-presenca motivo s agora)
                            {:tipo :conflito/sessao-nao-aceita-presenca :motivo motivo
                             :sessao-id sessao-id :estado (:estado s)})))
          (let [r (presenca/registrar-evento! tx (assoc m :ente-id ente-id))]
            (producers/emitir-presenca-registrada! bus tx ente-id
              {:sessao-id (:sessao-id m) :vereador-id (:vereador-id m) :tipo (:tipo m)
               :modalidade (:modalidade m) :fonte (:fonte m) :ocorrido-em (str (:ocorrido-em m))})
            r)))))
  ;; Etapa 2c — a CHAMADA EM LOTE. MESMO desenho de `registrar-presenca!` (gate + janela DENTRO da tx, sobre a
  ;; sessao lida AQUI com `FOR SHARE`), estendido a N linhas: a PRIMEIRA linha reprovada recusa o LOTE INTEIRO
  ;; ANTES de qualquer INSERT — nao ha' "grava as validas e recusa as invalidas". Meia chamada silenciosa e'
  ;; exatamente o defeito que esta rota existe para matar.
  (registrar-presenca-lote! [this ente-id {:keys [sessao-id registros agora]}]
    (transacao this ente-id
      (fn [tx]
        (when (or (nil? agora) (empty? registros))
          (throw (ex-info "registrar-presenca-lote!: agora e um lote nao-vazio sao obrigatorios"
                          {:tipo :servidor/erro :sessao-id sessao-id})))
        (when (some #(nil? (:ocorrido-em %)) registros)
          (throw (ex-info "registrar-presenca-lote!: toda linha do lote exige ocorrido-em"
                          {:tipo :servidor/erro :sessao-id sessao-id})))
        (let [s (sessao/janela-para-registro tx ente-id sessao-id)]
          (when (nil? s)
            (throw (ex-info "registrar-presenca-lote!: sessao inexistente neste ente"
                            {:tipo :conflito/sessao-nao-aceita-presenca :motivo :sessao-inexistente
                             :sessao-id sessao-id})))
          ;; UMA linha reprovada recusa o LOTE INTEIRO — nenhum INSERT roda antes desta checagem terminar.
          ;; O INDICE da linha entra na ex-data junto do `vereador-id` (revisao): o cliente casa
          ;; `recibos[i]` com `registros[i]` por POSICAO, entao sem o indice a tela nao consegue destacar a
          ;; linha e o secretario faz busca binaria reenviando sublotes, ao vivo, com o plenario esperando.
          (when-let [{:keys [motivo registro indice]}
                     (first (keep-indexed
                             (fn [i r]
                               (when-let [mo (logic/motivo-recusa-de-presenca s (:ocorrido-em r) agora)]
                                 {:motivo mo :registro r :indice i}))
                             registros))]
            (throw (ex-info (logic/mensagem-de-recusa-de-presenca motivo s agora)
                            {:tipo :conflito/sessao-nao-aceita-presenca :motivo motivo
                             :sessao-id sessao-id :estado (:estado s)
                             :vereador-id (:vereador-id registro) :indice indice})))
          (let [recibos (presenca/registrar-lote! tx ente-id sessao-id registros)]
            (doseq [{:keys [vereador-id tipo modalidade fonte ocorrido-em]} registros]
              (producers/emitir-presenca-registrada! bus tx ente-id
                {:sessao-id sessao-id :vereador-id vereador-id :tipo tipo :modalidade modalidade
                 :fonte fonte :ocorrido-em (str ocorrido-em)}))
            recibos)))))
  (listar-presenca [this ente-id sessao-id] (transacao this ente-id #(presenca/listar-eventos % ente-id sessao-id)))
  (presenca-corrente [this ente-id sessao-id instante]
    (transacao this ente-id #(presenca/presenca-corrente % ente-id sessao-id instante)))
  (listar-justificativas [this ente-id sessao-id]
    (transacao this ente-id #(presenca/listar-justificativas-da-sessao % ente-id sessao-id)))
  ;; UMA tx por request (molde de `adicionar-item-na-sessao!`, e o oposto do que `controllers/pauta-da-sessao`
  ;; faz com tres tx separadas). Aqui a atomicidade nao e' luxo: em tres tx, um vereador pode entrar no
  ;; plenario entre a leitura dos eventos e a das justificativas e sair na tela PRESENTE *e* com ausencia
  ;; justificada — uma chamada que nunca existiu em nenhum instante real, publicada em ata. O curto-circuito
  ;; no `when-let` tambem evita as duas leituras quando a sessao nao existe.
  (chamada-da-sessao [this ente-id sessao-id agora]
    (transacao this ente-id
      (fn [tx]
        (when-let [s (sessao/buscar tx ente-id sessao-id)]
          ;; o INSTANTE sai DAQUI, da sessao lida nesta tx — nao de uma leitura anterior no controller.
          ;; Com a sessao lida antes, uma sessao encerrada no meio do request era avaliada em 'agora' (o
          ;; estado stale ainda dizia 'aberta') e um evento POSTERIOR ao encerramento entrava na chamada.
          (let [instante (logic/instante-de-avaliacao s agora)]
            {:sessao s
             :instante instante
             :presencas (presenca/presenca-corrente tx ente-id sessao-id instante)
             :justificativas (presenca/listar-justificativas-da-sessao tx ente-id sessao-id)
             ;; Etapa 2d: o QUARTO insumo, na MESMA tx (evita o TOCTOU de uma 4a leitura a parte) — os atos
             ;; registrados de chamada conduzida. `sem-registro-de-presenca` (derivado so' de `:presencas`)
             ;; nao muda de significado; e' `:chamadas-conduzidas` que desambigua "ninguem chamou" de "a
             ;; chamada aconteceu e todos faltaram".
             :chamadas-conduzidas (chamada/listar-da-sessao tx ente-id sessao-id)})))))
  ;; A FOLHA (Etapa 5 fatia 1) — a MESMA tx unica de `chamada-da-sessao`, mais a SERIE. Nao delega a
  ;; `chamada-da-sessao` porque delegar abriria uma SEGUNDA tx: o que este metodo existe para impedir e' o
  ;; documento composto de snapshots diferentes (ver a docstring no protocolo). Toda leitura da folha esta
  ;; DENTRO deste `fn [tx]`, e nenhuma linha do controller volta ao banco depois.
  (folha-da-sessao [this ente-id sessao-id agora]
    (transacao this ente-id
      (fn [tx]
        (when-let [s (sessao/buscar tx ente-id sessao-id)]
          (let [instante (logic/instante-de-avaliacao s agora)
                piso     (logic/piso-da-janela-de-presenca s)]
            {:sessao s
             :instante instante
             :presencas (presenca/presenca-corrente tx ente-id sessao-id instante)
             :justificativas (presenca/listar-justificativas-da-sessao tx ente-id sessao-id)
             :chamadas-conduzidas (chamada/listar-da-sessao tx ente-id sessao-id)
             :piso piso
             ;; a janela e' [piso, instante] — o MESMO teto que a presenca corrente acima usou, para a serie
             ;; nunca mostrar um evento que a linha derivada nao viu.
             :serie (when piso (presenca/serie-de-eventos-da-sessao tx ente-id sessao-id piso instante))})))))
  ;; Etapa 5 fatia 4 (D2/D3/D7/D9) — os primitivos de `sessoes.folha_sessao`. Cada um e' UMA tx propria (nao
  ;; ha' uma UNICA tx envolvendo leitura+renderizacao+escrita — D7 rejeita explicitamente segurar um lock
  ;; durante a renderizacao do PDF): `max-versao-da-folha` le', o chamador renderiza FORA de qualquer tx, e
  ;; `inserir-folha!` escreve numa tx nova; se colidir (23505), o chamador re-tenta as tres etapas.
  (max-versao-da-folha [this ente-id sessao-id]
    (transacao this ente-id #(db-folha/max-versao % ente-id sessao-id)))
  (inserir-folha! [this ente-id row]
    (transacao this ente-id #(db-folha/inserir! % (assoc row :ente-id ente-id))))
  (inserir-folha-dedup! [this ente-id {:keys [sessao-id gerada-por] :as row} desde]
    (transacao this ente-id
      (fn [tx]
        ;; D9 DENTRO da tx da escrita — a leitura e o INSERT sao o MESMO ato, e nao dois separados por uma
        ;; renderizacao (que e' o que abria a janela do duplo-clique concorrente).
        (if-let [existente (db-folha/recente-do-ator tx ente-id sessao-id gerada-por desde)]
          (assoc existente :ja-congelada true)
          (db-folha/inserir! tx (assoc row :ente-id ente-id))))))
  (buscar-folha [this ente-id sessao-id versao]
    (transacao this ente-id #(db-folha/buscar % ente-id sessao-id versao)))
  (folhas-da-sessao [this ente-id sessao-id]
    (transacao this ente-id #(db-folha/folhas-da-sessao % ente-id sessao-id)))
  (folha-recente-do-ator [this ente-id sessao-id gerada-por desde]
    (transacao this ente-id #(db-folha/recente-do-ator % ente-id sessao-id gerada-por desde)))
  ;; MESMO desenho de `registrar-presenca!` (gate + janela DENTRO da tx, sobre a sessao lida AQUI com `FOR
  ;; SHARE`): conduzir a chamada e' o MESMO tipo de escrita — um fato contra o quorum de uma sessao, que nao
  ;; pode entrar depois que ela fechou. `:conflito/chamada` e' tag PROPRIA (nao reusa
  ;; `:conflito/sessao-nao-aceita-presenca`): convencao do modulo e' 1 tag por RECURSO (transicao/inscricao/
  ;; pauta/fala/vinculo/justificativa), e o ato de chamada e' um recurso proprio, nao presenca.
  (registrar-chamada-conduzida! [this ente-id {:keys [sessao-id conduzida-por roster ocorrido-em agora] :as m}]
    (transacao this ente-id
      (fn [tx]
        (when (or (nil? ocorrido-em) (nil? agora))
          (throw (ex-info "registrar-chamada-conduzida!: ocorrido-em e agora sao obrigatorios (gate de janela)"
                          {:tipo :servidor/erro :sessao-id sessao-id})))
        (when (nil? roster)
          (throw (ex-info "registrar-chamada-conduzida!: roster e' obrigatorio (denominador congelado)"
                          {:tipo :servidor/erro :sessao-id sessao-id})))
        (let [s (sessao/janela-para-registro tx ente-id sessao-id)]
          (when (nil? s)
            (throw (ex-info "registrar-chamada-conduzida!: sessao inexistente neste ente"
                            {:tipo :conflito/chamada :motivo :sessao-inexistente :sessao-id sessao-id})))
          (when-let [motivo (logic/motivo-recusa-de-presenca s ocorrido-em agora)]
            (throw (ex-info (logic/mensagem-de-recusa-de-chamada motivo s agora)
                            {:tipo :conflito/chamada :motivo motivo
                             :sessao-id sessao-id :estado (:estado s)})))
          ;; DEDUPLICACAO antes de qualquer conta: um REENVIO (duplo clique) devolve o ato que ja existe.
          ;; `:ja-registrado` sobe ate' a borda, que responde 200 em vez de 201 — o cliente distingue
          ;; "registrei agora" de "ja estava registrado" sem que um ato falso entre num append-only.
          (if-let [existente (chamada/ato-recente-do-ator tx ente-id sessao-id conduzida-por
                                                          (.minus ^Instant ocorrido-em
                                                                  logic/janela-de-deduplicacao-de-chamada))]
            (assoc existente :ja-registrado true)
            (do
              (when (>= (chamada/contar-da-sessao tx ente-id sessao-id) logic/teto-de-atos-de-chamada)
                (throw (ex-info (logic/mensagem-de-recusa-de-chamada :teto-de-atos s agora)
                                {:tipo :conflito/chamada :motivo :teto-de-atos :sessao-id sessao-id})))
              ;; O DENOMINADOR CONGELADO sai DAQUI, da mesma tx e da MESMA uniao que `chamada-da-sessao`
              ;; publica (roster x presenca corrente x justificativas) — nao de uma conta roster-only no
              ;; controller. Computa-lo la' fora dava um numero que DIVERGIA da tela no caso do licenciado
              ;; com evento positivo, e o congelado e' append-only: o numero errado nao podia ser corrigido,
              ;; so' acompanhado de outro. `ocorrido-em` e' o instante de avaliacao (a sessao esta viva — o
              ;; gate acima ja' garantiu — logo `instante-de-avaliacao` seria exatamente ele).
              (let [presencas (presenca/presenca-corrente tx ente-id sessao-id ocorrido-em)
                    justs     (presenca/listar-justificativas-da-sessao tx ente-id sessao-id)
                    membros   (logic/membros-da-casa-da-chamada roster presencas justs)]
                ;; FAIL-CLOSED no denominador ZERO. O precedente do mesmo eixo e' `instante-de-avaliacao`,
                ;; que escolheu LANCAR em vez de servir uma chamada fabricada ("melhor a rota cair do que a
                ;; ata mentir"). Aqui a chamada fabricada seria GRAVADA e imutavel: quorum impossivel /
                ;; divisao por zero a jusante (folha da sessao, apuracao de assiduidade), sem caminho de
                ;; reparo. O zero segue aceito no CHECK do banco como piso estrutural — o que se recusa e' o
                ;; ATO. Causa tipica: data da sessao digitada com o ano errado, ou acervo de mandatos ainda
                ;; nao migrado.
                (when (zero? membros)
                  (throw (ex-info (logic/mensagem-de-recusa-de-chamada :casa-sem-membros s agora)
                                  {:tipo :conflito/chamada :motivo :casa-sem-membros :sessao-id sessao-id})))
                (chamada/registrar! tx (-> m
                                           (dissoc :roster)
                                           (assoc :ente-id ente-id :membros-da-casa membros))))))))))
  (listar-chamadas-conduzidas [this ente-id sessao-id]
    (transacao this ente-id #(chamada/listar-da-sessao % ente-id sessao-id)))
  (resumo-presenca [this ente-id membros-da-casa]
    (transacao this ente-id #(presenca/resumo-presenca % ente-id membros-da-casa 10)))
  (esta-presente? [this ente-id sessao-id vereador-id instante] (transacao this ente-id #(rel-presenca/esta-presente-em? % sessao-id vereador-id instante)))
  (presentes-plenario [this ente-id sessao-id instante] (transacao this ente-id #(rel-presenca/presentes-plenario % sessao-id instante)))
  (presentes-remoto [this ente-id sessao-id instante] (transacao this ente-id #(rel-presenca/presentes-remoto % sessao-id instante)))
  ;; A CORRIDA PERDIDA vem daqui: o pre-check de `db/criar-justificativa!` da' a mensagem acionavel no caso
  ;; comum, mas duas requisicoes simultaneas leem 'nao existe' e as duas inserem — a 2a viola a UNIQUE
  ;; (ente_id, sessao_id, vereador_id) da mig 0029 e sobe 23505. Traduzir aqui (e nao no db/) segue o
  ;; precedente do repo (legislativo/participacao/cadastros catcham 23505 no Repo-Component): o catch tem de
  ;; envolver a TX inteira, porque depois do 23505 a tx esta abortada e nao ha' mais o que consultar dentro
  ;; dela. Mesma tag do pre-check -> o handler continua com um `catch` unico.
  (criar-justificativa! [this ente-id m]
    (try
      (transacao this ente-id #(presenca/criar-justificativa! % (assoc m :ente-id ente-id)))
      (catch PSQLException e
        (if (= "23505" (.getSQLState e))
          (throw (ex-info (logic/mensagem-de-recusa-de-justificativa :ja-existe)
                          {:tipo :conflito/justificativa :motivo :ja-existe
                           :sessao-id (:sessao-id m) :vereador-id (:vereador-id m)}))
          (throw e)))))
  (buscar-justificativa [this ente-id id] (transacao this ente-id #(presenca/buscar-justificativa % ente-id id)))
  (decidir-justificativa! [this ente-id m] (transacao this ente-id #(presenca/decidir-justificativa! % (assoc m :ente-id ente-id))))
  (registrar-segmento! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (gravacao/registrar-segmento! tx (assoc m :ente-id ente-id))]
          ;; fronteira core->IA (§22.3.3): o segmento captado vai p/ o pipeline de transcricao.
          (producers/emitir-gravacao-segmento-captado! bus tx ente-id
            (cond-> {:segmento-id (:id m) :container-bruto-uri (:container-bruto-uri m)
                     :fonte-ingestao (:fonte-ingestao m) :acesso-restrito (boolean (:acesso-restrito m))}
              (:sessao-id m) (assoc :sessao-id (:sessao-id m))))
          r))))
  (vincular-segmento! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r   (gravacao/vincular-segmento! tx (assoc m :ente-id ente-id))
              ;; le o estado pos-vinculo p/ o evento carregar o acesso-restrito DEFINITIVO (re-derivado p/
              ;; sessao secreta) — a IA reconcilia o sigilo por este evento, nao pelo captado (que no fluxo
              ;; Opcao A pode ter saido com acesso-restrito=false). Mesma tx do ato (atomicidade §22.9 E2).
              seg (gravacao/buscar tx ente-id (:id m))]
          (producers/emitir-gravacao-segmento-vinculado! bus tx ente-id
            {:segmento-id (:id m) :sessao-id (:sessao-id m) :acesso-restrito (boolean (:acesso-restrito seg))})
          r))))
  (buscar-segmento [this ente-id id] (transacao this ente-id #(gravacao/buscar % ente-id id)))
  (listar-segmentos-da-sessao [this ente-id sessao-id] (transacao this ente-id #(gravacao/listar-segmentos-da-sessao % ente-id sessao-id)))
  (inscrever! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (tribuna/inscrever! tx (assoc m :ente-id ente-id))]
          (producers/emitir-inscricao-registrada! bus tx ente-id
            {:inscricao-id (:id m) :sessao-id (:sessao-id m) :vereador-id (:vereador-id m)
             :origem-inscricao (:origem-inscricao m) :fase (:fase m) :ordem (:ordem r)})
          r))))
  (buscar-inscricao [this ente-id id] (transacao this ente-id #(tribuna/buscar-inscricao % ente-id id)))
  (listar-inscricoes [this ente-id sessao-id] (transacao this ente-id #(tribuna/listar-inscricoes % ente-id sessao-id)))
  (desistir! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [sid (:sessao-id (tribuna/buscar-inscricao tx ente-id (:id m)))  ; sessao-id p/ rotear o canal
              r   (tribuna/desistir! tx (assoc m :ente-id ente-id))]
          (producers/emitir-inscricao-desistida! bus tx ente-id {:inscricao-id (:id m) :sessao-id sid})
          r))))
  (iniciar-fala! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (tribuna/iniciar-fala! tx (assoc m :ente-id ente-id))]
          (producers/emitir-fala-iniciada! bus tx ente-id
            (cond-> {:fala-id (:id m) :sessao-id (:sessao-id m) :orador-id (:orador-id m)
                     :tipo-fala (:tipo-fala m) :fase (:fase m) :iniciou-em (str (:iniciou-em m))}
              (:inscricao-id m) (assoc :inscricao-id (:inscricao-id m))))
          r))))
  (registrar-evento-cronometro! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r   (tribuna/registrar-evento-cronometro! tx (assoc m :ente-id ente-id))
              sid (:sessao-id (tribuna/buscar-fala tx ente-id (:fala-id m)))]  ; sessao-id p/ rotear o canal
          (producers/emitir-fala-cronometro! bus tx ente-id
            (cond-> {:fala-id (:fala-id m) :sessao-id sid :tipo (:tipo m) :ocorrido-em (str (:ocorrido-em m))}
              (:segundos-adicionais m) (assoc :segundos-adicionais (:segundos-adicionais m))))
          r))))
  (encerrar-fala! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (tribuna/encerrar-fala! tx (assoc m :ente-id ente-id))
              f (tribuna/buscar-fala tx ente-id (:id m))]
          (producers/emitir-fala-encerrada! bus tx ente-id
            {:fala-id (:id m) :sessao-id (:sessao-id f)
             :tempo-segundos (:tempo-efetivamente-usado-segundos r) :encerrou-em (str (:encerrou-em m))})
          r))))
  (buscar-fala [this ente-id id] (transacao this ente-id #(tribuna/buscar-fala % ente-id id)))
  (listar-falas-da-sessao [this ente-id sessao-id] (transacao this ente-id #(tribuna/listar-falas-da-sessao % ente-id sessao-id)))
  (listar-apartes [this ente-id fala-pai-id] (transacao this ente-id #(tribuna/listar-apartes % ente-id fala-pai-id)))
  (listar-eventos-cronometro [this ente-id fala-id] (transacao this ente-id #(tribuna/listar-eventos-cronometro % ente-id fala-id)))
  (registrar-decisao-mesa! [this ente-id m] (transacao this ente-id #(tribuna/registrar-decisao-mesa! % (assoc m :ente-id ente-id))))
  (buscar-decisao-mesa [this ente-id id] (transacao this ente-id #(tribuna/buscar-decisao-mesa % ente-id id)))
  (listar-decisoes-mesa [this ente-id sessao-id] (transacao this ente-id #(tribuna/listar-decisoes-mesa % ente-id sessao-id)))
  ;; UMA tx (molde LITERAL de `chamada-da-sessao` acima): sessao + fala em curso + marcos DAQUELA fala +
  ;; fila, tudo na MESMA leitura — em tx separadas, a Mesa podia encerrar a fala no meio do request e o
  ;; telao publicava um orador que ja' desceu da tribuna. Curto-circuito: sessao inexistente devolve nil
  ;; sem tocar fala/cronometro/inscricoes. SEM roster: ao contrario de `chamada-da-sessao`, esta leitura
  ;; nao cruza a Casa com presenca (nao ha' denominador a resolver aqui).
  (tribuna-da-sessao [this ente-id sessao-id]
    (transacao this ente-id
      (fn [tx]
        (when-let [s (sessao/buscar tx ente-id sessao-id)]
          (let [fala (tribuna/fala-em-curso tx ente-id sessao-id)]
            {:sessao s
             :fala-em-curso fala
             ;; marcos so' fazem sentido presos a UMA fala — sem fala em curso, nao ha' cronometro de
             ;; ninguem para ler (e ler `fala-id nil` seria uma query sem sentido, nao "zero marcos").
             :marcos (if fala (tribuna/listar-eventos-cronometro tx ente-id (:id fala)) [])
             :inscricoes (tribuna/listar-inscricoes tx ente-id sessao-id)})))))
  ;; §16.13 — compoe o ato append-only + a emissao do evento de tempo real na MESMA tx (atomicidade §22.9 E2):
  ;; o painel da mesa de conducao reage ao incidente ao vivo (SSE canal plenario).
  (registrar-incidente! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (incidente/registrar! tx (assoc m :ente-id ente-id))]
          (producers/emitir-incidente-registrado! bus tx ente-id
            (cond-> {:incidente-id (:id m) :sessao-id (:sessao-id m) :tipo (:tipo m)
                     :resultado (:resultado m) :ocorrido-em (str (:ocorrido-em m))}
              (:objeto-tipo m)   (assoc :objeto-tipo (:objeto-tipo m))
              (:objeto-id m)     (assoc :objeto-id (:objeto-id m))
              (:requerente-id m) (assoc :requerente-id (:requerente-id m))))
          r))))
  (buscar-incidente [this ente-id id] (transacao this ente-id #(incidente/buscar % ente-id id)))
  (listar-incidentes [this ente-id sessao-id] (transacao this ente-id #(incidente/listar-da-sessao % ente-id sessao-id)))
  ;; Etapa 6 fatia 2 — UMA tx do lado de `sessoes` (mesmo molde de `chamada-da-sessao`/`folha-da-sessao`): as
  ;; TRES leituras (sessoes, presencas, justificativas) veem o MESMO snapshot MVCC. O roster fica de fora
  ;; (outro modulo, outra tx — o controller resolve depois desta chamada devolver).
  (leituras-assiduidade [this ente-id periodo]
    (transacao this ente-id
      (fn [tx]
        (let [sessoes (sessao/listar-fechadas-no-periodo tx ente-id periodo)
              ;; `instante-de-sessao-fechada`: reusa `logic/instante-de-avaliacao` (I5) com uma guarda —
              ;; `listar-fechadas-no-periodo` so' devolve `logic/estados-sessao-fechada`, entao o ramo 'agora'
              ;; daquela funcao (que exigiria um relogio que este metodo nao tem) nunca deveria disparar; a
              ;; guarda falha ALTO se essa invariante um dia quebrar, em vez de silenciosamente avaliar
              ;; presenca em `agora=nil` (zerando a apuracao daquela sessao sem erro nenhum).
              instante-de-sessao-fechada
              (fn [s]
                (when-not (contains? logic/estados-sessao-fechada (:estado s))
                  (throw (ex-info "leituras-assiduidade: sessao nao fechada no lote (invariante quebrada)"
                                  {:tipo :servidor/erro :sessao-id (:id s) :estado (:estado s)})))
                (logic/instante-de-avaliacao s nil))
              sessoes-com-instante (mapv (fn [s] [(:id s) (instante-de-sessao-fechada s)]) sessoes)
              sessao-ids (mapv :id sessoes)]
          {:sessoes sessoes
           ;; a MESMA janela, contando o que o filtro de data EXCLUIU por nao ter data de referencia
           ;; nenhuma — na MESMA tx, para o numero publicado nao ser de outro snapshot que o das sessoes.
           :sessoes-sem-data-de-referencia (sessao/contar-fechadas-sem-data-de-referencia tx ente-id periodo)
           :presencas-por-sessao (presenca/presencas-correntes-das-sessoes tx ente-id sessoes-com-instante)
           :justificativas-por-sessao (presenca/justificativas-das-sessoes tx ente-id sessao-ids)})))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoSessoesPg nil nil))
