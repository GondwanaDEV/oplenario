(ns oplenario.transparencia.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do modulo transparencia (§22.10 diplomat/http/in, ADR-0001) — as rotas-dado
  Pedestal + os handlers.

  DOIS perfis: (Slice 1) o PORTAL PUBLICO (SEM `auth`) — o :ente do path resolve via `resolver-ente-publico`
  (seam do host) e o Repo abre com-tenant* com ele (RLS isola mesmo sem ator); a saida FILTRA via adapters/out.
  (Slice 2) o ACOMPANHAMENTO do cidadao (COM `auth`) — o ente-id + seguidor vem do ATOR (token), NUNCA do
  path/corpo (anti-forge); mesmo perfil so-auth-sem-papel de participacao/comentar.

  Disambiguador `casa/` (mesma razao ja documentada em participacao): o router prefix-tree do Pedestal 0.7
  nao admite um wildcard (`:ente`) e um literal no MESMO nivel de path — `casa/` mantem depth-2 sempre
  literal, o :ente cai em subarvore propria. As rotas do Slice 2 sao sob /portal (autenticadas, sem :ente no
  path) — sem colisao com as publicas /portal/casa/... ."
  (:require [oplenario.http :as http]
            [oplenario.transparencia.adapters.in.portal :as adapters-in]
            [oplenario.transparencia.adapters.out.acompanhamento :as adapters-out-acomp]
            [oplenario.transparencia.adapters.out.artefato :as adapters-out-artefato]
            [oplenario.transparencia.adapters.out.dados-abertos :as adapters-out-dados-abertos]
            [oplenario.transparencia.adapters.out.ente :as adapters-out-ente]
            [oplenario.transparencia.adapters.out.materia :as adapters-out-materia]
            [oplenario.transparencia.adapters.out.movimentacao :as adapters-out-movimentacao]
            [oplenario.transparencia.adapters.out.norma :as adapters-out-norma]
            [oplenario.transparencia.adapters.out.parlamentar :as adapters-out-parlamentar]
            [oplenario.transparencia.adapters.out.vereadores :as adapters-out-vereadores]
            [oplenario.transparencia.adapters.out.votacao :as adapters-out-votacao]
            [oplenario.transparencia.controllers :as controllers]))

(set! *warn-on-reflection* true)

(def resolver-ente-publico-uuid
  "Seam `resolver-ente-publico` DEFAULT do host (V1): o :ente do path = UUID do ente, coagido fail-closed
  (:validacao/invalido -> 400). Mesmo seam de participacao/diplomat/http/in — fornecido pelo host a `rotas`."
  adapters-in/ente-param->uuid)

(defn- info-ente-handler
  "GET /portal/casa/:ente — perfil publico MINIMO do ente (nome-oficial/nome-curto), p/ a barra
  institucional/rodape do portal exibirem o nome real da Casa em vez do UUID cru da rota (FE Onda A2
  fast-follow). `info-ente` chega INJETADA pelo host (cross-modulo por inversao de dependencia sobre o Repo
  de cadastros — §22.10, mesmo padrao de consultar-sessao/membros-da-casa/painel-compliance; transparencia
  nunca importa cadastros). Ente inexistente -> 404 (nunca devolve o UUID como se fosse nome)."
  [info-ente resolver-ente-publico acesso-restrito-desde]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))]
      (if-let [e (info-ente ente-id)]
        ;; ADR-0018: a faixa "Sistema da Camara com acesso restrito desde DD/MM" do portal (o portal segue no ar)
        (http/json-resposta 200 (adapters-out-ente/->wire
                                 (assoc e :acesso-restrito-desde (when acesso-restrito-desde
                                                                   (acesso-restrito-desde ente-id)))))
        (http/json-resposta 404 {:erro "ente nao encontrado"})))))

(defn- listar-materias-handler
  "GET /portal/casa/:ente/materias — resposta agora e' o PAR {:materias :materias-total} (frente
  'truncamento-familia', sitio (b)): esta rota E' a listagem publica de proposicoes (sem paginacao,
  sem outra rota — ver materia-vista.ts/escolherDestaque no FE), e o teto de 200 saia sem sinalizar.

  Ente inexistente -> 404, igual a /portal/casa/:ente (achado do teste exploratorio contra a homologacao,
  metodo docs/20): esta rota devolvia 200 com colecao VAZIA para QUALQUER id, enquanto a rota-pai devolvia
  404 para o mesmo id. O cliente nao conseguia distinguir Casa-que-existe-porem-vazia de
  Casa-que-nao-existe — e foi esse 200 que sustentou a capa do portal renderizando uma Casa inexistente. Casa REAL
  vazia SEGUE 200 com lista vazia: a distincao vem de `info-ente` (o mesmo seam injetado do host que a
  rota-pai usa, §22.10 — transparencia nunca importa cadastros), nunca do tamanho da lista."
  [repo-transparencia resolver-ente-publico info-ente]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))]
      (if-not (info-ente ente-id)
        (http/json-resposta 404 {:erro "ente nao encontrado"})
        (http/json-resposta 200
          (adapters-out-materia/materias->wire (controllers/listar-materias repo-transparencia ente-id)))))))

(defn- ficha-materia-handler
  [repo-transparencia resolver-ente-publico]
  (fn [req]
    (let [ente-id       (resolver-ente-publico (get-in req [:path-params :ente]))
          proposicao-id (adapters-in/proposicao-param->uuid (get-in req [:path-params :proposicao_id]))]
      (if-let [detalhe (controllers/ficha-materia repo-transparencia ente-id proposicao-id)]
        (http/json-resposta 200 (adapters-out-materia/ficha->wire detalhe (:norma detalhe)))
        (http/json-resposta 404 {:erro "materia nao encontrada"})))))

(defn- movimentacoes-da-materia-handler
  "GET /portal/casa/:ente/materias/:proposicao_id/movimentacoes — \"Por onde a materia passou\" (PUBLICO): cada
  movimentacao com a data e o nome da etapa no rito da Casa, da mais recente para a mais antiga, com o total e o
  sinal de historico completo. So' de materia que o portal mostra (404 senao, a mesma voz da ficha)."
  [repo-transparencia resolver-ente-publico]
  (fn [req]
    (let [ente-id       (resolver-ente-publico (get-in req [:path-params :ente]))
          proposicao-id (adapters-in/proposicao-param->uuid (get-in req [:path-params :proposicao_id]))]
      (if-let [r (controllers/movimentacoes-da-materia repo-transparencia ente-id proposicao-id)]
        (http/json-resposta 200 (adapters-out-movimentacao/movimentacoes->wire r))
        (http/json-resposta 404 {:erro "materia nao encontrada"})))))

(defn- pareceres-juridicos-handler
  "GET /portal/casa/:ente/materias/:proposicao_id/pareceres-juridicos — os pareceres juridicos da materia (ADR-0019 Eixo 4).
  So' de materia que o portal publica (404 senao) e, pelo proprio seam, so' depois da deliberacao; antes, lista vazia.
  `pareceres-juridicos-publicos` e' o seam do host sobre o legislativo (§22.10): ja' devolve o wire."
  [repo-transparencia resolver-ente-publico pareceres-juridicos-publicos]
  (fn [req]
    (let [ente-id       (resolver-ente-publico (get-in req [:path-params :ente]))
          proposicao-id (adapters-in/proposicao-param->uuid (get-in req [:path-params :proposicao_id]))]
      (if (controllers/ficha-materia repo-transparencia ente-id proposicao-id)
        (http/json-resposta 200 (pareceres-juridicos-publicos ente-id proposicao-id))
        (http/json-resposta 404 {:erro "materia nao encontrada"})))))

(defn- listar-normas-handler
  "GET /portal/casa/:ente/legislacao(?tipo=&ano=&numero=) — acervo as-enacted (F6c Slice 3). Query-params
  OPCIONAIS coagidos na borda (ano/numero nao-inteiro -> 400); ausentes -> filtro vazio = compat Slice 1.
  Resposta e' o PAR {:normas :normas-total} (frente 'truncamento-familia', sitio (c)): o teto de 200 saia
  sem sinalizar.

  Ente inexistente -> 404, igual a /portal/casa/:ente (achado do teste exploratorio contra a homologacao,
  metodo docs/20): esta rota devolvia 200 com colecao VAZIA para QUALQUER id, enquanto a rota-pai devolvia
  404 para o mesmo id. O cliente nao conseguia distinguir Casa-que-existe-porem-vazia de
  Casa-que-nao-existe — e foi esse 200 que sustentou a capa do portal renderizando uma Casa inexistente. Casa REAL
  vazia SEGUE 200 com lista vazia: a distincao vem de `info-ente` (o mesmo seam injetado do host que a
  rota-pai usa, §22.10 — transparencia nunca importa cadastros), nunca do tamanho da lista."
  [repo-transparencia resolver-ente-publico info-ente]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))
          filtro  (adapters-in/filtro-legislacao (:query-params req))]
      (if-not (info-ente ente-id)
        (http/json-resposta 404 {:erro "ente nao encontrado"})
        (http/json-resposta 200
          (adapters-out-norma/normas->wire (controllers/listar-normas repo-transparencia ente-id filtro)))))))

(defn- buscar-norma-handler
  [repo-transparencia resolver-ente-publico]
  (fn [req]
    (let [ente-id  (resolver-ente-publico (get-in req [:path-params :ente]))
          norma-id (adapters-in/norma-param->uuid (get-in req [:path-params :norma_id]))]
      (if-let [n (controllers/buscar-norma repo-transparencia ente-id norma-id)]
        (http/json-resposta 200 (adapters-out-norma/->wire n))
        (http/json-resposta 404 {:erro "norma nao encontrada"})))))

(defn- baixar-artefato-handler
  "GET /portal/casa/:ente/legislacao/:norma_id/artefato — download BINARIO do artefato de publicacao mais
  recente (Slice 4b, PUBLICO). :nao-encontrado -> 404; :blob-ausente (ancora-antes-do-blob) -> 500 ALERTA
  (NUNCA 404 silencioso sobre um documento oficial); :ok -> resposta binaria."
  [repo-transparencia resolver-ente-publico objeto-store]
  (fn [req]
    (let [ente-id  (resolver-ente-publico (get-in req [:path-params :ente]))
          norma-id (adapters-in/norma-param->uuid (get-in req [:path-params :norma_id]))
          r        (controllers/baixar-artefato-da-norma repo-transparencia objeto-store ente-id norma-id)]
      (case (:resultado r)
        :ok            (adapters-out-artefato/->download r norma-id)
        :blob-ausente  (http/json-resposta 500 {:erro "artefato temporariamente indisponivel"})
        :nao-encontrado (http/json-resposta 404 {:erro "artefato nao encontrado"})))))

(defn- perfil-vereador-handler
  "GET /portal/casa/:ente/vereadores/:vereador_id — perfil PUBLICO do vereador (Onda E fatia 2, SEM auth).

  DUAS fontes fundidas na borda: a IDENTIDADE **e a JANELA DE EXERCICIO DO MANDATO** vem de
  `ficha-e-janelas-publicas`, INJETADA pelo host sobre o Repo de `cadastros` (inversao de dependencia
  §22.10, mesmo padrao de `info-ente` — transparencia nunca importa cadastros); os NUMEROS vem do
  read-model proprio, agora RECORTADOS por essa janela (I-5 fatia 6).

  A JANELA NAO DECIDE NADA ALEM DO RECORTE. Janela vazia e' um estado legitimo (suplente que ainda nao
  tomou posse e' um parlamentar real e tem perfil): o read-model devolve 0/0 com
  `:janela-de-exercicio-conhecida false` e a resposta e' 200. Quem decide 404 e' so' a ficha.

  FAIL-CLOSED no 404: vereador inexistente — ou cadastrado em OUTRA Casa, ja' que o seam e' consultado com o
  `ente-id` resolvido do PATH — devolve 404 ANTES de qualquer leitura de perfil. Nunca 200 com perfil vazio:
  isso insinuaria um parlamentar real sem nenhuma atuacao, o que e' difamatorio. A ordem importa duplamente:
  o guard tambem impede que numeros DESTA Casa saiam sob a identidade de outra. E o read-model so' e' lido
  no caminho 200 — `perfil-de-vereador-inexistente-continua-404-sem-ler-o-perfil` injeta um Repo que explode
  em `perfil-parlamentar` justamente para que a ordem nao possa inverter em silencio."
  [repo-transparencia resolver-ente-publico ficha-e-janelas-publicas votacoes-com-voto-publico]
  (fn [req]
    (let [ente-id     (resolver-ente-publico (get-in req [:path-params :ente]))
          vereador-id (adapters-in/vereador-param->uuid (get-in req [:path-params :vereador_id]))]
      (if-let [{:keys [ficha janelas]} (ficha-e-janelas-publicas ente-id vereador-id)]
        (http/json-resposta 200
          (adapters-out-parlamentar/->wire
           ficha janelas
           (controllers/perfil-parlamentar repo-transparencia ente-id vereador-id janelas
                                          votacoes-com-voto-publico)))
        (http/json-resposta 404 {:erro "vereador nao encontrado"})))))

(defn- listar-vereadores-handler
  "GET /portal/casa/:ente/vereadores — a LISTA publica dos vereadores EM EXERCICIO da Casa (SEM auth): o indice
  que o perfil `.../vereadores/:vereador_id` nao tinha (so' abria por UUID). Quem esta' em exercicio vem de
  `vereadores-em-exercicio`, INJETADO pelo host sobre o Repo de `cadastros` (inversao de dependencia §22.10,
  mesmo padrao de `info-ente`/`ficha-e-janelas-publicas` — transparencia nunca importa cadastros); a saida
  passa pelo gate `adapters/out/vereadores` (contrato `:closed`, so' o publico).

  Casa inexistente -> 404, como a rota-pai e as irmas (nunca 200 com lista vazia de uma Casa que nao existe);
  Casa REAL sem vereador em exercicio segue 200 com lista vazia — a distincao vem de `info-ente`."
  [resolver-ente-publico info-ente vereadores-em-exercicio]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))]
      (if-not (info-ente ente-id)
        (http/json-resposta 404 {:erro "ente nao encontrado"})
        (http/json-resposta 200 (adapters-out-vereadores/->wire (vereadores-em-exercicio ente-id)))))))

(defn- seguir-handler
  "POST /portal/materias/:proposicao_id/acompanhar (cidadao, SO-auth). ente-id + seguidor do ATOR; guard de
  existencia da materia no controller (ausente -> nil -> 404). Devolve 201 {estado} (UPSERT; re-seguir 201 tb)."
  [repo-transparencia]
  (fn [req]
    (let [proposicao-id (adapters-in/proposicao-param->uuid (get-in req [:path-params :proposicao_id]))]
      (if-let [r (controllers/seguir! repo-transparencia (:ator req) proposicao-id)]
        (http/json-resposta 201 (adapters-out-acomp/recibo->wire (:estado r)))
        (http/json-resposta 404 {:erro "materia nao encontrada"})))))

(defn- deixar-de-seguir-handler
  "DELETE /portal/materias/:proposicao_id/acompanhar (cidadao, SO-auth). IDEMPOTENTE: 200 {cancelado} mesmo
  se nao seguia (retirar consentimento e' sempre seguro)."
  [repo-transparencia]
  (fn [req]
    (let [proposicao-id (adapters-in/proposicao-param->uuid (get-in req [:path-params :proposicao_id]))]
      (controllers/deixar-de-seguir! repo-transparencia (:ator req) proposicao-id)
      (http/json-resposta 200 (adapters-out-acomp/recibo->wire "cancelado")))))

(defn- meus-acompanhamentos-handler
  "GET /portal/acompanhamentos (cidadao, SO-auth). Lista as materias que o ATOR segue (escopo pelo seguidor
  do token — nunca ve as de outro). Resposta e' o PAR {:acompanhamentos :acompanhamentos-total} (frente
  'truncamento-familia', sitio (c)): o teto de 200 saia sem sinalizar."
  [repo-transparencia]
  (fn [req]
    (http/json-resposta 200
      (adapters-out-acomp/meus->wire (controllers/meus-acompanhamentos repo-transparencia (:ator req))))))

;; ---------- Onda E: DADOS ABERTOS (Decreto 8.777 + LAI art. 8 §3) ----------

(defn- dados-abertos-handler
  "GET /portal/casa/:ente/dados-abertos — o catalogo (PUBLICO): cada dataset com dicionario, linhas e ultima
  atualizacao. Casa inexistente -> 404, como a rota-pai e as irmas (nunca 200 com catalogo de ninguem)."
  [repo-transparencia resolver-ente-publico info-ente votacoes-com-voto-publico]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))]
      (if-not (info-ente ente-id)
        (http/json-resposta 404 {:erro "ente nao encontrado"})
        (http/json-resposta 200 (adapters-out-dados-abertos/catalogo->wire
                                 (controllers/catalogo-dados-abertos repo-transparencia votacoes-com-voto-publico
                                                                     ente-id)))))))

(defn- baixar-dataset-handler
  "GET /portal/casa/:ente/dados-abertos/:arquivo — o dataset INTEIRO em CSV (PUBLICO). 404 para Casa inexistente
  ou arquivo que nao e' do catalogo."
  [repo-transparencia resolver-ente-publico info-ente nomes-dos-vereadores votacoes-com-voto-publico]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))]
      (if-let [r (when (info-ente ente-id)
                   (controllers/dataset-csv repo-transparencia nomes-dos-vereadores votacoes-com-voto-publico ente-id
                                            (get-in req [:path-params :arquivo])))]
        (adapters-out-dados-abertos/->download r)
        (http/json-resposta 404 {:erro "dataset nao encontrado"})))))

;; ---------- Portal de VOTACOES (frente 'portal-votacoes-publicas') ----------

(defn- listar-votacoes-handler
  "GET /portal/casa/:ente/votacoes(?pagina=N) — as votacoes ENCERRADAS de sessoes PUBLICAS (nunca secreta, nunca em
  curso), a mais recente primeiro, 20 por pagina, com o TOTAL: o cliente sabe quantas existem. `:ente` malformado ->
  400; Casa inexistente -> 404 (a voz da rota-pai); `:pagina` invalida -> 400. `listar-votacoes` e' o seam do host (quem
  sabe o que e' publico: sessoes; quem guarda a votacao: legislativo)."
  [repo-transparencia resolver-ente-publico info-ente listar-votacoes]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))
          pagina  (adapters-in/query-pagina (get-in req [:query-params :pagina]))]
      (if-not (info-ente ente-id)
        (http/json-resposta 404 {:erro "ente nao encontrado"})
        (http/json-resposta 200 (adapters-out-votacao/lista->wire
                                 (controllers/votacoes-publicas repo-transparencia listar-votacoes ente-id pagina)))))))

(defn- votacao-publica-handler
  "GET /portal/casa/:ente/votacoes/:votacao_id — uma votacao encerrada de sessao publica; se nominal, o voto de cada
  vereador (pelo nome parlamentar). 404 UNICO para votacao inexistente, de outra Casa, em curso, anulada ou de sessao
  secreta (quem pergunta nao distingue uma da outra)."
  [repo-transparencia resolver-ente-publico info-ente buscar-votacao nomes-dos-vereadores]
  (fn [req]
    (let [ente-id    (resolver-ente-publico (get-in req [:path-params :ente]))
          votacao-id (adapters-in/votacao-param->uuid (get-in req [:path-params :votacao_id]))]
      (if-let [r (when (info-ente ente-id)
                   (controllers/votacao-publica repo-transparencia buscar-votacao nomes-dos-vereadores ente-id votacao-id))]
        (http/json-resposta 200 (adapters-out-votacao/detalhe->wire (:votacao r) (:nomes r)))
        (http/json-resposta 404 {:erro "votacao nao encontrada"})))))

(defn rotas
  "Fragmento de rotas do modulo transparencia (table syntax Pedestal). Recebe o `repo-transparencia`
  (Repo-Component), o `resolver-ente-publico` (seam do host, rotas publicas do Slice 1) e o interceptor
  `auth` (compartilhado, rotas autenticadas do Slice 2). `oplenario.rotas` funde este fragmento."
  [{:keys [repo-transparencia resolver-ente-publico auth objeto-store info-ente ficha-e-janelas-publicas
           nomes-dos-vereadores vereadores-em-exercicio pareceres-juridicos-publicos acesso-restrito-desde
           ;; portal de votacoes: seams do host sobre sessoes (o que e' publico) + legislativo (a votacao)
           votacoes-publicas votacao-publica
           ;; voto POR VEREADOR (perfil, CSV de votos nominais): (fn [ente-id] -> #{votacao-id}) das votacoes de sessao
           ;; publica, do host. Sem ele nenhum voto sai (fail-closed).
           votacoes-com-voto-publico]}]
  #{["/portal/casa/:ente" :get
     [(info-ente-handler info-ente resolver-ente-publico acesso-restrito-desde)]
     :route-name :transparencia/info-ente]
    ["/portal/casa/:ente/materias" :get
     [(listar-materias-handler repo-transparencia resolver-ente-publico info-ente)]
     :route-name :transparencia/listar-materias]
    ["/portal/casa/:ente/materias/:proposicao_id" :get
     [(ficha-materia-handler repo-transparencia resolver-ente-publico)]
     :route-name :transparencia/ficha-materia]
    ["/portal/casa/:ente/materias/:proposicao_id/movimentacoes" :get
     [(movimentacoes-da-materia-handler repo-transparencia resolver-ente-publico)]
     :route-name :transparencia/movimentacoes-da-materia]
    ["/portal/casa/:ente/materias/:proposicao_id/pareceres-juridicos" :get
     [(pareceres-juridicos-handler repo-transparencia resolver-ente-publico
                                   (or pareceres-juridicos-publicos (fn [_ _] {:pareceres []})))]
     :route-name :transparencia/pareceres-juridicos]
    ["/portal/casa/:ente/legislacao" :get
     [(listar-normas-handler repo-transparencia resolver-ente-publico info-ente)]
     :route-name :transparencia/listar-normas]
    ["/portal/casa/:ente/legislacao/:norma_id" :get
     [(buscar-norma-handler repo-transparencia resolver-ente-publico)]
     :route-name :transparencia/buscar-norma]
    ;; ---- Slice 4b: download BINARIO do artefato de publicacao (PUBLICO; :norma_id/artefato = literal filho
    ;;      unico de :norma_id -> sem colisao wildcard+literal no mesmo nivel do prefix-tree Pedestal 0.7) ----
    ["/portal/casa/:ente/legislacao/:norma_id/artefato" :get
     [(baixar-artefato-handler repo-transparencia resolver-ente-publico objeto-store)]
     :route-name :transparencia/baixar-artefato]
    ;; ---- a LISTA publica dos vereadores em exercicio (literal pai do perfil, no nivel de `materias`) ----
    ["/portal/casa/:ente/vereadores" :get
     [(listar-vereadores-handler resolver-ente-publico info-ente (or vereadores-em-exercicio (fn [_] [])))]
     :route-name :transparencia/listar-vereadores]
    ;; ---- Onda E fatia 2: perfil PUBLICO do vereador (`vereadores` e' mais um literal no MESMO nivel de
    ;;      `materias`/`legislacao` — literais entre si nao colidem no prefix-tree, so' wildcard+literal) ----
    ["/portal/casa/:ente/vereadores/:vereador_id" :get
     [(perfil-vereador-handler repo-transparencia resolver-ente-publico ficha-e-janelas-publicas
                               (or votacoes-com-voto-publico (constantly #{})))]
     :route-name :transparencia/perfil-vereador]
    ;; ---- Onda E: DADOS ABERTOS (mais um literal no nivel de `materias`/`legislacao`) ----
    ["/portal/casa/:ente/dados-abertos" :get
     [(dados-abertos-handler repo-transparencia resolver-ente-publico info-ente
                             (or votacoes-com-voto-publico (constantly #{})))]
     :route-name :transparencia/dados-abertos]
    ["/portal/casa/:ente/dados-abertos/:arquivo" :get
     [(baixar-dataset-handler repo-transparencia resolver-ente-publico info-ente
                              (or nomes-dos-vereadores (fn [_] {}))
                              (or votacoes-com-voto-publico (constantly #{})))]
     :route-name :transparencia/baixar-dataset]
    ;; ---- Portal de VOTACOES: mais um literal no nivel de `materias`/`legislacao` ----
    ["/portal/casa/:ente/votacoes" :get
     [(listar-votacoes-handler repo-transparencia resolver-ente-publico info-ente
                               (or votacoes-publicas (fn [_ _ _] {:votacoes [] :total 0})))]
     :route-name :transparencia/listar-votacoes]
    ["/portal/casa/:ente/votacoes/:votacao_id" :get
     [(votacao-publica-handler repo-transparencia resolver-ente-publico info-ente
                               (or votacao-publica (fn [_ _] nil))
                               (or nomes-dos-vereadores (fn [_] {})))]
     :route-name :transparencia/votacao-publica]
    ;; ---- Slice 2: acompanhamento do cidadao (autenticado, SO-auth sem papel) ----
    ["/portal/materias/:proposicao_id/acompanhar" :post
     [auth (seguir-handler repo-transparencia)]
     :route-name :transparencia/seguir]
    ["/portal/materias/:proposicao_id/acompanhar" :delete
     [auth (deixar-de-seguir-handler repo-transparencia)]
     :route-name :transparencia/deixar-de-seguir]
    ["/portal/acompanhamentos" :get
     [auth (meus-acompanhamentos-handler repo-transparencia)]
     :route-name :transparencia/meus-acompanhamentos]})
