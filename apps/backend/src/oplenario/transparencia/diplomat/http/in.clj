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
            [oplenario.transparencia.adapters.out.ente :as adapters-out-ente]
            [oplenario.transparencia.adapters.out.materia :as adapters-out-materia]
            [oplenario.transparencia.adapters.out.norma :as adapters-out-norma]
            [oplenario.transparencia.adapters.out.parlamentar :as adapters-out-parlamentar]
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
  [info-ente resolver-ente-publico]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))]
      (if-let [e (info-ente ente-id)]
        (http/json-resposta 200 (adapters-out-ente/->wire e))
        (http/json-resposta 404 {:erro "ente nao encontrado"})))))

(defn- listar-materias-handler
  [repo-transparencia resolver-ente-publico]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))]
      (http/json-resposta 200
        (adapters-out-materia/->wires (controllers/listar-materias repo-transparencia ente-id))))))

(defn- ficha-materia-handler
  [repo-transparencia resolver-ente-publico]
  (fn [req]
    (let [ente-id       (resolver-ente-publico (get-in req [:path-params :ente]))
          proposicao-id (adapters-in/proposicao-param->uuid (get-in req [:path-params :proposicao_id]))]
      (if-let [detalhe (controllers/ficha-materia repo-transparencia ente-id proposicao-id)]
        (http/json-resposta 200 (adapters-out-materia/ficha->wire detalhe (:norma detalhe)))
        (http/json-resposta 404 {:erro "materia nao encontrada"})))))

(defn- listar-normas-handler
  "GET /portal/casa/:ente/legislacao(?tipo=&ano=&numero=) — acervo as-enacted (F6c Slice 3). Query-params
  OPCIONAIS coagidos na borda (ano/numero nao-inteiro -> 400); ausentes -> filtro vazio = compat Slice 1."
  [repo-transparencia resolver-ente-publico]
  (fn [req]
    (let [ente-id (resolver-ente-publico (get-in req [:path-params :ente]))
          filtro  (adapters-in/filtro-legislacao (:query-params req))]
      (http/json-resposta 200
        (adapters-out-norma/->wires (controllers/listar-normas repo-transparencia ente-id filtro))))))

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
  [repo-transparencia resolver-ente-publico ficha-e-janelas-publicas]
  (fn [req]
    (let [ente-id     (resolver-ente-publico (get-in req [:path-params :ente]))
          vereador-id (adapters-in/vereador-param->uuid (get-in req [:path-params :vereador_id]))]
      (if-let [{:keys [ficha janelas]} (ficha-e-janelas-publicas ente-id vereador-id)]
        (http/json-resposta 200
          (adapters-out-parlamentar/->wire
           ficha (controllers/perfil-parlamentar repo-transparencia ente-id vereador-id janelas)))
        (http/json-resposta 404 {:erro "vereador nao encontrado"})))))

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
  do token — nunca ve as de outro)."
  [repo-transparencia]
  (fn [req]
    (http/json-resposta 200
      (adapters-out-acomp/minhas->wire (controllers/meus-acompanhamentos repo-transparencia (:ator req))))))

(defn rotas
  "Fragmento de rotas do modulo transparencia (table syntax Pedestal). Recebe o `repo-transparencia`
  (Repo-Component), o `resolver-ente-publico` (seam do host, rotas publicas do Slice 1) e o interceptor
  `auth` (compartilhado, rotas autenticadas do Slice 2). `oplenario.rotas` funde este fragmento."
  [{:keys [repo-transparencia resolver-ente-publico auth objeto-store info-ente ficha-e-janelas-publicas]}]
  #{["/portal/casa/:ente" :get
     [(info-ente-handler info-ente resolver-ente-publico)]
     :route-name :transparencia/info-ente]
    ["/portal/casa/:ente/materias" :get
     [(listar-materias-handler repo-transparencia resolver-ente-publico)]
     :route-name :transparencia/listar-materias]
    ["/portal/casa/:ente/materias/:proposicao_id" :get
     [(ficha-materia-handler repo-transparencia resolver-ente-publico)]
     :route-name :transparencia/ficha-materia]
    ["/portal/casa/:ente/legislacao" :get
     [(listar-normas-handler repo-transparencia resolver-ente-publico)]
     :route-name :transparencia/listar-normas]
    ["/portal/casa/:ente/legislacao/:norma_id" :get
     [(buscar-norma-handler repo-transparencia resolver-ente-publico)]
     :route-name :transparencia/buscar-norma]
    ;; ---- Slice 4b: download BINARIO do artefato de publicacao (PUBLICO; :norma_id/artefato = literal filho
    ;;      unico de :norma_id -> sem colisao wildcard+literal no mesmo nivel do prefix-tree Pedestal 0.7) ----
    ["/portal/casa/:ente/legislacao/:norma_id/artefato" :get
     [(baixar-artefato-handler repo-transparencia resolver-ente-publico objeto-store)]
     :route-name :transparencia/baixar-artefato]
    ;; ---- Onda E fatia 2: perfil PUBLICO do vereador (`vereadores` e' mais um literal no MESMO nivel de
    ;;      `materias`/`legislacao` — literais entre si nao colidem no prefix-tree, so' wildcard+literal) ----
    ["/portal/casa/:ente/vereadores/:vereador_id" :get
     [(perfil-vereador-handler repo-transparencia resolver-ente-publico ficha-e-janelas-publicas)]
     :route-name :transparencia/perfil-vereador]
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
