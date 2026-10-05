(ns oplenario.admin-sistema.diplomat.http.in
  "Rotas do CONSOLE DO OPERADOR (ADR-0016) — SUPRATENANT: o ator nao tem Casa, e o interceptor
  `it/autenticacao-operador` recusa qualquer credencial de Casa (cross-esfera = 401, sem fail-open). O prefixo
  `/operacao` separa as duas esferas tambem na URL.

  O login espelha o da Casa (`identidade/diplomat/http/auth_in`): descoberta publica -> PKCE no BFF -> o token
  e' RE-VERIFICADO aqui (nunca se confia no BFF) -> sessao opaca em `admin_sistema.sessao_operador`."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [oplenario.admin-sistema.adapters.in.ente :as in-ente]
            [oplenario.admin-sistema.adapters.out.ente :as out-ente]
            [oplenario.admin-sistema.adapters.out.ia :as out-ia]
            [oplenario.admin-sistema.autenticacao :as auten]
            [oplenario.admin-sistema.components.idp-admin :as idp-op]
            [oplenario.admin-sistema.components.repositorio :as repo]
            [oplenario.admin-sistema.controllers :as controllers]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]
            [oplenario.kernel.components.objeto-store :as objeto-store]
            [oplenario.kernel.tempo :as tempo])
  (:import (java.time Duration Instant)))

(defn- descoberta-handler
  "GET /operacao/descoberta — o que o BFF precisa para comecar o PKCE no realm do operador (URL publica)."
  [{:keys [realm base-url base-url-publico client-id]}]
  (fn [_]
    (http/json-resposta 200 {:realm realm :base-url (if (str/blank? base-url-publico) base-url base-url-publico)
                             :client-id client-id})))

(defn- corpo->token [json-params]
  (let [token (when (map? json-params) (get json-params "token"))]
    (when-not (and (string? token) (not (str/blank? token)) (<= (count token) 16384))
      (throw (ex-info "token ausente ou invalido" {:tipo :validacao/invalido :campo :token})))
    token))

(defn- registrar-sem-trancar!
  "Grava na atuacao e devolve o registro, ou nil se nao gravou (vai para o `log/error`). NUNCA lanca: a entrada do operador
  nao pode ficar trancada porque a corrente da atuacao caiu — quem entra pode ser quem vai consertar (mesma regra da
  entrada da Casa, ADR-0017, adendo de 05/10/2026)."
  [repo-op registro]
  (try (repo/registrar-atuacao! repo-op registro)
       (catch Exception e
         (log/error e "admin-sistema: registro da ENTRADA do operador nao gravado na atuacao; a entrada SEGUE"
                    {:acao (:acao registro) :operador-id (:operador-id registro)})
         nil)))

(defn- mint-handler
  "POST /operacao/sessoes — token do realm do operador -> operador ATIVO -> sessao opaca. A entrada fica na atuacao
  (retencao maxima, 12.5) como PAR: a TENTATIVA (`entrada-no-console-iniciada`) logo antes de criar a sessao, o desfecho
  (`entrou-no-console`, ou `entrada-no-console-falhou` se a sessao nao nasceu) depois, apontando a tentativa em
  `detalhe.tentativa`. Sessao que existe tem tentativa na corrente. A atuacao fora do ar NUNCA tranca a entrada
  (ADR-0017, adendo de 05/10/2026): nem a tentativa nem o desfecho recusam o login. Operador recusado (401) nao tem
  tentativa: nada foi concedido."
  [idp repo-op relogio {:keys [absoluta-h ociosa-min]}]
  (fn [req]
    (let [token (corpo->token (:json-params req))]
      (if-let [claims (idp-op/verificar-token-operador idp token)]
        (if-let [ator (auten/ator-do-operador repo-op (:operador-id claims))]
          (let [^Instant agora (tempo/agora relogio)
                operador-id (:operador-id ator)
                tentativa (registrar-sem-trancar! repo-op {:operador-id operador-id :acao "entrada-no-console-iniciada"})
                aponta (when tentativa {:tentativa (str (:id tentativa))})
                seg (try (repo/criar-sessao-operador! repo-op {:operador-id operador-id
                                                               :expira-em (.plus agora (Duration/ofHours absoluta-h))
                                                               :ocioso-ate (.plus agora (Duration/ofMinutes ociosa-min))})
                         (catch Exception e
                           (registrar-sem-trancar! repo-op {:operador-id operador-id :acao "entrada-no-console-falhou"
                                                            :detalhe aponta})
                           (throw e)))]
            (registrar-sem-trancar! repo-op {:operador-id operador-id :acao "entrou-no-console" :detalhe aponta})
            (http/json-resposta 200 {:sessao seg}))
          (http/json-resposta 401 {:erro "operador inativo"}))
        (http/json-resposta 401 {:erro "token invalido"})))))

(defn- logout-handler [repo-op]
  (fn [req]
    (when-let [seg (it/cookie req it/nome-cookie-operacao)]
      (repo/apagar-sessao-operador! repo-op seg))
    {:status 204 :headers {} :body nil}))

(defn- eu-handler [req]
  (let [{:keys [operador-id nome email papeis]} (:ator req)]
    (http/json-resposta 200 {:operador {:id (str operador-id) :nome nome :email email :papeis (vec (sort papeis))}})))

;; ---- registro de Casas (12.1) ----

(defn- com-erros
  "Os erros do registro que viram resposta: Casa inexistente -> 404; estado que nao permite -> 409 (com o porque e, quando
  ha', a `causa` estavel que a tela le); o seam pesado que esta instalacao nao tem (exportar, apagar) -> 503 NOMEADO."
  [f]
  (fn [req]
    (try (f req)
         (catch clojure.lang.ExceptionInfo e
           (let [{:keys [tipo causa extra]} (ex-data e)]
             (case tipo
               :admin-sistema/nao-encontrada (http/json-resposta 404 {:erro (ex-message e)})
               :admin-sistema/conflito (http/json-resposta 409 (cond-> (merge {:erro (ex-message e)} extra)
                                                                 causa (assoc :causa causa)))
               :admin-sistema/indisponivel (http/json-resposta 503 {:erro (ex-message e) :causa (or causa "indisponivel")})
               (throw e))))
         (catch org.postgresql.util.PSQLException e
           ;; a corrida que o banco segura (dois pedidos abertos na mesma Casa; a mesma pessoa nas duas pontas)
           (if (#{"23505" "23514"} (.getSQLState e))
             (http/json-resposta 409 {:erro "o pedido mudou enquanto voce decidia — recarregue a Casa"})
             (throw e))))))

(defn- ente-do-path [req] (in-ente/ente-param->uuid (get-in req [:path-params :ente])))

(defn- agora [relogio] (tempo/agora relogio))

(defn- listar-handler [repo-op deps relogio]
  (fn [_] (http/json-resposta 200 (out-ente/lista->wire (controllers/listar-casas repo-op deps (agora relogio))))))

(defn- provisionar-handler [repo-op deps]
  (fn [req]
    (let [casa (in-ente/provisionar->dominio (:json-params req))]
      (http/json-resposta 201 (out-ente/provisionada->wire
                               (controllers/provisionar-casa! repo-op deps (:ator req) casa))))))

(defn- ficha-handler [repo-op deps relogio]
  (com-erros (fn [req] (http/json-resposta 200 (out-ente/ficha->wire
                                                (controllers/ficha-da-casa repo-op deps (ente-do-path req)
                                                                           (agora relogio)))))))

(defn- atos-sem-desfecho-handler
  "GET /operacao/atos-sem-desfecho — ADR-0017 (adendo de 05/10/2026): os atos da Operacao (entrada no console, orcamento de
  IA, reaplicacao do login) iniciados cujo desfecho a atuacao nao registrou. So' leitura: o desfecho que falta pode ser
  que o ato nao aconteceu ou que aconteceu e o registro caiu — quem confere e' a pessoa. Mais novo que a tolerancia
  (`controllers/tolerancia-sem-desfecho-segundos`) ainda pode estar em curso e nao entra. Total sempre; lista truncada
  diz `truncado`."
  [repo-op relogio]
  (fn [_] (http/json-resposta 200 (out-ente/sem-desfecho->wire (controllers/atos-sem-desfecho repo-op (agora relogio))))))

(defn- reenviar-convite-handler [repo-op deps]
  (com-erros (fn [req] (http/json-resposta 200 (out-ente/casa->wire
                                                (controllers/reenviar-convite! repo-op deps (:ator req)
                                                                               (ente-do-path req)))))))

(defn- reprovisionar-realm-handler [repo-op deps]
  (com-erros (fn [req] (http/json-resposta 200 (out-ente/casa->wire
                                                (controllers/reprovisionar-realm! repo-op deps (:ator req)
                                                                                  (ente-do-path req)))))))

;; ---- ADR-0018 (fatia 1): suspender, reativar, iniciar o encerramento ----

(defn- pedido-do-path [req] (in-ente/ente-param->uuid (get-in req [:path-params :pedido])))

(defn- transicao [f]
  (com-erros (fn [req] (http/json-resposta 200 (out-ente/transicao->wire (f req))))))

(defn- pedir-suspensao-handler [repo-op deps relogio]
  (transicao (fn [req] (controllers/pedir-suspensao! repo-op deps (:ator req) (ente-do-path req)
                                                     (in-ente/pedir-suspensao->dominio (:json-params req))
                                                     (agora relogio)))))

(defn- iniciar-encerramento-handler [repo-op deps relogio]
  (transicao (fn [req] (controllers/iniciar-encerramento! repo-op deps (:ator req) (ente-do-path req)
                                                          (in-ente/iniciar-encerramento->dominio (:json-params req))
                                                          (agora relogio)))))

(defn- aprovar-handler [repo-op deps relogio]
  (transicao (fn [req] (controllers/aprovar-pedido! repo-op deps (:ator req) (pedido-do-path req)
                                                    (in-ente/decisao->justificativa (:json-params req))
                                                    (agora relogio)))))

(defn- recusar-handler [repo-op deps]
  (transicao (fn [req] (controllers/recusar-pedido! repo-op deps (:ator req) (pedido-do-path req)
                                                    (in-ente/decisao->justificativa (:json-params req))))))

(defn- reativar-handler [repo-op deps]
  (transicao (fn [req] (controllers/reativar! repo-op deps (:ator req) (ente-do-path req)
                                              (in-ente/reativar->justificativa (:json-params req))))))

;; ---- ADR-0018 (fatia 2): encerrar — no console (supratenant) ----

(defn- exportacao-do-path [req] (in-ente/ente-param->uuid (get-in req [:path-params :exportacao])))

(defn- gerar-exportacao-operador-handler [repo-op deps relogio]
  (com-erros (fn [req]
               (http/json-resposta 202 (out-ente/exportacao->wire
                                        (controllers/gerar-exportacao! repo-op deps {:tipo "operador"
                                                                                     :id (get-in req [:ator :operador-id])}
                                                                       (ente-do-path req) (agora relogio)))))))

(defn- registrar-oficio-handler [repo-op deps relogio]
  (com-erros (fn [req]
               (http/json-resposta 200 (out-ente/exportacao->wire
                                        (controllers/registrar-oficio! repo-op deps (:ator req) (exportacao-do-path req)
                                                                       (in-ente/oficio->texto (:json-params req))
                                                                       (agora relogio)))))))

(defn- destino-acervo-handler [repo-op deps]
  (transicao (fn [req] (controllers/definir-destino-acervo! repo-op deps (:ator req) (ente-do-path req)
                                                            (in-ente/destino-acervo->url (:json-params req))))))

(defn- pedir-apagamento-handler [repo-op deps relogio]
  (transicao (fn [req] (controllers/pedir-apagamento! repo-op deps (:ator req) (ente-do-path req)
                                                      (in-ente/pedir-apagamento->justificativa (:json-params req))
                                                      (agora relogio)))))

(defn- retomar-apagamento-handler [repo-op deps relogio]
  (transicao (fn [req] (controllers/retomar-apagamento! repo-op deps (:ator req) (ente-do-path req) (agora relogio)))))

;; ---- ADR-0018 (fatia 2): a exportacao vista pela CASA (o admin_ente, interceptor da Casa) ----

(defn- ente-do-ator [req] (get-in req [:ator :ente-id]))

(defn- exportacoes-da-casa-handler [repo-op deps]
  (fn [req]
    (http/json-resposta 200 (out-ente/da-casa->wire
                             (controllers/exportacoes-para-a-casa repo-op deps (ente-do-ator req))))))

(defn- gerar-exportacao-casa-handler [repo-op deps relogio]
  (com-erros (fn [req]
               (-> (http/json-resposta 202 (out-ente/exportacao->wire
                                            (controllers/gerar-exportacao! repo-op deps
                                                                           {:tipo "admin_ente"
                                                                            :id (get-in req [:ator :identidade-id])}
                                                                           (ente-do-ator req) (agora relogio))))
                   (assoc :auditoria {:rotulo "pediu a exportação completa da Câmara"})))))

(defn- baixar-exportacao-handler [repo-op store]
  (com-erros (fn [req]
               (let [e (controllers/arquivo-para-baixar! repo-op (ente-do-ator req) (exportacao-do-path req))]
                 (if-let [in (objeto-store/abrir store (:chave-objeto e))]
                   (out-ente/exportacao->download e in)
                   (http/json-resposta 404 {:erro "o arquivo desta exportacao nao esta' mais disponivel"
                                            :causa "arquivo-ausente"}))))))

(defn- confirmar-recebimento-handler [repo-op deps relogio]
  (com-erros (fn [req]
               (-> (http/json-resposta 200 (out-ente/exportacao->wire
                                            (controllers/confirmar-recebimento!
                                             repo-op deps (:ator req) (ente-do-ator req) (exportacao-do-path req)
                                             (in-ente/confirmar-recebimento->sha256 (:json-params req))
                                             (agora relogio))))
                   (assoc :auditoria {:rotulo "confirmou o recebimento da exportação completa"})))))

(defn rotas-da-casa
  "ADR-0018 (fatia 2): a exportacao completa (9.6) vista pela Casa — `auth` e' o interceptor da CASA (o host o passa) e
  so' o `admin_ente` entra. Supratenant no armazenamento (a linha sobrevive ao apagamento), mas o ator e' da Casa: a
  Casa e' a do ator, nunca a do caminho. Gerar e confirmar passam numa Casa suspensa (allowlist de
  `oplenario.restricao-da-casa`); na Casa encerrada, tudo e' 410."
  [{:keys [auth repo-admin-sistema relogio objeto-store deps-registro]}]
  (let [papel (it/exige-papel "admin_ente")]
    #{["/administracao/exportacoes" :get [auth papel (exportacoes-da-casa-handler repo-admin-sistema deps-registro)]
       :route-name :exportacao-da-casa/listar]
      ["/administracao/exportacoes" :post
       [auth papel (gerar-exportacao-casa-handler repo-admin-sistema deps-registro relogio)]
       :route-name :exportacao-da-casa/gerar]
      ["/administracao/exportacoes/:exportacao/arquivo" :get
       [auth papel (baixar-exportacao-handler repo-admin-sistema objeto-store)]
       :route-name :exportacao-da-casa/baixar]
      ["/administracao/exportacoes/:exportacao/confirmacao" :post
       [auth papel it/corpo-json (confirmar-recebimento-handler repo-admin-sistema deps-registro relogio)]
       :route-name :exportacao-da-casa/confirmar-recebimento]}))

;; ---- observabilidade da IA (Onda E, §22.8) ----

(def ^:private janelas-ia
  "As janelas que a tela oferece (o satelite aceita 1-168; o console so' pede estas)."
  #{24 168})

(defn- horas-param [s]
  (let [h (if (str/blank? s) 24 (parse-long s))]
    (when-not (contains? janelas-ia h)
      (throw (ex-info "horas deve ser 24 ou 168" {:tipo :validacao/invalido :campo :horas})))
    h))

(defn- observabilidade-ia-handler
  "GET /operacao/ia?horas=24|168 — a saude da IA em todas as Casas. IA fora (ou nao ligada) nao e' 500: a resposta diz
  `disponivel: false` e a tela avisa (R-IA-1)."
  [observabilidade-ia]
  (fn [req]
    (let [horas (horas-param (get-in req [:query-params :horas]))
          o (when observabilidade-ia
              (try (observabilidade-ia horas)
                   (catch clojure.lang.ExceptionInfo e
                     (if (= :ia/indisponivel (:tipo (ex-data e))) nil (throw e)))))]
      (http/json-resposta 200 (out-ia/observabilidade->wire horas o)))))

(defn estado-da-casa
  "O seam `estado-da-casa` que o host usa no interceptor de Casa (ADR-0018, Eixo 3): (fn [ente-id] -> {:estado :motivo
  :desde} | nil). Efetiva o preguicoso (incidente vencido, suspensao agendada cuja sessao acabou) ao ler."
  [repo-op deps relogio]
  (fn [ente-id] (controllers/estado-da-casa repo-op deps ente-id (tempo/agora relogio))))

(defn rotas
  "`operacao` = o mapa `:operacao` da config, ja' resolvido pelo host. `deps-registro` = os seams que o host injeta
  para o provisionamento cruzar cadastros/identidade/IdP das Casas sem import (§22.10). `observabilidade-ia` =
  (horas -> mapa do satelite; lanca `:ia/indisponivel`), o seam do host para a IA (admin_sistema nao importa
  integracao_ia)."
  [{:keys [idp-operacao repo-admin-sistema relogio operacao deps-registro observabilidade-ia]}]
  (let [auth (it/autenticacao-operador idp-operacao repo-admin-sistema)
        papel (it/exige-papel "operador")]
    #{["/operacao/descoberta" :get [(descoberta-handler operacao)] :route-name :admin-sistema/descoberta]
      ["/operacao/sessoes" :post [it/corpo-json (mint-handler idp-operacao repo-admin-sistema relogio (:sessao operacao))]
       :route-name :admin-sistema/mint-sessao]
      ["/operacao/sessoes" :delete [(logout-handler repo-admin-sistema)] :route-name :admin-sistema/logout-sessao]
      ["/operacao/eu" :get [auth eu-handler] :route-name :admin-sistema/eu]
      ["/operacao/casas" :get [auth papel (listar-handler repo-admin-sistema deps-registro relogio)]
       :route-name :admin-sistema/listar-casas]
      ["/operacao/casas" :post [auth papel it/corpo-json (provisionar-handler repo-admin-sistema deps-registro)]
       :route-name :admin-sistema/provisionar-casa]
      ["/operacao/casas/:ente" :get [auth papel (ficha-handler repo-admin-sistema deps-registro relogio)]
       :route-name :admin-sistema/ficha-da-casa]
      ;; ADR-0018: a sessao do console ja' exige a chave fisica (ADR-0016), entao cada operador aqui passou por ela
      ["/operacao/casas/:ente/suspensao" :post
       [auth papel it/corpo-json (pedir-suspensao-handler repo-admin-sistema deps-registro relogio)]
       :route-name :admin-sistema/pedir-suspensao]
      ["/operacao/casas/:ente/encerramento" :post
       [auth papel it/corpo-json (iniciar-encerramento-handler repo-admin-sistema deps-registro relogio)]
       :route-name :admin-sistema/iniciar-encerramento]
      ["/operacao/casas/:ente/reativacao" :post [auth papel it/corpo-json (reativar-handler repo-admin-sistema deps-registro)]
       :route-name :admin-sistema/reativar-casa]
      ["/operacao/pedidos/:pedido/aprovacao" :post
       [auth papel it/corpo-json (aprovar-handler repo-admin-sistema deps-registro relogio)]
       :route-name :admin-sistema/aprovar-pedido]
      ;; ADR-0018 (fatia 2): encerrar — exportacao, oficio, destino do acervo, apagamento (o pedido; a aprovacao e' a
      ;; mesma fila "aguardando 2o operador" acima)
      ["/operacao/casas/:ente/exportacoes" :post
       [auth papel (gerar-exportacao-operador-handler repo-admin-sistema deps-registro relogio)]
       :route-name :admin-sistema/gerar-exportacao]
      ["/operacao/exportacoes/:exportacao/oficio" :post
       [auth papel it/corpo-json (registrar-oficio-handler repo-admin-sistema deps-registro relogio)]
       :route-name :admin-sistema/registrar-oficio-de-recebimento]
      ["/operacao/casas/:ente/destino-acervo" :post
       [auth papel it/corpo-json (destino-acervo-handler repo-admin-sistema deps-registro)]
       :route-name :admin-sistema/definir-destino-acervo]
      ["/operacao/casas/:ente/apagamento" :post
       [auth papel it/corpo-json (pedir-apagamento-handler repo-admin-sistema deps-registro relogio)]
       :route-name :admin-sistema/pedir-apagamento]
      ["/operacao/casas/:ente/apagamento/retomada" :post
       [auth papel (retomar-apagamento-handler repo-admin-sistema deps-registro relogio)]
       :route-name :admin-sistema/retomar-apagamento]
      ["/operacao/pedidos/:pedido/recusa" :post [auth papel it/corpo-json (recusar-handler repo-admin-sistema deps-registro)]
       :route-name :admin-sistema/recusar-pedido]
      ["/operacao/casas/:ente/convite" :post [auth papel (reenviar-convite-handler repo-admin-sistema deps-registro)]
       :route-name :admin-sistema/reenviar-convite]
      ["/operacao/casas/:ente/realm" :post [auth papel (reprovisionar-realm-handler repo-admin-sistema deps-registro)]
       :route-name :admin-sistema/reprovisionar-realm]
      ["/operacao/atos-sem-desfecho" :get [auth papel (atos-sem-desfecho-handler repo-admin-sistema relogio)]
       :route-name :admin-sistema/atos-sem-desfecho]
      ["/operacao/ia" :get [auth papel (observabilidade-ia-handler observabilidade-ia)]
       :route-name :admin-sistema/observabilidade-ia]}))
