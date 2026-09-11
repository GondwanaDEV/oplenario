(ns oplenario.legislativo.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do legislativo (§22.10 diplomat/http/in, ADR-0001): TRES verticais moram
  aqui (docstring atualizada — review MENOR fe-9-ficha-materia: a lista estava presa em 'DUAS verticais' e
  ja' nao refletia a silhueta real das rotas). (1) a votacao ao vivo (F4 Slice 3) — abrir / registrar voto /
  encerrar. A rota mora AQUI (legislativo e' o DONO do agregado votacao + da tx que casa ato+emissao, Slice
  1), nao no `sessoes` — espelha o SSE `/sessoes/:id/plenario` que mora no `tempo_real` (prefixo de URL !=
  dono do modulo). (2) CRUD+detalhe de proposicoes (Onda B Slices 1-2) — listar (tenant-wide, paginado),
  criar, detalhe, editar (PATCH parcial + versao 'edicao'). (3) ficha da materia (Onda B Slice 3) — leitura
  agregada cross-eixo (proposicao+texto+tramitacao+apensadas+emendas+pareceres) NUMA rota so'. Todas so' o
  gate grosso (papel 'secretario'), sem authz herdada de sessao. O diplomat e' a UNICA camada que cruza o
  gate de borda (adapters/in na entrada, adapters/out na saida); o controller trabalha so em models. Na
  vertical de votacao a authz e' HERDADA do recurso SESSAO via `consultar-sessao` INJETADA pelo host
  (legislativo NAO importa sessoes, §22.10): a grossa (exige-papel) na rota, a fina
  (policy.check/pode-dirigir-votacao?) no controller."
  (:require [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.autorizacao :as authz]
            [oplenario.kernel.tempo :as tempo]
            [oplenario.legislativo.adapters.in.ciencia :as adapters-in-ciencia]
            [oplenario.legislativo.adapters.in.documento :as adapters-in-documento]
            [oplenario.legislativo.adapters.in.parecer :as adapters-in-parecer]
            [oplenario.legislativo.adapters.in.proposicao :as adapters-in-proposicao]
            [oplenario.legislativo.adapters.in.votacao :as adapters-in]
            [oplenario.legislativo.adapters.out.documento :as adapters-out-documento]
            [oplenario.legislativo.adapters.out.documento-modelo :as adapters-out-documento-modelo]
            [oplenario.legislativo.adapters.out.ficha-materia :as adapters-out-ficha]
            [oplenario.legislativo.adapters.out.meu-painel :as adapters-out-meu-painel]
            [oplenario.legislativo.adapters.in.pos-aprovacao :as adapters-in-pos-aprovacao]
            [oplenario.legislativo.adapters.out.autografo :as adapters-out-autografo]
            [oplenario.legislativo.adapters.out.parecer :as adapters-out-parecer]
            [oplenario.legislativo.adapters.out.pos-aprovacao :as adapters-out-pos-aprovacao]
            [oplenario.legislativo.adapters.out.proposicao :as adapters-out-proposicao]
            [oplenario.legislativo.adapters.out.protocolo-geral :as adapters-out-protocolo]
            [oplenario.legislativo.adapters.out.relator-pendente :as adapters-out-relator]
            [oplenario.legislativo.adapters.out.tramitacao-executiva :as adapters-out-tramitacao-executiva]
            [oplenario.legislativo.adapters.out.votacao :as adapters-out]
            [oplenario.legislativo.components.assinador-icp :as assinador-icp]
            [oplenario.legislativo.controllers :as controllers])
  (:import (java.time ZoneId)))

(set! *warn-on-reflection* true)

;; fuso civil p/ `agora` (LocalDate) do gatilho de emissao do parecer — prazos/regras do motor operam em
;; data civil, nao UTC (review MEDIUM fe-11-parecer); mesma constante de participacao.controllers/zona-civil.
(def ^:private zona-civil (ZoneId/of "America/Fortaleza"))

(defn- resposta-conflito-sessao-fechada
  "Traduz `:conflito/sessao-fechada` -> 409 (T2 grupo A achado #4/#5, ledger de prontidao Fase 8): a sessao ja
  fechou e a votacao (abrir/votar/meu-voto/encerrar) esta bloqueada. Mensagem do dominio via ex-message —
  mesma disciplina de `sessoes.diplomat.http.in/resposta-conflito-sessao-fechada` (tag identica cross-modulo,
  cada diplomat traduz a SUA borda)."
  [e]
  (http/json-resposta 409 {:erro (ex-message e)}))

(defn- abrir-handler
  "POST /sessoes/:id/votacoes. corpo-json -> :json-params; adapters/in valida+coage+injeta id/autor; controller
  autoriza na sessao (:id) e abre; adapters/out projeta o recibo. nil (sessao inexistente) -> 404; sessao ja
  fechada (`:conflito/sessao-fechada`) -> 409 (ledger Fase 8 achado #5)."
  [repo-leg consultar-sessao sessao-fechada?]
  (fn [req]
    (let [ator (:ator req)
          sid  (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          m    (adapters-in/abrir-votacao->dominio ator (:json-params req))]
      (try
        (if-let [recibo (controllers/abrir-votacao repo-leg consultar-sessao sessao-fechada? ator sid m)]
          (http/json-resposta 201 (adapters-out/abertura->wire recibo))
          (http/json-resposta 404 {:erro "sessao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/sessao-fechada (:tipo (ex-data e)))
            (resposta-conflito-sessao-fechada e)
            (throw e)))))))

(defn- voto-handler
  "POST /sessoes/:id/votacoes/:votacao-id/votos. Authz na sessao + amarra votacao<->sessao; dispatch por
  modalidade no controller. nil (votacao inexistente ou de outra sessao) -> 404. T2 grupo A achado #1 (ledger
  Fase 8): o 2o voto NOMINAL do MESMO vereador batia no UNIQUE
  `votos_ente_id_votacao_id_vereador_id_key` e a PSQLException subia CRUA ate' o interceptor global -> 500
  ('erro interno'). O irmao self-service `meu-voto-handler` (abaixo) JA tratava isto — so' esta rota (a Mesa
  registrando votos nominais) nao tinha o catch. ESPELHA o irmao: mesma tag `:conflito/voto-duplicado`
  (lancada agora tambem por `repo/registrar-voto!`, nao so' `registrar-meu-voto!`) -> 409. Sessao ja fechada
  (`:conflito/sessao-fechada`) -> 409 (ledger Fase 8 achado #4/#5)."
  [repo-leg consultar-sessao sessao-fechada?]
  (fn [req]
    (let [ator (:ator req)
          sid  (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          vid  (adapters-in/id-param->uuid (get-in req [:path-params :votacao-id]))
          m    (adapters-in/registrar-voto->dominio ator vid (:json-params req))]
      (try
        (if-let [recibo (controllers/registrar-voto repo-leg consultar-sessao sessao-fechada? ator sid vid m)]
          (http/json-resposta 201 (adapters-out/voto->wire recibo))
          (http/json-resposta 404 {:erro "votacao nao encontrada nesta sessao"}))
        (catch clojure.lang.ExceptionInfo e
          (case (:tipo (ex-data e))
            :conflito/voto-duplicado
            (http/json-resposta 409 {:erro "voto ja registrado para este vereador nesta votacao"})
            :conflito/sessao-fechada (resposta-conflito-sessao-fechada e)
            (throw e)))))))

(defn- meu-voto-handler
  "POST /sessoes/:id/votacoes/:votacao-id/meu-voto (Onda C3, papel 'vereador'). `hoje`/`instante` resolvidos
  AQUI, na borda (mesmo padrao de emitir-parecer-handler/`agora`) — o controller nao le o relogio. Um
  double-tap/retry do celular (2 requests concorrentes do MESMO vereador) serializa pelo lock `FOR UPDATE`
  em `registrar-meu-voto!` e o 2o bate no UNIQUE -> `:conflito/voto-duplicado` -> 409 (nunca 500 opaco;
  mesmo padrao de :conflito/transicao|inscricao|fala neste modulo/sessoes). Sessao ja fechada
  (`:conflito/sessao-fechada`) -> 409 (ledger Fase 8 achado #4/#5)."
  [repo-leg consultar-sessao sessao-fechada? resolver-vereador registro relogio]
  (fn [req]
    (let [ator (:ator req)
          sid  (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          vid  (adapters-in/id-param->uuid (get-in req [:path-params :votacao-id]))
          instante (tempo/agora relogio)
          hoje (tempo/hoje-de instante zona-civil)
          m    (adapters-in/meu-voto->dominio ator vid (:json-params req))]
      (try
        (if-let [recibo (controllers/meu-voto repo-leg consultar-sessao sessao-fechada? resolver-vereador registro ator sid vid hoje instante m)]
          (http/json-resposta 201 (adapters-out/voto->wire recibo))
          (http/json-resposta 404 {:erro "vereador sem cadastro vinculado, ou sessao/votacao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (case (:tipo (ex-data e))
            :conflito/voto-duplicado
            (http/json-resposta 409 {:erro "voto ja registrado para este vereador nesta votacao"})
            :conflito/sessao-fechada (resposta-conflito-sessao-fechada e)
            (throw e)))))))

(defn- encerrar-handler
  "POST /sessoes/:id/votacoes/:votacao-id/encerramento. Apura + grava o snapshot (CAS); adapters/out projeta os
  totais. nil (votacao inexistente ou de outra sessao) -> 404. Votacao ja terminal -> `:conflito/votacao-
  terminal` -> 409 (T2 grupo A achado #3, ledger Fase 8 — ERA 400, corrigido: 409 e' o codigo certo p/
  'seu pedido era valido, o recurso mudou'). Sessao ja fechada (`:conflito/sessao-fechada`) -> 409 (achado
  #4/#5)."
  [repo-leg consultar-sessao sessao-fechada?]
  (fn [req]
    (let [ator (:ator req)
          sid  (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          vid  (adapters-in/id-param->uuid (get-in req [:path-params :votacao-id]))
          m    (adapters-in/encerrar-votacao->dominio ator vid (:json-params req))]
      (try
        (if-let [snap (controllers/encerrar-votacao repo-leg consultar-sessao sessao-fechada? ator sid vid m)]
          (http/json-resposta 200 (adapters-out/encerramento->wire snap))
          (http/json-resposta 404 {:erro "votacao nao encontrada nesta sessao"}))
        (catch clojure.lang.ExceptionInfo e
          (case (:tipo (ex-data e))
            :conflito/votacao-terminal
            (http/json-resposta 409 {:erro "votacao ja em estado terminal (encerrada/anulada)"})
            :conflito/sessao-fechada (resposta-conflito-sessao-fechada e)
            (throw e)))))))

(defn- listar-proposicoes-handler
  "GET /legislativo/proposicoes(?busca=&tipo=&estado=&autor-id=&ano=&pagina=&tamanho=&ordenar-por=&ordenar-dir=).
  Leitura tenant-wide (mesmo contrato de authz de /paineis/*, Onda B Slice 1): adapters/in coage os filtros
  (fail-closed -> 400); o controller le' do tenant do ator; adapters/out projeta+valida."
  [repo-leg]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          filtro (adapters-in-proposicao/listar-proposicoes->dominio (:query-params req))]
      (http/json-resposta 200 (adapters-out-proposicao/listar->wire
                                (controllers/listar-proposicoes repo-leg ente-id filtro))))))

(defn- criar-proposicao-handler
  "POST /legislativo/proposicoes. Cria + relê o detalhe (o Repo devolve so' {:id :sequencial :urn-lex}).
  `vereador-vinculado?` (injetada pelo host) valida `autor-id` no controller (achados I-1/M-1 da review) —
  incoerente/sem vinculo -> :validacao/invalido, o interceptor global `erro` traduz -> 400."
  [repo-leg resolver-municipio vereador-vinculado?]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          m (adapters-in-proposicao/criar-proposicao->dominio ator (:json-params req))]
      (controllers/criar-proposicao repo-leg resolver-municipio vereador-vinculado? ente-id m)
      (let [{:keys [proposicao texto]} (controllers/buscar-proposicao-ficha repo-leg ente-id (:id m))]
        (http/json-resposta 201 (adapters-out-proposicao/detalhe->wire proposicao texto))))))

(defn- detalhe-proposicao-handler
  "GET /legislativo/proposicoes/:id."
  [repo-leg]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [{:keys [proposicao texto]} (controllers/buscar-proposicao-ficha repo-leg ente-id id)]
        (http/json-resposta 200 (adapters-out-proposicao/detalhe->wire proposicao texto))
        (http/json-resposta 404 {:erro "proposicao nao encontrada"})))))

;; ========================= Fatia 2: a BORDA da tramitacao (eixo C) =========================

(defn- guard-inavaliavel?
  "A excecao veio do AVALIADOR da DSL (motor), e nao do dominio? `:erro :runtime` (fato sem fn no registry,
  identificador sem valor, tipo nao-booleano) e `:erro :sintaxe` (guard que nao parseia — `criar-transicao!`
  gateia no save, mas uma linha escrita por import/SQL direto escapa desse gate) sao as duas formas. Olha
  tambem a CAUSA, mesma disciplina do `raiz` do interceptor global — a tx do Repo pode reembrulhar."
  [e]
  (let [erro? #(contains? #{:runtime :sintaxe} (:erro %))]
    (boolean (or (erro? (ex-data e)) (erro? (ex-data (ex-cause e)))))))

(defn- recusa-de-tramitacao
  "A prosa de cada `:motivo` de `{:transicionou? false}` (ver `db/tramitacao/transicionar!`) + o proprio
  motivo como campo. O `:else` cobre o contrato ANTIGO — mapa sem `:motivo`, que e' o que um Repo/fake ou
  um caller nao-atualizado ainda devolve: a borda degrada para a frase generica em vez de responder um
  motivo inventado. As strings aqui sao PROSA sobre o mecanismo (fim de rito, guard, ato nao declarado);
  nenhum nome de estado ou de gatilho de camara aparece neste codigo — os que a frase cita vem do DADO,
  interpolados de `estado` e `gatilho`."
  [motivo gatilho estado]
  (case motivo
    :estado-terminal
    {:motivo "estado-terminal"
     :erro (str "o rito desta Casa declara '" estado "' como fim de processo: a materia nao sai mais "
                "deste estado, e o ato '" gatilho "' nao se aplica. Nao e' falta de permissao nem de "
                "documento — o processo legislativo acabou aqui.")}

    :guarda-recusou
    {:motivo "guarda-recusou"
     :erro (str "o rito desta Casa declara o ato '" gatilho "' a partir de '" estado "', mas a condicao "
                "dele nao esta' cumprida agora — o mesmo pedido pode passar quando ela estiver.")}

    :gatilho-nao-declarado
    {:motivo "gatilho-nao-declarado"
     :erro (str "o rito desta Casa nao declara o ato '" gatilho "' a partir de '" estado "' — repetir o "
                "pedido nao muda isso. Os atos que ele declara estao no GET desta mesma rota.")}

    :estado-fora-do-rito
    {:motivo "estado-fora-do-rito"
     :erro (str "o rito desta materia nao declara o estado atual '" estado "' — ela pode ser anterior a "
                "este rito, ou o rito ter sido trocado sob os pes dela. Nenhum ato e' possivel ate' "
                "alguem reconciliar rito e estado na configuracao.")}

    {:erro (str "o rito desta Casa nao permite o ato '" gatilho "' com a materia em '" estado "'")}))

(defn- tramitar-handler
  "POST /legislativo/proposicoes/:id/tramitacao — dispara UM GATILHO na maquina do eixo C. Irma de
  `/legislativo/pareceres/:id/emissao`: mesma fiacao (`registro` do motor injetado pelo host, `agora`
  resolvido AQUI na borda em data civil), mesmo gate grosso ('secretario').

  O CORPO ACEITA `gatilho`, JAMAIS ESTADO-DESTINO (wire :closed). Quem escolhe o destino e' o template
  avaliando o guard; quem escolhe o rito e' a coluna da linha. Ver `wire/in/proposicao.TramitarProposicao`.

  OS QUATRO DESFECHOS, e por que cada codigo:

  - transicionou -> 200 + recibo (de/para/gatilho/ocorrido-em). 200 e nao 201 espelhando a irma
    `/pareceres/:id/emissao`: a transicao grava uma linha de historico, mas nao cria recurso ENDERECAVEL
    (nao ha `GET /tramitacoes/:id` p/ apontar num Location) — 201 prometeria uma URL que nao existe.

  - a engine NAO transicionou -> 409 + `{:estado-atual :gatilho :motivo}`. E' DOMINIO NORMAL, nao erro:
    a Casa nao permite esse ato. 409 e nao 400 porque o pedido estava correto — o MESMO corpo
    funcionaria noutro estado, ou depois; quem recusa e' o recurso, nao a requisicao. E' o codigo que este
    projeto ja' usa p/ conflito de estado em 7 bordas (`:conflito/votacao-terminal`, `/sessao-fechada`,
    `/proposicao-nao-aprovada`, `/aparte`, `/inscricao`, `/fala`, `/vinculo`), e cabe aqui pelo mesmo
    motivo. O corpo carrega estado-atual + gatilho porque 'proibido' sem 'de onde' e 'o que' obriga o
    operador a adivinhar o regimento — e carrega `motivo`, porque as QUATRO causas de recusa pedem acoes
    OPOSTAS e a frase generica de antes ('a Casa nao permite este ato agora') as achatava todas:
      · `estado-terminal`       -> a Casa ENCERROU o processo (mig 0078: quem declara o fim e' o rito, nao
                                   mais uma string cravada em SQL). Nao ha' o que esperar; parar de tentar.
      · `guarda-recusou`        -> o ato existe, a condicao dele nao esta' cumprida AGORA. O unico dos
                                   quatro que o tempo (ou o `contexto` certo no corpo) pode mudar.
      · `gatilho-nao-declarado` -> o rito nao declara ESSE ato a partir desse estado. Repetir nunca
                                   funciona; o GET irmao lista os que ele declara.
      · `estado-fora-do-rito`   -> o rito nem conhece o estado atual da materia. Conserto e' de CONFIG.
    O `motivo` vai como campo PROPRIO, e nao so' embutido na prosa do `:erro`: interface que precise
    distinguir 'some o botao' de 'mostra o que falta' nao pode depender de casar substring de mensagem.

  - materia sem rito (`:conflito/sem-rito`) -> 409 tambem, mas com mensagem PROPRIA: nao e' 'agora nao',
    e' 'nunca, ate' alguem configurar'. Nao e' 400: o corpo estava certo, falta dado da Casa.

  - guard que LANCA / CAS: sao coisas DIFERENTES entre si e das de cima.
      · CAS (`:conflito/transicao`) -> 409, corpo SEM estado-atual: a escrita colidiu, ninguem sabe em que
        estado a materia parou. Colar o estado lido antes da colisao seria afirmar um fato ja' vencido.
      · guard inavaliavel -> 500 com corpo NOMEADO. Nao e' 4xx: nenhuma acao do cliente conserta um rito
        que nao avalia, e chamar isso de erro do cliente ESCONDE o incidente atras da metrica errada — o
        principio comercial da §5 do CLAUDE.md e' que regra falhando em runtime e' incidente inaceitavel,
        entao ela tem que contar como 5xx. O que NAO pode e' o 500 OPACO ('erro interno') do interceptor
        global: o corpo nomeia a causa (o rito da Casa nao pode ser avaliado) e o gatilho que ficou preso,
        para o servidor saber que ligar para quem administra os templates, nao reapertar o botao."
  [repo-leg registro relogio]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          pid (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          agora (tempo/hoje relogio zona-civil)
          m (adapters-in-proposicao/tramitar->dominio ator pid agora (:json-params req))]
      (try
        (if-let [r (controllers/tramitar-proposicao repo-leg registro ator m)]
          (if (:transicionou? r)
            (http/json-resposta 200 (adapters-out-proposicao/recibo-transicao->wire pid (:gatilho m) r))
            (http/json-resposta 409 (assoc (recusa-de-tramitacao (:motivo r) (:gatilho m) (:de r))
                                           :estado-atual (:de r) :gatilho (:gatilho m))))
          (http/json-resposta 404 {:erro "proposicao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (cond
            ;; A NEGACAO DE AUTORIZACAO VEM PRIMEIRO (3-A; achado IMPORTANTE-4 da revisao de seguranca).
            ;; `authz/check!` embrulha a excecao da politica e a passa como CAUSA, e `guard-inavaliavel?`
            ;; OLHA A CAUSA — entao, com a ordem invertida, toda negacao por expressao inavaliavel
            ;; (campo de ator escrito errado, fato ausente do registry) saia como 500 'o rito nao pode ser
            ;; avaliado' em vez de 403. A Casa recebia incidente de config onde havia recusa de acesso, e
            ;; o teste de `db/` nao pegava porque nao atravessa o diplomat.
            (authz/negado? e)
            (http/json-resposta 403 {:erro "autorizacao negada" :gatilho (:gatilho m)})

            (guard-inavaliavel? e)
            (http/json-resposta 500 {:erro (str "o rito desta Casa nao pode ser avaliado — a materia NAO "
                                                "tramitou; procure quem administra os templates de tramitacao")
                                     :gatilho (:gatilho m)})
            :else
            (case (:tipo (ex-data e))
              :conflito/sem-rito (http/json-resposta 409 {:erro (ex-message e) :gatilho (:gatilho m)})
              :conflito/transicao (http/json-resposta 409 {:erro (ex-message e) :gatilho (:gatilho m)})
              (throw e))))))))

(defn- tramitacao-leitura-handler
  "GET /legislativo/proposicoes/:id/tramitacao(?limite=) — o HISTORICO da materia + os GATILHOS que a Casa
  declara a partir do estado ATUAL. Mesmo path e mesmo gate grosso ('secretario') do POST irmao; a lista de
  gatilhos e' o que torna aquele POST usavel por uma interface, em vez de exigir que alguem adivinhe a
  string do ato.

  DUAS COISAS QUE ESTA BORDA NAO FAZ, e nenhuma das duas por preguica:

  1. NAO AVALIA GUARD p/ dizer quais gatilhos passariam. [REVERTIDO por ADR-0004] Ate' 11/09/2026 a razao
     era que o guard lia `alegado` (o `contexto` do corpo, renomeado na borda), argumento do POST que nao
     existe nesta leitura — avaliar `alegado.urgente` contra `{}` responderia 'nao passa' sobre um ato
     que passaria com o corpo certo: resposta precisa e FALSA, pior que imprecisa e honesta. Essa razao
     especifica sumiu: o guard nao le' mais o corpo do POST sob nome nenhum (nem `contexto`, nem
     `alegado`), so' verdade APURADA. O que continua valendo, sozinho, e' o que ja' vinha ao lado dela:
     guard LANCA (um rito inavaliavel derrubaria a leitura, tirando do operador tambem o historico,
     justamente quando ele mais precisa) e guard consulta FATO (N avaliacoes por abertura de tela, cada
     uma uma consulta real).
     O preco — o botao que o guard vai recusar — e' pago no NOME do campo (`gatilhos-possiveis`, nunca
     'disponiveis') e em `pode-ser-recusado` por gatilho, que e' mais informacao que um aviso generico.

  2. NAO toca `registro` nem `relogio`. Leitura que precisa do motor e' leitura que virou escrita
     disfarcada; a ausencia desses dois argumentos na fiacao e' o que torna (1) verificavel por inspecao.

  404 p/ materia inexistente no tenant (nunca vaza a diferenca entre 'nao existe' e 'e' de outra Casa').
  Materia SEM rito nao e' 404 nem 409: e' 200 com historico vazio, nenhum gatilho e a `nota` dizendo por
  que — o recurso existe, e a resposta correta sobre ele e' 'nao ha' o que tramitar, e eis o motivo'."
  [repo-leg]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          {:keys [limite]} (adapters-in-proposicao/tramitacao-query->dominio (:query-params req))]
      (if-let [m (controllers/buscar-tramitacao repo-leg ente-id id limite)]
        (http/json-resposta 200 (adapters-out-proposicao/tramitacao->wire m))
        (http/json-resposta 404 {:erro "proposicao nao encontrada"})))))

(defn- ficha-materia-handler
  "GET /legislativo/proposicoes/:id/ficha (Onda B Slice 3). Mesmo gate grosso das rotas irmas (papel
  'secretario'); nil (proposicao inexistente ou de outro tenant) -> 404, nunca vaza. O diplomat compoe os
  DOIS adapters/out (proposicao p/ o cabecalho + ficha-materia p/ o envelope) — adapters/ nunca chama outro
  adapters/ (ADR-0001 §3). `:texto` ja' chega EXTRAIDO do controller (string/nil — review MENOR
  fe-9-ficha-materia: o diplomat nunca decide nome de campo do model, so' compoe)."
  [repo-leg resolver-comissoes]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [{:keys [proposicao texto] :as ficha} (controllers/buscar-ficha-materia repo-leg resolver-comissoes ente-id id)]
        (http/json-resposta 200 (adapters-out-ficha/ficha->wire
                                   (adapters-out-proposicao/detalhe->wire proposicao texto)
                                   ficha))
        (http/json-resposta 404 {:erro "proposicao nao encontrada"})))))

(defn- editar-proposicao-handler
  "PATCH /legislativo/proposicoes/:id. PRE-CHECK via `buscar-proposicao-ficha` ANTES de editar — 404 imediato
  se a proposicao nao existir (mesmo contrato de 404 do GET de detalhe — nunca 500). Sem o pre-check,
  `editar-proposicao` alcanca `db/proposicao.clj`'s `editar!`, que lanca `ex-info` SEM `:tipo` assim que acha
  a linha ausente — o interceptor global de erro (`oplenario.interceptors/erro`) so' mapeia `:validacao/
  invalido`->400 e `authz/negado?`->403, entao essa excecao cai no fallback generico -> 500 (bug real, achado
  em review). Apos o pre-check passar, edita + rele' o detalhe p/ o corpo 200. `vereador-vinculado?`
  (injetada pelo host) valida `autor-id` no controller, mesmo contrato de `criar-proposicao-handler`
  (achados I-1/M-1 da review)."
  [repo-leg vereador-vinculado?]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-not (controllers/buscar-proposicao-ficha repo-leg ente-id id)
        (http/json-resposta 404 {:erro "proposicao nao encontrada"})
        (let [m (adapters-in-proposicao/editar-proposicao->dominio ator id (:json-params req))]
          (controllers/editar-proposicao repo-leg vereador-vinculado? ente-id m)
          (if-let [{:keys [proposicao texto]} (controllers/buscar-proposicao-ficha repo-leg ente-id id)]
            (http/json-resposta 200 (adapters-out-proposicao/detalhe->wire proposicao texto))
            (http/json-resposta 404 {:erro "proposicao nao encontrada"})))))))

(defn- parecer-editor-handler
  "GET /legislativo/pareceres/:id (Onda B Slice 5). nil (parecer inexistente ou de outro tenant) -> 404."
  [repo-leg resolver-comissoes]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [dados (controllers/buscar-parecer-editor repo-leg resolver-comissoes ente-id id)]
        (http/json-resposta 200 (adapters-out-parecer/editor->wire dados))
        (http/json-resposta 404 {:erro "parecer nao encontrado"})))))

(defn- salvar-rascunho-parecer-handler
  "PATCH /legislativo/pareceres/:id. PRE-CHECK 404 ANTES de escrever se o parecer nao existir (mesmo
  contrato de editar-proposicao-handler — evita a ex-info sem :tipo do db/ cair no fallback 500)."
  [repo-leg resolver-comissoes]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-not (controllers/buscar-parecer-editor repo-leg resolver-comissoes ente-id id)
        (http/json-resposta 404 {:erro "parecer nao encontrado"})
        (let [m (adapters-in-parecer/salvar-rascunho->dominio ator id (:json-params req))]
          (controllers/salvar-rascunho-parecer repo-leg ente-id m)
          (http/json-resposta 200 (adapters-out-parecer/editor->wire
                                     (controllers/buscar-parecer-editor repo-leg resolver-comissoes ente-id id))))))))

(defn- emitir-parecer-handler
  "POST /legislativo/pareceres/:id/emissao. Onda C4: constroi o assinador STUB inline (mesmo padrao de
  gerar-artefato-publicacao!) — a assinatura acontece dentro de Repo/emitir-parecer!, nao aqui."
  [repo-leg resolver-comissoes registro relogio]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          agora (tempo/hoje relogio zona-civil)]
      (if-let [{:keys [parecer]} (controllers/buscar-parecer-editor repo-leg resolver-comissoes ente-id id)]
        (let [m (adapters-in-parecer/emitir->dominio ator id (:template-id parecer) agora (:json-params req))]
          (controllers/emitir-parecer repo-leg registro (assinador-icp/assinador-stub) ente-id m)
          (http/json-resposta 200 (adapters-out-parecer/editor->wire
                                     (controllers/buscar-parecer-editor repo-leg resolver-comissoes ente-id id))))
        (http/json-resposta 404 {:erro "parecer nao encontrado"})))))

(defn- meu-parecer-editor-handler
  "GET /meu/pareceres/:id (Onda C4, feature 7.3). Gate grosso 'vereador' na rota; gate de posse
  (relator-do-parecer?) no controller — 404 sem distinguir 'nao existe' de 'nao e' seu' (mesmo contrato de
  acusar-ciencia-handler)."
  [repo-leg resolver-vereador resolver-comissoes]
  (fn [req]
    (let [ator (:ator req)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [dados (controllers/meu-parecer-editor repo-leg resolver-vereador resolver-comissoes ator id)]
        (http/json-resposta 200 (adapters-out-parecer/editor->wire dados))
        (http/json-resposta 404 {:erro "parecer nao encontrado"})))))

(defn- meu-emitir-parecer-handler
  "POST /meu/pareceres/:id/emissao (Onda C4) — 'assinar em 2 toques'. Mesmo gate de posse de
  meu-parecer-editor-handler ANTES de tentar emitir; o TEMPLATE-ID vem do parecer JA' CARREGADO por
  meu-parecer-editor (mesmo pre-check tambem serve de gate 404 — mesmo padrao de emitir-parecer-handler)."
  [repo-leg registro relogio resolver-vereador resolver-comissoes]
  (fn [req]
    (let [ator (:ator req)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          agora (tempo/hoje relogio zona-civil)]
      (if-let [{:keys [parecer]} (controllers/meu-parecer-editor repo-leg resolver-vereador resolver-comissoes ator id)]
        (let [m (adapters-in-parecer/emitir->dominio ator id (:template-id parecer) agora (:json-params req))]
          (if (controllers/meu-emitir-parecer repo-leg registro (assinador-icp/assinador-stub)
                                              resolver-vereador ator id m)
            (http/json-resposta 200 (adapters-out-parecer/editor->wire
                                       (controllers/meu-parecer-editor repo-leg resolver-vereador resolver-comissoes ator id)))
            (http/json-resposta 404 {:erro "parecer nao encontrado"})))
        (http/json-resposta 404 {:erro "parecer nao encontrado"})))))

;; ========================= Onda B Slice 6: expediente (documentos + protocolo geral) =========================

(defn- listar-modelos-documento-handler
  "GET /legislativo/documento-modelos (Onda B Slice 6). Seletor da aba 'Gerar documento' — so' os ATIVOS."
  [repo-leg]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))]
      (http/json-resposta 200 (adapters-out-documento-modelo/modelos->wire
                                 (controllers/listar-modelos-documento repo-leg ente-id))))))

(defn- gerar-documento-handler
  "POST /legislativo/documentos. `m` ja' carrega o `:id` novo (gerado pelo adapters/in) — o controller resolve
  o modelo (404 se inexistente no tenant) e gera; o handler RE-LE pelo mesmo id p/ o corpo 201 completo (mesmo
  padrao de criar-proposicao-handler)."
  [repo-leg]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          m (adapters-in-documento/gerar-documento->dominio ator (:json-params req))]
      (if (controllers/gerar-documento repo-leg ente-id m)
        (let [{:keys [documento protocolo]} (controllers/buscar-documento-editor repo-leg ente-id (:id m))]
          (http/json-resposta 201 (adapters-out-documento/documento->wire documento protocolo)))
        (http/json-resposta 404 {:erro "modelo de documento nao encontrado"})))))

(defn- detalhe-documento-handler
  "GET /legislativo/documentos/:id (Onda B Slice 6). nil (documento inexistente ou de outro tenant) -> 404."
  [repo-leg]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [{:keys [documento protocolo]} (controllers/buscar-documento-editor repo-leg ente-id id)]
        (http/json-resposta 200 (adapters-out-documento/documento->wire documento protocolo))
        (http/json-resposta 404 {:erro "documento nao encontrado"})))))

(defn- editar-documento-handler
  "PATCH /legislativo/documentos/:id. PRE-CHECK 404 ANTES de escrever se o documento nao existir (mesmo
  contrato de salvar-rascunho-parecer-handler — evita a ex-info sem :tipo do db/ cair no fallback 500)."
  [repo-leg]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-not (controllers/buscar-documento-editor repo-leg ente-id id)
        (http/json-resposta 404 {:erro "documento nao encontrado"})
        (let [m (adapters-in-documento/editar-documento->dominio ator id (:json-params req))]
          (controllers/editar-documento repo-leg ente-id m)
          (let [{:keys [documento protocolo]} (controllers/buscar-documento-editor repo-leg ente-id id)]
            (http/json-resposta 200 (adapters-out-documento/documento->wire documento protocolo))))))))

(defn- protocolar-documento-handler
  "POST /legislativo/documentos/:id/protocolo — o CTA 'Protocolar e numerar' do mockup. PRE-CHECK 404 (mesmo
  contrato das rotas irmas). `ano` (kernel/tempo, injetavel em teste) resolve a data civil AQUI, na borda —
  mesmo padrao de emitir-parecer-handler/`agora`. Documento ja' 'emitido' (re-protocolar) ou lock-version
  desatualizado -> `:validacao/invalido` no db/documento.clj (Repo/protocolar-documento!) -> 400, nunca 500."
  [repo-leg relogio]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          ano (.getYear (tempo/hoje relogio zona-civil))]
      (if-not (controllers/buscar-documento-editor repo-leg ente-id id)
        (http/json-resposta 404 {:erro "documento nao encontrado"})
        (let [m (adapters-in-documento/protocolar-documento->dominio ator id (:json-params req))]
          (controllers/protocolar-documento repo-leg ente-id ano m)
          (let [{:keys [documento protocolo]} (controllers/buscar-documento-editor repo-leg ente-id id)]
            (http/json-resposta 200 (adapters-out-documento/documento->wire documento protocolo))))))))

(defn- protocolo-geral-handler
  "GET /legislativo/protocolo-geral (Onda B Slice 6, feature 3.23) — o 'Livro do Protocolo Geral' do ano
  corrente (kernel/tempo, mesmo padrao de protocolar-documento-handler)."
  [repo-leg relogio]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          ano (.getYear (tempo/hoje relogio zona-civil))]
      (http/json-resposta 200 (adapters-out-protocolo/livro->wire
                                 (controllers/listar-protocolo-do-ano repo-leg ente-id ano))))))

;; ========================= Onda B Slice 7: pos-aprovacao (autografo + sancao/veto, F3.8a) =========================

(defn- pos-aprovacao->wire
  "{:autografo :tramitacao-executiva} (dominio, kebab, nil-aveis) -> PosAprovacaoOut. Chama os DOIS
  adapters/out irmaos (autografo/tramitacao-executiva) antes de compor — adapters/ nunca chama outro
  adapters/ (ADR-0001 §3), mesma disciplina de ficha-materia-handler compondo detalhe->wire+ficha->wire."
  [{:keys [autografo tramitacao-executiva]}]
  (adapters-out-pos-aprovacao/pos-aprovacao->wire
    (some-> autografo adapters-out-autografo/autografo->wire)
    (some-> tramitacao-executiva adapters-out-tramitacao-executiva/tramitacao-executiva->wire)))

(defn- gerar-autografo-handler
  "POST /legislativo/proposicoes/:id/autografo — 'Gerar autografo e enviar ao Executivo'. `ano` (kernel/
  tempo, injetavel em teste) resolve o ano civil da geracao AQUI, na borda (mesmo padrao de
  protocolar-documento-handler/`ano`). nil (proposicao inexistente no tenant) -> 404. Autografo duplicado
  (guard do controller, mesmo racional de encerrar-votacao) -> :validacao/invalido -> 400 (interceptor
  global de erro). Materia NAO APROVADA em votacao (T3-A) -> :conflito/proposicao-nao-aprovada -> 409,
  traduzido AQUI (o interceptor global so' conhece :validacao/invalido; um :conflito/* solto vira 500)."
  [repo-leg resolver-municipio relogio]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          proposicao-id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          ano (.getYear (tempo/hoje relogio zona-civil))
          m (adapters-in-pos-aprovacao/gerar-autografo->dominio ator proposicao-id (:json-params req))]
      (try
        (if (controllers/gerar-autografo repo-leg resolver-municipio ente-id ano m)
          (http/json-resposta 201 (pos-aprovacao->wire
                                    (controllers/buscar-pos-aprovacao repo-leg ente-id proposicao-id)))
          (http/json-resposta 404 {:erro "proposicao nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          ;; T3-A: materia nao aprovada -> 409 (o pedido era valido; o recurso e' que nao chegou la'). Sem
          ;; esta traducao o `:conflito/*` cai no `:else` do interceptor global e a recusa vira 500 — mesma
          ;; disciplina de `resposta-conflito-tramitacao-executiva`: cada diplomat traduz a SUA borda.
          (case (:tipo (ex-data e))
            ;; T3-A: materia nao aprovada. T3-A2: aprovada, mas a votacao nao registrou o texto deliberado.
            ;; Os dois sao 409 (o pedido era valido; o recurso e' que nao esta em condicao de atende-lo) e
            ;; carregam a mensagem de DOMINIO — sem esta traducao o `:conflito/*` cai no `:else` do
            ;; interceptor global e a recusa vira 500. Mesma disciplina de resposta-conflito-tramitacao-executiva.
            (:conflito/proposicao-nao-aprovada
             :conflito/aprovacao-sem-texto) (http/json-resposta 409 {:erro (ex-message e)})
            (throw e)))))))

(defn- pos-aprovacao-handler
  "GET /legislativo/proposicoes/:id/pos-aprovacao. nil (proposicao inexistente no tenant) -> 404."
  [repo-leg]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          proposicao-id (adapters-in/id-param->uuid (get-in req [:path-params :id]))]
      (if-let [dados (controllers/buscar-pos-aprovacao repo-leg ente-id proposicao-id)]
        (http/json-resposta 200 (pos-aprovacao->wire dados))
        (http/json-resposta 404 {:erro "proposicao nao encontrada"})))))

(defn- resposta-conflito-tramitacao-executiva
  "Traduz `:conflito/tramitacao-executiva` -> 409 e `:validacao/votacao-inexistente` -> 400 (T2 grupo B,
  ledger de prontidao Fase 10). Sem esta traducao as duas caiam no `:else` do interceptor global e a
  borda devolvia **500 'erro interno'** para (a) conflito de estado, (b) CAS divergente — o caso mais
  banal de escrita concorrente — e (c) um `veto-votacao-id` que nao existe, que e' erro de CORPO.
  Mesma disciplina de `resposta-conflito-sessao-fechada` acima: cada diplomat traduz a SUA borda,
  mensagem do dominio via `ex-message`, nada de mapear `:conflito/*` no interceptor global."
  [e]
  (case (:tipo (ex-data e))
    :conflito/tramitacao-executiva (http/json-resposta 409 {:erro (ex-message e)})
    :validacao/votacao-inexistente (http/json-resposta 400 {:erro (ex-message e)})
    (throw e)))

(defn- registrar-resposta-executivo-handler
  "POST /legislativo/autografos/:id/resposta — 'Registrar retorno' (path :id = autografo-id). nil (sem
  tramitacao executiva para este autografo no tenant) -> 404; conflito de estado ou de lock-version -> 409.

  A rota IRMA da apreciacao, e com o MESMO guard: `db/tramitacao-executiva` lanca os dois conflitos com a
  mesma tag. Traduzida junto de proposito — a sonda T2 grupo B so' exercia a apreciacao, mas deixar o
  irmao adjacente com o mesmo defeito enquanto se edita a mesma dupla de funcoes seria pior engenharia
  que corrigir os dois (ledger Fase 10)."
  [repo-leg]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          autografo-id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          m (adapters-in-pos-aprovacao/registrar-resposta->dominio ator (:json-params req))]
      (try
        (if (controllers/registrar-resposta-executivo repo-leg ente-id autografo-id m)
          (http/json-resposta 200 (adapters-out-tramitacao-executiva/tramitacao-executiva->wire
                                     (controllers/buscar-tramitacao-por-autografo repo-leg ente-id autografo-id)))
          (http/json-resposta 404 {:erro "tramitacao executiva nao encontrada para este autografo"}))
        (catch clojure.lang.ExceptionInfo e
          (resposta-conflito-tramitacao-executiva e))))))

(defn- apreciar-veto-handler
  "POST /legislativo/tramitacoes-executivas/:id/apreciacao (path :id = tramitacao-executiva-id, DIRETO).
  A votacao real e' aberta/encerrada via /sessoes/:id/votacoes* ja' existente (§5 doc-mestre) — esta rota
  so' carimba o desfecho. nil (tramitacao executiva inexistente no tenant) -> 404; conflito de estado ou
  de lock-version -> 409; `veto-votacao-id` inexistente -> 400 (ledger Fase 10)."
  [repo-leg]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (adapters-in/id-param->uuid (get-in req [:path-params :id]))
          m (adapters-in-pos-aprovacao/apreciar-veto->dominio ator (:json-params req))]
      (try
        (if (controllers/apreciar-veto repo-leg ente-id id m)
          (http/json-resposta 200 (adapters-out-tramitacao-executiva/tramitacao-executiva->wire
                                     (controllers/buscar-tramitacao-executiva repo-leg ente-id id)))
          (http/json-resposta 404 {:erro "tramitacao executiva nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (resposta-conflito-tramitacao-executiva e))))))

;; ========================= Onda C1: borda /meu do vereador (home fora-de-sessao) =========================

(defn- meu-painel-handler
  "GET /meu/painel. Gate grosso 'vereador' na rota; `resolver-vereador` (injetado pelo host) resolve o
  vereador do proprio ator — anti-forja por construcao (nada no request escolhe 'de quem' e' o painel).
  Ator sem cadastro vinculado -> painel vazio (200), nunca 404/500 (mesmo contrato do controller)."
  [repo-leg resolver-vereador]
  (fn [req]
    (http/json-resposta 200 (adapters-out-meu-painel/meu-painel->wire
                               (controllers/meu-painel repo-leg resolver-vereador (:ator req))))))

(defn- acusar-ciencia-handler
  "POST /meu/ciencias. `vereador-id` NUNCA vem do corpo (adapters/in nem o le); o controller injeta o
  resolvido do ator. nil (ator sem cadastro vinculado -> `resolver-vereador` nil) -> 404, mesmo contrato de
  um recurso ausente do proprio ator (nunca 500)."
  [repo-leg resolver-vereador]
  (fn [req]
    (let [m (adapters-in-ciencia/acusar-ciencia->dominio (:json-params req))]
      (if-let [recibo (controllers/acusar-ciencia repo-leg resolver-vereador (:ator req) m)]
        (http/json-resposta 201 (adapters-out-meu-painel/acusar-ciencia->wire recibo))
        (http/json-resposta 404 {:erro "vereador sem cadastro vinculado neste ente"})))))

(defn rotas
  "Fragmento de rotas da votacao ao vivo + proposicoes + editor de parecer + borda /meu do vereador (table
  syntax Pedestal). Recebe o interceptor `auth` (compartilhado), o `repo-legislativo` (Repo-Component do
  proprio modulo), `consultar-sessao` (injetada pelo host — cross-modulo p/ a authz herdada da sessao),
  `resolver-municipio` (injetada pelo host — cross-modulo p/ o legislativo computar a URN em protocolar!,
  Onda B Slice 2, §22.10), `resolver-vereador` (injetada pelo host — cross-modulo p/ cadastros, Onda C1,
  §22.5.3 exceção nomeada — resolve identidade->vereador-id NESTA Casa p/ a borda /meu),
  `resolver-comissoes` (injetada pelo host — MESMA exceção nomeada, cross-modulo p/ cadastros: resolve
  `comissao-id -> nome` EM LOTE, porque `legislativo.pareceres.comissao_id` e' guard ref sem FK
  cross-schema e por isso a tela do parecer mostrava o UUID — defeito #11 do ledger de prontidao), `vereador-vinculado?`
  (injetada pelo host — cross-modulo p/ cadastros, mesma inversao de dependencia; fix da review Onda E
  fatia 2 achados I-1/M-1 — confirma que um `autor-id` cru do corpo e' um cadastro de vereador NESTE ente
  antes de virar autoria PUBLICA), `registro` (RegistroFatos do motor, injetado pelo host — Onda B Slice 5,
  o editor de parecer dirige o motor via emitir-parecer!) e `relogio` (kernel/tempo, injetado pelo host —
  review MEDIUM fe-11-parecer, mesmo contrato de `participacao-http/rotas`: producao le o relogio do
  sistema, teste crava o instante). `sessao-fechada?` (injetada pelo host — cross-modulo p/ `sessoes.logic/
  estados-sessao-fechada`, T2 grupo A achado #4/#5 do ledger de prontidao Fase 8: legislativo NAO importa o
  vocabulario de estado fechado, §22.10 — o predicado atravessa a fronteira do jeito que `consultar-sessao`
  ja' atravessa).
  Todas as acoes das verticais de votacao/proposicoes/parecer EXIGEM a authz GROSSA (papel 'secretario') +
  corpo-json nas de escrita; a fina da votacao decide no controller com a sessao carregada. A borda /meu
  EXIGE papel 'vereador' (papel DISTINTO — nao 'secretario')."
  [{:keys [auth repo-legislativo consultar-sessao sessao-fechada? resolver-municipio resolver-vereador
           resolver-comissoes vereador-vinculado? registro relogio]}]
  (let [papel (it/exige-papel "secretario")
        papel-vereador (it/exige-papel "vereador")]
    #{["/sessoes/:id/votacoes" :post
       [auth papel it/corpo-json (abrir-handler repo-legislativo consultar-sessao sessao-fechada?)]
       :route-name :legislativo/abrir-votacao]
      ["/sessoes/:id/votacoes/:votacao-id/votos" :post
       [auth papel it/corpo-json (voto-handler repo-legislativo consultar-sessao sessao-fechada?)]
       :route-name :legislativo/registrar-voto]
      ["/sessoes/:id/votacoes/:votacao-id/meu-voto" :post
       [auth papel-vereador it/corpo-json (meu-voto-handler repo-legislativo consultar-sessao sessao-fechada?
                                                             resolver-vereador registro relogio)]
       :route-name :legislativo/meu-voto]
      ["/sessoes/:id/votacoes/:votacao-id/encerramento" :post
       [auth papel it/corpo-json (encerrar-handler repo-legislativo consultar-sessao sessao-fechada?)]
       :route-name :legislativo/encerrar-votacao]
      ["/legislativo/proposicoes" :get [auth papel (listar-proposicoes-handler repo-legislativo)]
       :route-name :legislativo/listar-proposicoes]
      ["/legislativo/proposicoes" :post
       [auth papel it/corpo-json (criar-proposicao-handler repo-legislativo resolver-municipio vereador-vinculado?)]
       :route-name :legislativo/criar-proposicao]
      ["/legislativo/proposicoes/:id" :get [auth papel (detalhe-proposicao-handler repo-legislativo)]
       :route-name :legislativo/detalhe-proposicao]
      ["/legislativo/proposicoes/:id/ficha" :get [auth papel (ficha-materia-handler repo-legislativo resolver-comissoes)]
       :route-name :legislativo/ficha-materia]
      ["/legislativo/proposicoes/:id" :patch
       [auth papel it/corpo-json (editar-proposicao-handler repo-legislativo vereador-vinculado?)]
       :route-name :legislativo/editar-proposicao]
      ["/legislativo/proposicoes/:id/tramitacao" :get
       [auth papel (tramitacao-leitura-handler repo-legislativo)]
       :route-name :legislativo/tramitacao-proposicao]
      ["/legislativo/proposicoes/:id/tramitacao" :post
       [auth papel it/corpo-json (tramitar-handler repo-legislativo registro relogio)]
       :route-name :legislativo/tramitar-proposicao]
      ["/legislativo/pareceres/:id" :get [auth papel (parecer-editor-handler repo-legislativo resolver-comissoes)]
       :route-name :legislativo/parecer-editor]
      ["/legislativo/pareceres/:id" :patch
       [auth papel it/corpo-json (salvar-rascunho-parecer-handler repo-legislativo resolver-comissoes)]
       :route-name :legislativo/salvar-rascunho-parecer]
      ["/legislativo/pareceres/:id/emissao" :post
       [auth papel it/corpo-json (emitir-parecer-handler repo-legislativo resolver-comissoes registro relogio)]
       :route-name :legislativo/emitir-parecer]
      ["/legislativo/documento-modelos" :get [auth papel (listar-modelos-documento-handler repo-legislativo)]
       :route-name :legislativo/listar-modelos-documento]
      ["/legislativo/documentos" :post
       [auth papel it/corpo-json (gerar-documento-handler repo-legislativo)]
       :route-name :legislativo/gerar-documento]
      ["/legislativo/documentos/:id" :get [auth papel (detalhe-documento-handler repo-legislativo)]
       :route-name :legislativo/detalhe-documento]
      ["/legislativo/documentos/:id" :patch
       [auth papel it/corpo-json (editar-documento-handler repo-legislativo)]
       :route-name :legislativo/editar-documento]
      ["/legislativo/documentos/:id/protocolo" :post
       [auth papel it/corpo-json (protocolar-documento-handler repo-legislativo relogio)]
       :route-name :legislativo/protocolar-documento]
      ["/legislativo/protocolo-geral" :get [auth papel (protocolo-geral-handler repo-legislativo relogio)]
       :route-name :legislativo/protocolo-geral]
      ["/legislativo/proposicoes/:id/autografo" :post
       [auth papel it/corpo-json (gerar-autografo-handler repo-legislativo resolver-municipio relogio)]
       :route-name :legislativo/gerar-autografo]
      ["/legislativo/proposicoes/:id/pos-aprovacao" :get [auth papel (pos-aprovacao-handler repo-legislativo)]
       :route-name :legislativo/pos-aprovacao]
      ["/legislativo/autografos/:id/resposta" :post
       [auth papel it/corpo-json (registrar-resposta-executivo-handler repo-legislativo)]
       :route-name :legislativo/registrar-resposta-executivo]
      ["/legislativo/tramitacoes-executivas/:id/apreciacao" :post
       [auth papel it/corpo-json (apreciar-veto-handler repo-legislativo)]
       :route-name :legislativo/apreciar-veto]
      ["/meu/painel" :get [auth papel-vereador (meu-painel-handler repo-legislativo resolver-vereador)]
       :route-name :legislativo/meu-painel]
      ["/meu/ciencias" :post
       [auth papel-vereador it/corpo-json (acusar-ciencia-handler repo-legislativo resolver-vereador)]
       :route-name :legislativo/acusar-ciencia]
      ["/meu/pareceres/:id" :get [auth papel-vereador (meu-parecer-editor-handler repo-legislativo resolver-vereador resolver-comissoes)]
       :route-name :legislativo/meu-parecer-editor]
      ["/meu/pareceres/:id/emissao" :post
       [auth papel-vereador it/corpo-json (meu-emitir-parecer-handler repo-legislativo registro relogio resolver-vereador resolver-comissoes)]
       :route-name :legislativo/meu-emitir-parecer]}))

;; ========================= FE Onda A1: fila de relatores pendentes (§16.11) =========================

(defn relatores-pendentes-wire
  "Ponto de entrada IN-PROCESS da fila de relatores pendentes (FE Onda A1) — gemeo nao-HTTP p/ o host compor
  o dashboard da Mesa (mirror `painel-wire` de compliance). Passa pelo controller (nunca pelo Repo-Component
  direto — ADR-0001) + o MESMO gate adapters/out (projeta+valida) que uma rota HTTP usaria.

  CONVENCAO DE AUTHZ (mesmo contrato de `compliance.diplomat.http.in/painel-wire`, `sessoes.diplomat.http.in/
  presenca-resumo-wire` e `participacao.diplomat.http.in/esic-cumprimento-wire`): esta fn NAO re-verifica
  papel/permissao; o ENDPOINT COMPONHEDOR e' o unico ponto de enforcement (GET /paineis/mesa, wired numa task
  posterior, ja' exige papel 'secretario'). QUALQUER novo caller DEVE aplicar o gate 'secretario' antes —
  senao expoe a fila de relatores pendentes tenant-wide a um papel qualquer. Nao ha lint que force isso: e'
  convencao, mantida por revisao."
  [repo-legislativo ente-id]
  (adapters-out-relator/relatores-pendentes->wire (controllers/relatores-pendentes repo-legislativo ente-id)))
