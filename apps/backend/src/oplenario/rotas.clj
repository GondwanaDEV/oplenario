(ns oplenario.rotas
  "Composicao das ROTAS do host (§22.10): junta os handlers (oplenario.http) com a cadeia de interceptors
  (oplenario.interceptors) — fica separada de http.clj p/ evitar ciclo (http nao conhece interceptors). W2
  monta /saude (publica) + /eu (auth) + /painel-secretaria (auth + papel). W3 adiciona as rotas-dado de cada
  modulo (com o servidor `using` os Repo). `montar` recebe os deps ja injetados (idp + repo-identidade)."
  (:require [oplenario.cadastros.components.repositorio :as repo-cadastros-comp]
            [oplenario.cadastros.diplomat.http.in :as cadastros-http]
            [oplenario.compliance.diplomat.http.in :as compliance-http]
            [oplenario.config :as config]
            [oplenario.http :as http]
            [oplenario.identidade.components.repositorio :as repo-identidade-comp]
            [oplenario.identidade.diplomat.http.auth-in :as auth-http]
            [oplenario.identidade.diplomat.http.in :as identidade-http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.legislativo.components.repositorio :as repo-legislativo-comp]
            [oplenario.legislativo.diplomat.http.in :as legislativo-http]
            [oplenario.paineis.diplomat.http.in :as paineis-http]
            [oplenario.participacao.diplomat.http.in :as participacao-http]
            [oplenario.sessoes.components.renderizador-pdf :as renderizador-pdf]
            [oplenario.sessoes.components.repositorio :as repo-sessoes-comp]
            [oplenario.sessoes.logic :as sessoes-logic]
            [oplenario.sessoes.components.serializador-folha :as serializador-folha]
            [oplenario.sessoes.diplomat.http.in :as sessoes-http]
            [oplenario.tempo-real.diplomat.sse :as tempo-real-sse]
            [oplenario.transparencia.diplomat.http.in :as transparencia-http]))

(set! *warn-on-reflection* true)

(defn resolver-vereador
  "identidade-id -> vereador-id NESTA Casa — host wiring (§22.5.3, exceção nomeada; mesma forma de
  `membros-da-casa`/`resolver-municipio` em `montar`). Resolve via o Repo-Component de `cadastros`
  (`vereador-por-identidade`) e devolve só o `:id`. `nil` quando a identidade não tem cadastro de
  vereador NESTE ente — não é erro: a borda `/meu` (Onda C1, `legislativo`) trata como painel vazio,
  nunca 500. O `legislativo` recebe esta fn JÁ RESOLVIDA pelo host (§22.10) — nunca importa `cadastros`.
  Extraída como defn de topo (em vez de closure só-inline) p/ ser testável direto contra Postgres real,
  sem subir o sistema inteiro (mesmo racional de `presenca-resumo-wire`/`esic-cumprimento-wire`)."
  [repo-cadastros ente-id identidade-id]
  (:id (repo-cadastros-comp/vereador-por-identidade repo-cadastros ente-id identidade-id)))

(defn resolver-comissoes
  "comissao-ids -> {comissao-id nome} NESTA Casa — host wiring (§22.5.3, exceção nomeada, mesma forma de
  `resolver-vereador`/`membros-da-casa`). Resolve via o Repo-Component de `cadastros`
  (`nomes-de-comissoes`); o `legislativo` recebe esta fn JA' RESOLVIDA e nunca importa `cadastros`.

  Existe porque `legislativo.pareceres.comissao_id` e' guard ref `uuid NOT NULL` SEM FK cross-schema
  (mig 20260620000019): o legislativo guarda o id e nunca soube o nome, entao a tela do parecer exibia
  `9119889e-...` — defeito #11 do ledger de prontidao (`docs/16-ledger-prontidao.md`), classificado
  `MATA`. Nao havia, ate' aqui, NENHUMA rota que fizesse id->nome de comissao.

  PLURAL de proposito: a ficha da materia lista N pareceres e resolver um a um seria N transacoes por
  request. Id sem comissao correspondente (guard ref orfao, ou comissao de outra Casa — a RLS ja' corta)
  simplesmente nao aparece no mapa; o chamador degrada pra nil e o FE mostra rotulo honesto. Nunca lanca,
  nunca inventa nome. Extraida como defn de topo, testavel direto contra Postgres real (mesmo racional de
  `resolver-vereador`)."
  [repo-cadastros ente-id ids]
  (repo-cadastros-comp/nomes-de-comissoes repo-cadastros ente-id ids))

(defn nome-na-casa
  "identidade-id -> nome, SO' de quem tem vinculo NESTA Casa — host wiring (§22.5.3, exceção nomeada, mesma
  forma de `resolver-vereador`). O `sessoes` guarda o `gerada-por` da folha como id de identidade e nunca soube
  o nome: a tela da folha exibia \"Congelada por 60ac7fcd\" (achado do roteiro do operador, docs/23 Fatia 5).

  O vinculo NESTE ente e' a guarda: `vinculos-de` corre sob a RLS do tenant, entao uma identidade sem vinculo
  aqui (de outra Casa, ou um id qualquer) devolve nil — o nome de quem nao pertence a Casa nunca sai por
  esta porta. Le' pela via ESTREITA `nome-por-id` (nunca materializa CPF). Nunca inventa nome."
  [repo-identidade ente-id identidade-id]
  (when (seq (repo-identidade-comp/vinculos-de repo-identidade ente-id identidade-id))
    (:nome (repo-identidade-comp/nome-por-id repo-identidade identidade-id))))

(def ^:private teto-de-janelas
  "Teto de intervalos devolvidos por `janelas-de-exercicio`. Cada janela vira um ramo de OR sobre `data` no
  WHERE da fatia 6, numa rota PUBLICA, anonima e sem cache; `criar-mandato!` (INSERT direto — o caminho do
  seed e do import de acervo legado, que carrega `lote_id`) nao limita a QUANTIDADE de stints nao
  sobrepostos (o EXCLUDE da mig 0059 so' impede SOBREPOSICAO entre mandatos 'vigente'). Convencao da casa:
  todo predicado de cardinalidade aberta tem teto explicito (2000 na pauta, 5000 no fan-out, 4096 no
  incidente, 200/50 nas listas deste mesmo perfil)."
  100)

(defn janelas-de-exercicio
  "Stints de mandato + licencas -> JANELA DE EXERCICIO: os periodos de data civil em que esta pessoa
  estava efetivamente no exercicio do mandato (I-5 fatia 4). PURA — sem banco, sem relogio, sem fuso.
  Entrada = as linhas cruas de `cadastros` (`mandatos-do-vereador` e `licencas-de-mandatos`, ja' em kebab
  e com colunas `date` como `java.time.LocalDate` — `kernel/db_tipos` faz a ponte, NAO ha cast aqui);
  saida = a forma canonica do kernel, intervalos INCLUSIVOS dos dois lados com `:fim` nil = em aberto.

  A fatia 6 usa isto como predicado do DENOMINADOR de presenca — e' a correcao do carry I-5, em que o
  suplente de 3 sessoes recebia o denominador da legislatura inteira.

  QUATRO decisoes que este corpo carrega, cada uma verificada na fonte de `cadastros`:
  - a licenca e' subtraida SO' DO SEU PROPRIO STINT (`mandato_licenca.mandato_id`, que a FK
    `(ente_id, mandato_id)` da mig 0010:160 amarra e que `licencas-de-mandatos` devolve de proposito).
    Subtrair da UNIAO dos stints — como este corpo fazia ate a revisao da fatia 4 — apaga stint alheio no
    minuto em que a licenca e' aberta (`fim` nil): `normalizar-intervalos` funde stints adjacentes e o
    buraco vale ate +infinito, entao uma licenca de 2022 apagava o mandato que a pessoa exerce HOJE.
    Licenca cujo `mandato-id` nao esta' entre os stints recebidos nao subtrai NADA.
  - fim do stint = `tempo/menor-fim` de `fim-efetivo` e `vigencia-fim` (nil = em aberto, logo perde de
    qualquer data), nunca `(or ...)`: `mudar-estado!` grava `fim_efetivo` com `[:coalesce ...]` e sem
    nenhuma checagem contra `vigencia_fim` (nao ha CHECK na mig 0010 nem trigger), entao uma data digitada
    posterior ao fim da vigencia ALARGARIA a janela em vez de encurta-la.
  - TODOS os stints, nunca `mandato-vigente` (que e' LIMIT 1 na data de hoje e devolve nil p/ ex-vereador,
    justamente o perfil historico em que a janela mais importa). O vao ENTRE dois stints de um suplente
    reconvocado nao e' exercicio e nao entra.
  - acima de `teto-de-janelas` intervalos, esta fn LANCA fail-closed (`:limite/janelas-excedido`) em vez de
    truncar (frente 'truncamento-familia', sitio (c) — correcao da decisao original desta docstring, que
    fazia `take-last` e descartava as MAIS ANTIGAS em silencio). Duas razoes para fail-closed em vez de um
    campo `-total`/`-truncado`: (i) isto e' o DENOMINADOR de um numero PUBLICADO (a fracao de assiduidade
    da fatia 6) — truncar o denominador produz um numero ERRADO apresentado como certo, a mesma categoria
    de `sessoes/db/sessao.clj:listar-todas`/`cadastros/db/vereador.clj:379`, nao a das listagens de UI da
    familia; (ii) `janela-anterior-a-projecao?` (adapters/out/parlamentar) e' calculado SOBRE estas janelas
    — um corte silencioso tambem distorceria aquela decisao, nao so' a fracao. Rota PUBLICA e ANONIMA:
    publicar uma assiduidade errada como se fosse certa e' pior que 422.

  Vereador sem mandato -> `[]`. Vazio aqui significa 'sem periodo de exercicio registrado' e a fatia 6 o
  publica como tal (0 de 0 + `:janela-de-exercicio-conhecida false`) — NUNCA como fallback p/ o
  denominador global, que seria republicar o I-5 onde ninguem esta olhando.

  O QUE ESTA FN NAO GARANTE (verificado por grep em `src/`, `demo/` e `resources/`; os tres sao o MESMO
  buraco — falta caminho de escrita de transicao de mandato em `cadastros`, e os tres viram numero publicado
  na fatia 6):
  - `fim_efetivo` NAO e' gravavel por nenhuma rota HTTP: o wire `RegistrarMandato` e' `:closed` e nao tem o
    campo, e `mudar-estado-mandato!` (o unico que o carimba) nao esta' exposto em
    `cadastros/diplomat/http/in.clj`. Ou seja, o fechamento do cassado/renunciado/falecido descrito acima
    esta' CORRETO mas hoje NAO ACONTECE em producao — o ramo so' e' exercitado por teste.
  - `estado` terminal com `fim_efetivo` nil nao fecha nada: `mudar-estado!` documenta `fim-efetivo` como
    opcional e o `[:coalesce ...]` preserva NULL. Esta fn IGNORA `estado` de proposito — nao ha data
    alternativa a usar, e inventar uma (p.ex. `hoje`) quebraria a pureza e congelaria o denominador.
  - `vigencia_fim` e' `{:optional true}` no wire e nao ha PATCH de mandato: mandato aberto = janela ABERTA,
    sem auto-cura. O suplente convocado sem data de retorno — o arquetipo que abriu o carry I-5 — recebe
    `{:fim nil}`, ou seja o denominador global deslocado para a data da convocacao.
  - as licencas chegam com validacao de intervalo PARCIAL: `registrar-licenca->dominio` recusa `inicio`
    posterior a `hoje` (licenca nao pode COMECAR no futuro — o flip de `mandato.estado` e' imediato e nao
    ha agendador), mas nao exige `fim >= inicio` nem que o intervalo caiba na vigencia do mandato, e nao ha
    constraint na mig 0010:148. Uma licenca com o ANO de `fim` digitado errado ainda zera a janela: ha'
    UPDATE de `fim` (a reassuncao, abaixo), mas ele so' casa `fim IS NULL`, e nao ha DELETE (Inv. 10) nem
    PATCH de licenca — entao licenca ja' FECHADA com data errada segue sem remedio dentro do sistema.

  CARRY FECHADO (reassuncao de mandato): licenca com `fim` nil (\"prazo indeterminado\", caminho de primeira
  classe no wire `RegistrarLicenca`) fecha a janela DAQUELE STINT na vespera do seu inicio, e isso DEIXOU de
  ser irreversivel — `cadastros` passou a expor `POST /cadastros/vereadores/:id/reassuncao`
  (`RepoCadastros/reassumir-mandato!`), que fecha as licencas abertas do stint na VESPERA de `reassumiu-em`
  (`db/vereador/encerrar-licencas-abertas!` — o PRIMEIRO e unico UPDATE de `mandato_licenca` do sistema) e
  devolve `mandato.estado` a 'vigente'. A convencao do -1 dia existe porque `subtrair-intervalos` e' inclusivo
  dos dois lados; quem a pina contra ESTA fn e' o teste
  `reassumir-em-D-fecha-a-licenca-em-D-menos-1-e-o-dia-D-conta-como-exercicio`. `reassumiu-em` tem teto em
  `hoje` na borda: uma data futura gravaria um `fim` que nenhum caminho de escrita alcanca depois.
  NAO use mais o contorno antigo (registrar um mandato NOVO 'vigente' para representar o retorno): o stint
  licenciado fica fora do predicado do EXCLUDE da mig 0059, entao o mandato novo entra — mas a reassuncao
  seguinte daquele stint passa a colidir (23P01 -> :conflito/mandato-sobreposto, 409)."
  [mandatos licencas]
  (let [licencas-do-stint (group-by :mandato-id licencas)
        janelas (tempo/normalizar-intervalos
                 (mapcat (fn [m]
                           (tempo/subtrair-intervalos
                            [{:inicio (:vigencia-inicio m)
                              :fim (tempo/menor-fim (:fim-efetivo m) (:vigencia-fim m))}]
                            (map #(select-keys % [:inicio :fim])
                                 (get licencas-do-stint (:id m)))))
                         mandatos))]
    (when (> (count janelas) teto-de-janelas)
      (throw (ex-info "janelas de exercicio do vereador acima do teto da rota publica de perfil"
                      {:tipo :limite/janelas-excedido :medido (count janelas) :teto teto-de-janelas})))
    janelas))

(defn ficha-e-janelas-publicas
  "Seam do host p/ a rota PUBLICA do perfil do vereador: devolve `{:ficha ... :janelas ...}`, ou nil se o
  vereador nao existe NESTA Casa (§22.10 — `transparencia` nunca importa `cadastros`; recebe esta fn ja'
  resolvida, mesma inversao de dependencia de `info-ente`/`membros-da-casa`).

  `:ficha` sao as MESMAS 4 chaves que `ficha-vereador` devolvia (`:vereador :mandato :legislatura
  :comissoes`) — o contrato de `transparencia/adapters/out/parlamentar` nao muda. `:janelas` e' a janela de
  exercicio (ver `janelas-de-exercicio`).

  UMA unica TRANSACAO (`ficha-e-mandatos-do-vereador`) — a MESMA que ja' rodava como guard de 404. Nao e'
  custo zero: sao 2 statements a mais que `ficha-vereador` (os stints + as licencas). O que se evita e' um
  SEGUNDO seam com BEGIN/SET LOCAL/COMMIT proprio numa rota anonima e sem cache.

  FAIL-CLOSED: o guard de 404 continua sendo a EXISTENCIA DA FICHA, e so' ela. Janela vazia NUNCA decide
  404 — o suplente que ainda nao tomou posse e' um parlamentar real e tem perfil; o que ele nao tem e'
  periodo de exercicio, e isso a fatia 6 publica explicitamente."
  [repo-cadastros ente-id vereador-id data]
  (when-let [composta (repo-cadastros-comp/ficha-e-mandatos-do-vereador
                       repo-cadastros ente-id vereador-id data)]
    {:ficha   (select-keys composta [:vereador :mandato :legislatura :comissoes])
     :janelas (janelas-de-exercicio (:mandatos composta) (:licencas composta))}))

(defn montar
  "Conjunto de rotas Pedestal (table syntax) a partir dos deps do servidor. `erro`/`cabecalhos` sao GLOBAIS
  (it/globais prepended em http/servico) — nao por rota. Aqui: `autenticacao` resolve o ator; `exige-papel`
  faz a authz grossa. As verticais de modulo (W3+) fundem seus fragmentos de rota (diplomat/http/in/rotas),
  recebendo o interceptor `auth` compartilhado + o Repo-Component do modulo. O HOST e' a raiz de composicao
  (§22.10): so ele cruza modulos — p/ o endpoint SSE (G3) e a vertical de votacao ao vivo (Slice 3, no
  legislativo) injeta `consultar-sessao` (delega ao Repo de sessoes) nos diplomats de tempo_real e legislativo,
  que NAO importam sessoes."
  [{:keys [idp repo-identidade repo-sessoes repo-legislativo repo-compliance repo-participacao
           repo-transparencia repo-paineis repo-cadastros canal-store objeto-store painel-compliance
           presenca-resumo esic-cumprimento relatores-pendentes info-ente registro-fatos
           keycloak sessao identidade-existe?]
    ;; nome LOCAL distinto da defn de topo `ficha-e-janelas-publicas` p/ nao sombrea-la (mesmo cuidado de
    ;; `resolver-vereador`/`resolver-vereador-fn`); a chave do mapa segue sendo :ficha-e-janelas-publicas.
    ficha-e-janelas-override :ficha-e-janelas-publicas}]
  (let [auth (it/autenticacao idp repo-identidade)
        ;; F6: relogio de producao (kernel/tempo) p/ o prazo LAI do e-SIC — determinismo em teste vem de
        ;; injetar relogio-fixo direto no fragmento de rotas (participacao-http/rotas). resolver-ente-publico
        ;; = seam da rota PUBLICA (sem ator): mapeia o :ente do path -> ente-id (V1 = UUID coagido fail-closed);
        ;; o Repo abre com-tenant* com ele e a RLS isola. Slug humano = refino futuro.
        ;; relogio de PRODUCAO compartilhado (stateless — le o relogio do sistema a cada chamada, kernel/tempo):
        ;; participacao (prazo LAI) e legislativo (Onda B Slice 5, `agora` do gatilho de emissao do parecer,
        ;; review MEDIUM fe-11-parecer) usam a MESMA instancia; determinismo em teste vem de cada fragmento de
        ;; rotas receber `relogio-fixo` no lugar, direto.
        relogio-producao (tempo/relogio-sistema)
        ;; cross-modulo via inversao de dependencia: o host fecha sobre o Repo de sessoes e expoe a consulta-fato
        ;; que o endpoint SSE (G3) E a vertical de votacao ao vivo (F4 Slice 3, no legislativo) precisam p/
        ;; autorizar (a RLS escopa por tenant). Os modulos chamam por esta fn, nunca importam sessoes (§22.10).
        consultar-sessao (fn [ente-id sessao-id] (repo-sessoes-comp/buscar-sessao repo-sessoes ente-id sessao-id))
        ;; T2 grupo A achado #4/#5 (ledger de prontidao Fase 8): a votacao ao vivo (legislativo) precisa saber
        ;; se a sessao ja FECHOU p/ recusar abrir/registrar-voto/encerrar — mas legislativo NAO importa
        ;; `sessoes.logic` (§22.10, cross-modulo so por HTTP/eventos/injecao). MESMA inversao de dependencia de
        ;; `consultar-sessao` acima: o host fecha sobre o vocabulario REAL (`estados-sessao-fechada`, a MESMA
        ;; particao que `sessoes.controllers/exigir-sessao-aberta!` usa) em vez de deixar o legislativo duplicar
        ;; o conjunto.
        sessao-fechada? (fn [sessao] (contains? sessoes-logic/estados-sessao-fechada (:estado sessao)))
        ;; Carry telao (Daouda, 12/09/2026): `GET /sessoes/:id/votacao-aberta` tinha o MESMO buraco que a
        ;; Etapa 4a ja' fechou para `/quorum`/`/tribuna`/`/composicao` — a borda exigia papel 'vereador'
        ;; ESTRITO, entao o telao da Mesa (papel 'secretario') tomava 403 na unica rota que recupera a
        ;; votacao aberta apos a retencao MINID de ~5min do canal. MESMA inversao de dependencia de
        ;; `sessao-fechada?` acima (legislativo NAO importa sessoes, §22.10).
        ;;
        ;; Revisao do Daouda (12/09/2026): a politica NAO e' `pode-ver-quorum-da-sessao?` pura — ganha uma
        ;; terceira clausula, 'vereador'. O argumento e' o PROPRIO argumento da docstring daquela fn ("o
        ;; gate e' de PUBLICO — quem so' assiste ao telao — nao de sessao; 'secretario' nao afrouxa nada
        ;; porque ja' lia esse estado, nominalmente, por `/chamada`"): numa sessao secreta o vereador VOTA
        ;; (`/meu-voto` e' gated 'vereador') — quem tem direito de REGISTRAR o voto tem, por construcao,
        ;; direito de saber que a votacao esta' aberta. Nega-lo aqui nao protege sigilo nenhum; so' devolve
        ;; o defeito que esta fatia existe para consertar, no cenario de maior consequencia (sessao
        ;; fechada, vereador que recarregou a pagina e nao vota).
        ;;
        ;; NAO e' um quarto predicado, e' o MESMO predicado + uma clausula: reusa `pode-ver-quorum-da-sessao?`
        ;; (a formula toda, sem desmontar o AND/OR dela) OR'ado com `pode-ver-sessao? AND papel vereador`.
        ;; A FORMA importa — a disjuncao entra DENTRO da conjuncao de mesma-Casa, nunca por fora:
        ;;   (or (pode-ver-quorum-da-sessao? a s) (and (pode-ver-sessao? a s) (papel vereador)))
        ;; = (or (and mesmaCasa (or publica secretario)) (and mesmaCasa vereador))
        ;; = (and mesmaCasa (or publica secretario vereador))                      [distributiva sobre AND]
        ;; — nunca `(or (papel vereador) (pode-ver-quorum-da-sessao? a s))`, que deixaria passar um
        ;; vereador de OUTRA Casa (a clausula de papel, isolada, nao teria por onde escopar ao tenant).
        ;; Cada ramo desta OR ja' checa `pode-ver-sessao?`/`pode-ver-quorum-da-sessao?` (mesma Casa embutida
        ;; nos dois), entao a forma acima e' fail-closed por construcao, nao por disciplina de quem le'.
        pode-ver-votacao-aberta?
        (fn [ator sessao]
          (or (sessoes-logic/pode-ver-quorum-da-sessao? ator sessao)
              (and (sessoes-logic/pode-ver-sessao? ator sessao)
                   (contains? (:papeis ator) "vereador"))))
        ;; FE Onda A1: membros-da-casa injetado em sessoes (presenca agregada) — mesma inversao de
        ;; dependencia de consultar-sessao/painel-compliance; fuso civil vindo do kernel
        ;; (`tempo/zona-civil-padrao`, I-5 fatia 2 — antes era literal aqui), mesmo racional de
        ;; participacao/controllers.clj.
        membros-da-casa (fn [ente-id]
                          (repo-cadastros-comp/membros-da-casa repo-cadastros ente-id
                                                                (tempo/hoje (tempo/relogio-sistema)
                                                                            tempo/zona-civil-padrao)))
        ;; §22.6 eixo C (fatia 1b-WIRE): a CHAMADA precisa do roster NUMA DATA QUE NAO E' HOJE (a data da
        ;; sessao sendo lida, resolvida pelo controller de sessoes a partir da propria sessao) — por isso
        ;; a aridade leva `data` como parametro, ao contrario de `membros-da-casa`/`resolver-vereador-fn`
        ;; acima (que fecham 'hoje' aqui dentro): reabrir a chamada de uma sessao do mes passado com 'hoje'
        ;; fechado no seam mostraria a composicao de HOJE, nao a de entao. Mesma inversao de dependencia
        ;; sobre cadastros (sessoes nunca importa cadastros, §22.10); irmao LITERAL de membros-da-casa.
        roster-da-casa-fn (fn [ente-id data] (repo-cadastros-comp/roster-da-casa repo-cadastros ente-id data))
        ;; Etapa 6 fatia 1: o LOTE de `roster-da-casa-fn` para VARIAS datas — irmao LITERAL, mesma inversao
        ;; de dependencia sobre `cadastros` (sessoes nunca importa cadastros, §22.10), aridade levando
        ;; `datas` pelo MESMO motivo (nunca fechar 'hoje' aqui dentro). Consumido por
        ;; `controllers/apurar-assiduidade` (Etapa 6 fatia 2) via a rota `GET /assiduidade` (Etapa 6 fatia 3).
        roster-da-casa-em-datas-fn (fn [ente-id datas]
                                     (repo-cadastros-comp/roster-da-casa-em-datas repo-cadastros ente-id datas))
        ;; Modo TV (docs/22): o resumo (sigla/numero/ementa) das proposicoes da PAUTA, em lote — sessoes nunca
        ;; importa legislativo (§22.10), entao o host fecha sobre o Repo de legislativo, mesma inversao de
        ;; dependencia de `consultar-sessao`. Consumido pelo `pauta-handler` (que degrada se isto falhar).
        resumir-proposicoes-fn (fn [ente-id ids]
                                 (repo-legislativo-comp/resumos-de-proposicoes repo-legislativo ente-id ids))
        ;; Etapa 5 fatia 1: o cabecalho da FOLHA (nome/legislatura da Casa) — seam irmao LITERAL de
        ;; `roster-da-casa-fn` acima, mesma inversao de dependencia sobre `cadastros` (sessoes nunca importa
        ;; cadastros, §22.10). Leva `data` na aridade pelo MESMO motivo de `roster-da-casa-fn` (nunca fechar
        ;; 'hoje' aqui dentro: reabrir a folha de uma sessao do mes passado com hoje fechado no seam
        ;; mostraria a composicao de hoje) — CARRY: `cadastros/legislatura-vigente` hoje so' le' o flag
        ;; `vigente` corrente (nao ha' consulta por DATA em `cadastros`), entao `data` ainda nao MUDA a
        ;; legislatura resolvida; o parametro existe para o seam nao precisar de uma segunda mudanca de
        ;; assinatura no dia em que essa consulta existir.
        dados-da-casa-fn (fn [ente-id _data]
                           (let [ente (repo-cadastros-comp/buscar-ente repo-cadastros ente-id)
                                 leg  (repo-cadastros-comp/legislatura-vigente repo-cadastros ente-id)]
                             {:nome-oficial (:nome-oficial ente)
                              :nome-curto (:nome-curto ente)
                              :legislatura-numero (:numero leg)
                              :legislatura-ano-inicio (:ano-inicio leg)
                              :legislatura-ano-fim (:ano-fim leg)}))
        ;; Etapa 5 fatia 5: os DOIS ports da folha, construidos UMA vez aqui (nunca dentro do handler HTTP,
        ;; que os recriaria a cada request sem ganho) — mesma disciplina de `serializador-fixture`/
        ;; `serializador-remessa` ("o host constroi e injeta"). JA' DECORADOS com as guardas: o serializador
        ;; com o TETO de tamanho de ENTRADA; o renderizador com TIMEOUT + TETO de SAIDA + POOL DEDICADO. As
        ;; duas primeiras sao obrigacoes que a revisao de seguranca da fatia 3 do brief deixou pendentes ate'
        ;; existir superficie HTTP (`POST /sessoes/:id/folha`, fiada abaixo, e' essa superficie); as outras
        ;; duas vieram da revisao adversarial da fatia 5 (o teto era assimetrico; o timeout limitava latencia,
        ;; nao consumo).
        ;; docs/23 Fatia 5: quem congelou cada versao da folha, pelo NOME (so' de quem tem vinculo nesta Casa) —
        ;; sessoes nunca importa identidade (§22.10), mesma inversao de dependencia dos seams acima.
        nome-na-casa-fn (fn [ente-id identidade-id] (nome-na-casa repo-identidade ente-id identidade-id))
        serializador-folha-fn (serializador-folha/serializador-folha-html-com-teto)
        renderizador-pdf-fn (renderizador-pdf/renderizador-pdf-guardado)
        ;; Onda B Slice 2: uf/nome-do-municipio do ente, p/ o legislativo computar a URN em protocolar! —
        ;; mesma inversao de dependencia de consultar-sessao/membros-da-casa/info-ente (§22.10).
        resolver-municipio (fn [ente-id] (repo-cadastros-comp/uf-e-municipio repo-cadastros ente-id))
        ;; Onda C1: identidade->vereador-id NESTA Casa, injetado na borda /meu do legislativo (mesma
        ;; inversao de dependencia de resolver-municipio/membros-da-casa; nome DISTINTO do defn de topo
        ;; `resolver-vereador` p/ nao sombrear — a chave passada a legislativo-http/rotas continua
        ;; :resolver-vereador).
        resolver-vereador-fn (fn [ente-id identidade-id] (resolver-vereador repo-cadastros ente-id identidade-id))
        ;; #11: a metade de `cadastros` do nome da comissao, para o `legislativo` (ver `resolver-comissoes`).
        resolver-comissoes-fn (fn [ente-id ids] (resolver-comissoes repo-cadastros ente-id ids))
        ;; Fix da review Onda E fatia 2 (achados I-1+M-1): autor-id cru do corpo de POST/PATCH proposicao
        ;; vira o elo de autoria PUBLICA (transparencia.materia) — precisa apontar pra um vereador de
        ;; verdade NESTE ente antes de virar afirmacao publica. Mesma inversao de dependencia de
        ;; resolver-vereador/resolver-municipio; reusa `buscar-vereador` (ja' ente-escopado, RLS + filtro
        ;; explicito) — "vinculo" aqui e' CADASTRO existente neste ente, mesmo contrato fraco de
        ;; resolver-vereador (nao exige mandato vigente; apertar p/ so' mandato ativo fica de carry se um
        ;; cliente pedir). `legislativo` recebe so' esta fn ja' resolvida, nunca importa cadastros (§22.10).
        vereador-vinculado? (fn [ente-id vereador-id]
                              (some? (repo-cadastros-comp/buscar-vereador repo-cadastros ente-id vereador-id)))
        ;; sec MEDIUM-2 FIX (gate #2): a rota da Mesa `registrar-voto` recebe `vereador-id` do CORPO e so'
        ;; checava nao-nulo — um secretario comprometido registraria voto nominal para um vereador FORA do
        ;; roster (id inexistente, de outra Casa, ou com mandato encerrado/licenciado), inflando o placar. O
        ;; `meu-voto` (celular) ja' valida mandato-vigente+presenca via policy-fina; a rota da Mesa nao tinha
        ;; equivalente. Este predicado responde "o vereador-id compoe a Casa com mandato VIGENTE hoje?" — o
        ;; MESMO conjunto que `membros-da-casa` (gate #1) conta: reusa `roster-da-casa` (que traz vigente E
        ;; licenciado, marcados) e mantem SO 'vigente' aqui, alinhado ao denominador do quorum. Mesma inversao
        ;; de dependencia de consultar-sessao/membros-da-casa (legislativo NAO importa cadastros, §22.10); fuso
        ;; civil como os demais seams datados. Um voto ao vivo exige composicao de HOJE (nao ha `data` na aridade).
        vereador-no-roster? (fn [ente-id vereador-id]
                              (boolean
                               (some (fn [l] (and (= vereador-id (:vereador-id l))
                                                  (= "vigente" (:estado-mandato l))))
                                     (repo-cadastros-comp/roster-da-casa repo-cadastros ente-id
                                       (tempo/hoje (tempo/relogio-sistema) tempo/zona-civil-padrao)))))
        ;; Override injetavel (mesmo racional de `painel-compliance` — so' serve aos testes DB-free da borda
        ;; de paineis); em producao `montar` e' chamado sem estas chaves e o `or` fecha sobre o repo real.
        presenca-resumo (or presenca-resumo
                            (fn [ente-id] (sessoes-http/presenca-resumo-wire repo-sessoes membros-da-casa ente-id)))
        ;; FE Onda A1: cumprimento de prazo do e-SIC injetado no dashboard da Mesa (mesma inversao de
        ;; dependencia; consumido por /paineis/mesa — task A6).
        esic-cumprimento (or esic-cumprimento
                             (fn [ente-id] (participacao-http/esic-cumprimento-wire repo-participacao ente-id)))
        ;; FE Onda A1: fila de relatores pendentes (self-contained no legislativo — sem cross-modulo);
        ;; consumido por /paineis/mesa (mesmo padrao de presenca-resumo/esic-cumprimento — task A6).
        relatores-pendentes (or relatores-pendentes
                                (fn [ente-id] (legislativo-http/relatores-pendentes-wire repo-legislativo ente-id)))
        ;; F7 dashboard da Mesa: o host compoe compliance+paineis por INVERSAO DE DEPENDENCIA (espelha
        ;; consultar-sessao). Fecha sobre o repo de compliance e expoe uma fn (ente-id -> PainelOut projetado)
        ;; que o diplomat de paineis chama — paineis nunca importa compliance (§22.10). Passa pelo diplomat de
        ;; compliance (painel-wire), nunca pelo seu adapters/out direto (a lint proibe host->adapters). O
        ;; override injetavel (`painel-compliance` no arg) so' serve aos testes DB-free da borda de paineis.
        painel-compliance (or painel-compliance
                              (fn [ente-id] (compliance-http/painel-wire repo-compliance ente-id)))
        ;; FE Onda A2 fast-follow: nome real do ente injetado no portal publico (barra institucional/rodape
        ;; mostravam o UUID cru da rota) — mesma inversao de dependencia de consultar-sessao/membros-da-casa;
        ;; transparencia nunca importa cadastros (§22.10). Ente sem perfil cadastrado -> nil -> 404 na borda.
        info-ente (or info-ente
                      (fn [ente-id] (repo-cadastros-comp/buscar-ente repo-cadastros ente-id)))
        ;; Onda E fatia 2 (Task 4) + I-5 fatia 4: a IDENTIDADE do vereador no perfil PUBLICO **mais** a
        ;; JANELA DE EXERCICIO do mandato — irmao de `info-ente`, mesma inversao de dependencia
        ;; (transparencia nunca importa cadastros, §22.10). A `data` e' a 4a aridade e decide mandato
        ;; vigente + comissoes vigentes: usa o mesmo `hoje` no fuso civil de `membros-da-casa` (nao
        ;; `LocalDate/now` do fuso do container — um deploy em UTC viraria o dia 3h antes e um mandato
        ;; encerrado ontem ainda apareceria vigente). Vereador inexistente NESTA Casa -> nil -> 404
        ;; fail-closed na borda (nunca 200 com perfil vazio); janela vazia NAO e' 404.
        ficha-e-janelas-fn
        (or ficha-e-janelas-override
            (fn [ente-id vereador-id]
              (ficha-e-janelas-publicas repo-cadastros ente-id vereador-id
                                        (tempo/hoje (tempo/relogio-sistema)
                                                    tempo/zona-civil-padrao))))
        ;; Onda D Slice 5 Task 9: guard de SERVICO — cadastros NUNCA importa identidade (§22.10) e nao ha'
        ;; FK cross-schema em cadastros.vereador.identidade_id (so' GUARD ref). O host injeta a existencia
        ;; via o Repo-Component de identidade (`identidade-existe?`, SUPRATENANT); mesma inversao de
        ;; dependencia de info-ente/consultar-sessao/resolver-municipio. Override injetavel p/ os testes
        ;; DB-free da borda de cadastros. Review Task 12 IMPORTANT: usa a leitura ESTREITA
        ;; `repo/identidade-existe?` (SELECT 1), NAO `identidade-por-id` — este guard so' precisa de um
        ;; booleano e nao deveria materializar CPF+nome so' pra jogar os dois fora.
        identidade-existe? (or identidade-existe?
                               (fn [ident-id] (repo-identidade-comp/identidade-existe? repo-identidade ident-id)))
        ;; Onda D Slice 2 Task 3: identidade/auth-in (GET /auth/descoberta/:ente, rota PUBLICA pre-login)
        ;; reusa este MESMO `info-ente` (existencia = `(some? (info-ente id))`) — inversao de dependencia
        ;; sobre cadastros, mesma forma de resolver-municipio/membros-da-casa; identidade nunca importa
        ;; cadastros (§22.10). Tambem expoe o nome PUBLICO do ente na resposta de descoberta.
        ;; `keycloak` = o bloco :keycloak da config (realm-prefixo/base-url-publico/web-client-id) que
        ;; auth-in usa p/ montar a resposta de descoberta. Injetavel p/ os testes DB-free da borda;
        ;; em producao cai no default carregado do config.edn+env (mesmo racional dos demais seams `or`).
        keycloak (or keycloak (:keycloak (config/carregar)))
        ;; Onda D Slice 2 Task 4: POST /auth/sessoes (mint) precisa do bloco :sessao da config
        ;; (:absoluta-h/:ociosa-min) — mesmo padrao `or` de `keycloak`/`info-ente` acima (fallback pra
        ;; config/carregar aqui no HOST; auth-http/rotas recebe ja' resolvido, nunca chama config/carregar
        ;; ela mesma).
        sessao (or sessao (:sessao (config/carregar)))]
    (-> #{["/saude"             :get http/saude :route-name :saude]
          ["/eu"                :get [auth http/eu] :route-name :eu]
          ["/painel-secretaria" :get [auth (it/exige-papel "secretario") http/painel-secretaria]
           :route-name :painel-secretaria]}
        (into (sessoes-http/rotas {:auth auth :repo-sessoes repo-sessoes :objeto-store objeto-store
                                   :resolver-vereador resolver-vereador-fn :relogio relogio-producao
                                   :roster-da-casa roster-da-casa-fn
                                   ;; Etapa 6 fatia 1: `roster-da-casa-em-datas-fn` fia o roster do periodo
                                   ;; para `controllers/apurar-assiduidade`, exposta pela rota `GET
                                   ;; /assiduidade` (Etapa 6 fatia 3).
                                   :roster-da-casa-em-datas roster-da-casa-em-datas-fn
                                   :resumir-proposicoes resumir-proposicoes-fn
                                   ;; Etapa 5 fatia 1: `dados-da-casa-fn` chega pronto para a Fatia 5 (as
                                   ;; rotas HTTP da folha) fiar o cabecalho — sem rota nova nesta fatia,
                                   ;; `sessoes-http/rotas` ainda nao destrutura a chave (chave extra e'
                                   ;; inocua p/ um mapa nao-closed).
                                   :dados-da-casa dados-da-casa-fn
                                   ;; Etapa 5 fatia 5: os dois ports da folha, ja' construidos+decorados acima.
                                   :serializador-folha serializador-folha-fn
                                   :renderizador-pdf renderizador-pdf-fn
                                   :nome-na-casa nome-na-casa-fn}))
        (into (legislativo-http/rotas {:auth auth :repo-legislativo repo-legislativo
                                       :consultar-sessao consultar-sessao
                                       :sessao-fechada? sessao-fechada?
                                       :pode-ver-votacao-aberta? pode-ver-votacao-aberta?
                                       :resolver-municipio resolver-municipio
                                       :resolver-vereador resolver-vereador-fn
                                       :resolver-comissoes resolver-comissoes-fn
                                       :vereador-vinculado? vereador-vinculado?
                                       ;; sec MEDIUM-2 FIX: gate #2 — a rota da Mesa so' registra voto nominal
                                       ;; para quem compoe a Casa com mandato vigente (roster). Mesmo seam
                                       ;; injetado, mesma inversao de dependencia sobre cadastros (§22.10).
                                       :vereador-no-roster? vereador-no-roster?
                                       ;; sec MEDIUM-1 FIX: o denominador do quorum (base-membros) e' computado
                                       ;; SERVER-SIDE no encerramento a partir da composicao real da Casa —
                                       ;; nunca mais do corpo do request. Reusa o MESMO seam `membros-da-casa`
                                       ;; ja' injetado em sessoes (hoje no fuso civil), mesma inversao de
                                       ;; dependencia de consultar-sessao (legislativo NAO importa cadastros, §22.10).
                                       :membros-da-casa membros-da-casa
                                       :registro registro-fatos
                                       :relogio relogio-producao}))
        (into (compliance-http/rotas {:auth auth :repo-compliance repo-compliance}))
        (into (cadastros-http/rotas {:auth auth :repo-cadastros repo-cadastros :relogio relogio-producao
                                     :identidade-existe? identidade-existe?}))
        (into (participacao-http/rotas {:auth auth :repo-participacao repo-participacao
                                        :resolver-ente-publico participacao-http/resolver-ente-publico-uuid
                                        :relogio relogio-producao}))
        (into (transparencia-http/rotas {:auth auth :repo-transparencia repo-transparencia
                                         :resolver-ente-publico transparencia-http/resolver-ente-publico-uuid
                                         :objeto-store objeto-store
                                         :info-ente info-ente
                                         ;; I-5 fatia 6: a borda passou a CONSUMIR o mapa inteiro
                                         ;; ({:ficha :janelas}) — a ficha decide o 404 e a janela recorta o
                                         ;; denominador de presenca. O host nao desembrulha mais nada.
                                         :ficha-e-janelas-publicas ficha-e-janelas-fn}))
        (into (paineis-http/rotas {:auth auth :repo-paineis repo-paineis
                                   :painel-compliance painel-compliance
                                   :presenca-resumo presenca-resumo
                                   :esic-cumprimento esic-cumprimento
                                   :relatores-pendentes relatores-pendentes}))
        (into (tempo-real-sse/rotas {:auth auth :canal-store canal-store :consultar-sessao consultar-sessao}))
        (into (auth-http/rotas {:info-ente info-ente :keycloak keycloak
                                :idp idp :repo-identidade repo-identidade
                                :relogio relogio-producao :sessao sessao}))
        (into (identidade-http/rotas {:auth auth :repo-identidade repo-identidade :idp idp})))))
