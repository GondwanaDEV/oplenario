(ns oplenario.cadastros.diplomat.http.in
  "Fronteira de IO HTTP de ENTRADA do cadastros (§22.10 diplomat/http/in, ADR-0001) — a borda HTTP do modulo.
  Task 5: duas rotas de LEITURA (GET) — lista de vereadores (`/cadastros/vereadores`) + ficha composta de um
  vereador (`/cadastros/vereadores/:id`). Task 6: 4 rotas de ESCRITA (criar/editar vereador, registrar
  mandato/licenca) + GET `/cadastros/legislatura-vigente` (leitura auxiliar p/ o seletor do form de mandato).
  Mesmo gate grosso (papel 'secretario') em TODAS; sem authz fina (nenhum recurso 'de posse' aqui). `data`
  (LocalDate `hoje`) e' resolvida AQUI, na borda, a partir do `relogio` injetado (mesmo padrao de
  `meu-voto-handler` no legislativo, review MEDIUM fe-11-parecer) — o controller nunca le o relogio. `id` do
  path e' um UUID coagido AQUI (`parse-uuid`); um path param que nao parseia (nem um vereador de outro
  tenant, nem um id mal formado) -> 404 uniforme, NUNCA 500 (mesmo contrato de 404 das rotas irmas de
  legislativo). Conflitos de dominio (`:conflito/mandato-sobreposto`, `:conflito/sem-mandato-vigente`,
  `:conflito/sem-mandato-licenciado`, `:conflito/retorno-anterior-ao-inicio`) sao capturados LOCALMENTE em
  cada handler de escrita -> 409 (nunca sobem ao interceptor global `erro`, que so' sabe mapear
  `:validacao/invalido` -> 400). A REASSUNCAO (`POST .../reassuncao`) fecha o par de `/licencas`: sem ela
  `mandato_licenca` nao tinha UPDATE nenhum, a janela de exercicio publica congelava para sempre e o mandato
  ficava 'licenciado' bloqueando a proxima licenca."
  (:require [oplenario.cadastros.adapters.in.vereador :as adapters-in]
            [oplenario.cadastros.adapters.out.legislatura :as adapters-leg]
            [oplenario.cadastros.adapters.out.vereador :as adapters]
            [oplenario.cadastros.controllers :as controllers]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.tempo :as tempo])
  (:import (java.time ZoneId)))

(set! *warn-on-reflection* true)

;; fuso civil p/ `hoje` — mesma constante de legislativo.diplomat.http.in/zona-civil e
;; participacao.controllers/zona-civil (mandato/cargo-na-Mesa vigentes correm por data civil, nao UTC).
(def ^:private zona-civil (ZoneId/of "America/Fortaleza"))

(defn- listar-handler
  "GET /cadastros/vereadores. Envelope {:vereadores [VereadorLinhaOut ...]} — mapa fechado, espaco p/
  metadata futura (nunca o array cru na raiz). O corpo 200 e' o envelope INTEIRO validado contra
  wire/ListaVereadoresOut (`lista-envelope->wire`), nao montado inline aqui."
  [repo relogio]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          hoje (tempo/hoje-de (tempo/agora relogio) zona-civil)]
      (http/json-resposta 200 (adapters/lista-envelope->wire (controllers/listar-vereadores repo ente-id hoje))))))

(defn- ficha-handler
  "GET /cadastros/vereadores/:id. `id` invalido (nao-UUID) -> `parse-uuid` nil -> mesmo caminho 404 do
  vereador inexistente/de-outro-tenant (o controller so' e' chamado com id nao-nil, `and` curto-circuita)."
  [repo relogio]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          id (parse-uuid (get-in req [:path-params :id]))
          hoje (tempo/hoje-de (tempo/agora relogio) zona-civil)]
      (if-let [f (and id (controllers/ficha-vereador repo ente-id id hoje))]
        (http/json-resposta 200 (adapters/ficha->wire f))
        (http/json-resposta 404 {:erro "vereador nao encontrado"})))))

(defn- criar-vereador-handler
  "POST /cadastros/vereadores. Corpo invalido -> adapters/in lanca :validacao/invalido -> 400 (interceptor
  global `erro`, nunca tratado aqui)."
  [repo]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          m (adapters-in/criar-vereador->dominio ator (:json-params req))]
      (http/json-resposta 201 (controllers/criar-vereador repo ente-id m)))))

(defn- editar-vereador-handler
  "PATCH /cadastros/vereadores/:id. `id` malformado -> `parse-uuid` nil -> mesmo caminho 404 (nunca 500)."
  [repo]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (parse-uuid (get-in req [:path-params :id]))
          campos (adapters-in/editar-vereador->dominio (:json-params req))]
      (if (and id (pos? (controllers/editar-vereador repo ente-id id campos)))
        (http/json-resposta 200 {:id (str id)})
        (http/json-resposta 404 {:erro "vereador nao encontrado"})))))

(defn- registrar-mandato-handler
  "POST /cadastros/vereadores/:id/mandatos. 409 (mandato sobreposto) capturado AQUI, local — nunca vira 500
  via o interceptor global `erro`."
  [repo]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (parse-uuid (get-in req [:path-params :id]))
          m (adapters-in/registrar-mandato->dominio ator id (:json-params req))]
      (try
        (if-let [r (and id (controllers/registrar-mandato repo ente-id m))]
          (http/json-resposta 201 r)
          (http/json-resposta 404 {:erro "vereador ou legislatura nao encontrada"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/mandato-sobreposto (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "ja existe mandato vigente sobreposto para este vereador"})
            (throw e)))))))

(defn- registrar-licenca-handler
  "POST /cadastros/vereadores/:id/licencas. `hoje` resolvido AQUI, na borda, a partir do `relogio` injetado
  (mesmo padrao de listar-handler/ficha-handler). 409 (sem mandato vigente) capturado local."
  [repo relogio]
  (fn [req]
    (let [ator (:ator req) ente-id (:ente-id ator)
          id (parse-uuid (get-in req [:path-params :id]))
          hoje (tempo/hoje-de (tempo/agora relogio) zona-civil)
          l (adapters-in/registrar-licenca->dominio ator hoje (:json-params req))]
      (try
        (if-let [r (and id (controllers/registrar-licenca repo ente-id id l hoje))]
          (http/json-resposta 201 r)
          (http/json-resposta 404 {:erro "vereador nao encontrado"}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :conflito/sem-mandato-vigente (:tipo (ex-data e)))
            (http/json-resposta 409 {:erro "vereador sem mandato vigente para licenciar"})
            (throw e)))))))

(def ^:private conflitos-de-reassuncao
  "Os tres conflitos de dominio da reassuncao -> a mensagem 409 de cada um. Mapa (e nao um `if` encadeado)
  porque sao tres e cresceriam mal em cadeia; o `throw` do `else` continua sendo a regra da casa: o handler
  NUNCA engole ex-info que nao reconhece."
  {:conflito/sem-mandato-licenciado    "vereador sem mandato licenciado para reassumir"
   :conflito/retorno-anterior-ao-inicio "data de reassuncao anterior ao inicio da licenca em curso"
   :conflito/mandato-sobreposto        "reabrir este mandato sobreporia outro mandato vigente do vereador"})

(defn- reassumir-mandato-handler
  "POST /cadastros/vereadores/:id/reassuncao. Fecha as licencas abertas do mandato na VESPERA de
  `reassumiu-em` e devolve o mandato a 'vigente' — o inverso de `/licencas`, e no MESMO papel ('secretario'):
  quem licencia reassume.

  200, nao 201: nenhum recurso e' criado (mesmo precedente de editar-vereador-handler/ligar-identidade-handler,
  as duas outras escritas do modulo que so' transicionam). A data sai STRINGIFICADA — `json-resposta` usa o
  ObjectMapper padrao do jsonista, que nao serializa `java.time.LocalDate` (mesma razao do `->str` em
  adapters/out/vereador) — e `fim` sai `null`, nunca a string \"\", quando NENHUMA licenca foi encerrada
  (o Repo devolve `:fim` nil nesse caso; `(str nil)` daria \"\"). A data da VOLTA e' do corpo, porque a
  reassuncao e' um FATO DATADO que a secretaria registra depois (o retorno de ontem, o de semana passada),
  nunca 'agora' — mas o `relogio` entra assim mesmo, como TETO: fato datado e' no passado, e uma data
  futura gravaria na licenca um `fim` que nenhum caminho de escrita alcanca depois (ver
  `reassumir-mandato->dominio`)."
  [repo relogio]
  (fn [req]
    (let [id (parse-uuid (get-in req [:path-params :id]))
          ente-id (:ente-id (:ator req))
          hoje (tempo/hoje-de (tempo/agora relogio) zona-civil)
          dia (adapters-in/reassumir-mandato->dominio hoje (:json-params req))]
      (try
        (if-let [r (and id (controllers/reassumir-mandato repo ente-id id dia))]
          (http/json-resposta 200 {:id (str (:id r)) :fim (some-> (:fim r) str)})
          (http/json-resposta 404 {:erro "vereador nao encontrado"}))
        (catch clojure.lang.ExceptionInfo e
          (if-let [msg (conflitos-de-reassuncao (:tipo (ex-data e)))]
            (http/json-resposta 409 {:erro msg})
            (throw e)))))))

(defn- ligar-identidade-handler
  "PATCH /cadastros/vereadores/:id/identidade (Onda D Slice 5 Task 9). Gated `admin_ente` (NAO
  'secretario', ver `rotas` abaixo): ligar identidade e' parte de CONCEDER ACESSO — um 'secretario' pode
  cadastrar um vereador mas nunca deveria poder ligar a PROPRIA identidade a esse cadastro e votar.

  `identidade-existe?` e' o guard de SERVICO injetado pelo host (mesma forma de `info-ente`) — `cadastros`
  nunca importa `identidade` (§22.10) e nao ha' FK cross-schema (`identidade_id` e' so' um GUARD ref, ver
  a migration `...0010-cadastros`) p/ garantir a referencia no banco. O adapters/in roda INCONDICIONAL no
  `let` (mesmo padrao de editar-vereador-handler acima) — corpo invalido (`identidade-id` que nao parseia
  UUID) -> 400 mesmo que `:id` do path TAMBEM seja invalido (validacao tem precedencia). Com o corpo valido:
  `id` do path invalido OU identidade-existe? falso -> a MESMA mensagem 404 (review Task 9 IMPORTANT-2:
  `identidade-por-id` e' SUPRATENANT — sem colapsar, um `admin_ente` de QUALQUER Casa poderia usar o texto
  do erro como oraculo pra descobrir, por tentativa, se um `identidade-id` chutado existe em algum lugar
  do sistema; mesmo raciocinio ja' aplicado em registrar-mandato-handler, que colapsa vereador/legislatura
  ausentes em \"vereador ou legislatura nao encontrada\"). Conflito (identidade ja' ligada a OUTRO vereador
  nesta Casa, indice UNIQUE parcial) -> 409 capturado LOCALMENTE (mesmo padrao de registrar-mandato-handler/
  registrar-licenca-handler acima)."
  [repo identidade-existe?]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))
          id (parse-uuid (get-in req [:path-params :id]))
          ident (adapters-in/ligar-identidade->dominio (:json-params req))]
      (cond
        (or (nil? id) (not (identidade-existe? ident)))
        (http/json-resposta 404 {:erro "vereador ou identidade nao encontrada"})
        :else
        (try
          (if (pos? (controllers/ligar-identidade repo ente-id id ident))
            (http/json-resposta 200 {:id (str id) :identidade-id (str ident)})
            (http/json-resposta 404 {:erro "vereador nao encontrado"}))
          (catch clojure.lang.ExceptionInfo e
            (if (= :conflito/identidade-ja-vinculada (:tipo (ex-data e)))
              (http/json-resposta 409 {:erro "identidade ja vinculada a outro vereador nesta Casa"})
              (throw e))))))))

(defn- legislatura-vigente-handler
  "GET /cadastros/legislatura-vigente. Sem legislatura vigente -> 404 (nunca corpo vazio 200)."
  [repo]
  (fn [req]
    (let [ente-id (:ente-id (:ator req))]
      (if-let [leg (controllers/legislatura-vigente repo ente-id)]
        (http/json-resposta 200 (adapters-leg/->wire leg))
        (http/json-resposta 404 {:erro "nenhuma legislatura vigente"})))))

(defn rotas
  "Fragmento de rotas de vereadores (table syntax Pedestal). Recebe o interceptor `auth` (compartilhado), o
  `repo-cadastros` (Repo-Component do proprio modulo), o `relogio` (kernel/tempo, injetado pelo host —
  producao le o relogio do sistema, teste crava o instante; mesmo contrato de `legislativo-http/rotas`/
  `participacao-http/rotas`) e `identidade-existe?` (Onda D Slice 5 Task 9 — guard de SERVICO injetado
  pelo host, mesma forma de `info-ente`; `cadastros` nunca importa `identidade`, §22.10). Todas as rotas
  EXIGEM authz grossa — a maioria papel 'secretario', MAS `/identidade` exige `admin_ente` (ligar
  identidade e' parte de CONCEDER ACESSO, nao de cadastro; ver docstring de `ligar-identidade-handler`)."
  [{:keys [auth repo-cadastros relogio identidade-existe?]}]
  (let [papel (it/exige-papel "secretario")
        papel-admin-ente (it/exige-papel "admin_ente")]
    #{["/cadastros/vereadores"     :get [auth papel (listar-handler repo-cadastros relogio)]
       :route-name :cadastros/listar-vereadores]
      ["/cadastros/vereadores/:id" :get [auth papel (ficha-handler repo-cadastros relogio)]
       :route-name :cadastros/ficha-vereador]
      ["/cadastros/vereadores" :post [auth papel it/corpo-json (criar-vereador-handler repo-cadastros)]
       :route-name :cadastros/criar-vereador]
      ["/cadastros/vereadores/:id" :patch [auth papel it/corpo-json (editar-vereador-handler repo-cadastros)]
       :route-name :cadastros/editar-vereador]
      ["/cadastros/vereadores/:id/mandatos" :post
       [auth papel it/corpo-json (registrar-mandato-handler repo-cadastros)]
       :route-name :cadastros/registrar-mandato]
      ["/cadastros/vereadores/:id/licencas" :post
       [auth papel it/corpo-json (registrar-licenca-handler repo-cadastros relogio)]
       :route-name :cadastros/registrar-licenca]
      ["/cadastros/vereadores/:id/reassuncao" :post
       [auth papel it/corpo-json (reassumir-mandato-handler repo-cadastros relogio)]
       :route-name :cadastros/reassumir-mandato]
      ["/cadastros/vereadores/:id/identidade" :patch
       [auth papel-admin-ente it/corpo-json (ligar-identidade-handler repo-cadastros identidade-existe?)]
       :route-name :cadastros/ligar-identidade]
      ["/cadastros/legislatura-vigente" :get [auth papel (legislatura-vigente-handler repo-cadastros)]
       :route-name :cadastros/legislatura-vigente]}))
