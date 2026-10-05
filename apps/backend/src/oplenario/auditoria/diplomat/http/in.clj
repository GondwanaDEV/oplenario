(ns oplenario.auditoria.diplomat.http.in
  "A borda HTTP da trilha de auditoria (ADR-0017):
  - o INTERCEPTOR global que registra cada ato na corrente da Casa (o host o poe entre os globais, por fora do
    interceptor de erro: ve a resposta FINAL — 403 inclusive — e o ator que a autenticacao resolveu);
  - `GET /auditoria` — a trilha no escopo do papel (auditor: a Casa; admin_ente: os acessos e os proprios atos;
    qualquer pessoa: os proprios);
  - `GET /auditoria/integridade` e `GET /auditoria/exportar.csv` — so' o `auditor`;
  - `GET /portal/casa/:ente/integridade` — os selos do dia, publicos (a ancora visivel da corrente)."
  (:require [clojure.tools.logging :as log]
            [io.pedestal.interceptor.chain :as chain]
            [oplenario.auditoria.adapters.in.filtro :as in-filtro]
            [oplenario.auditoria.adapters.out.trilha :as out]
            [oplenario.auditoria.controllers :as controllers]
            [oplenario.auditoria.logic :as logic]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]))

(set! *warn-on-reflection* true)

(defn interceptor
  "O interceptor GLOBAL da trilha. `seams` = {:ancorar!}. Nunca muda a resposta; a chave `:auditoria` que um handler
  deixa na resposta (o resumo do efeito) e' consumida aqui e nao vai para o fio.

  No :enter ele so' deixa no contexto COMO gravar a tentativa: os globais rodam antes do router e da autenticacao, e a
  tentativa precisa da rota e do ator — quem a grava e' `tentativa`, que `com-tentativa` poe logo antes do handler de
  toda rota de escrita. No :leave grava o desfecho, apontando a tentativa quando houve.

  A ENTRADA (o mint da sessao) e' a excecao em que o ator so' existe DENTRO do handler: `tentativa` entrega ao handler
  uma funcao que grava a tentativa quando ele ja' sabe quem entra, e o :leave a encontra em `::entrada` — inclusive
  para fechar com desfecho `falhou` quando o handler lanca depois dela (adendo de 05/10/2026)."
  [repo-auditoria seams]
  {:name  ::trilha
   :enter (fn [ctx]
            (assoc ctx
                   ::exigir-tentativa? (true? (:exigir-tentativa? seams))
                   ::registrar-tentativa!
                   (fn [req acao] (controllers/registrar-tentativa! repo-auditoria seams req acao))
                   ::registrar-tentativa-de-entrada!
                   (fn [req acao ator]
                     (controllers/registrar-tentativa-de-entrada! repo-auditoria seams req acao ator))))
   :leave (fn [ctx]
            (let [acao    (get-in ctx [:route :route-name])
                  entrada (some-> (::entrada ctx) deref)
                  ;; a entrada que lancou depois da tentativa nao tem marca na resposta: sem ela o desfecho `falhou`
                  ;; nao saberia o ator nem a classe, e a tentativa ficaria solta
                  resp    (cond-> (:response ctx)
                            entrada (update :auditoria #(merge {:classe "entrada" :ator (:ator entrada)} %)))]
              (controllers/registrar-requisicao! repo-auditoria seams (:request ctx) resp acao
                                                 (or (::tentativa ctx) (:tentativa entrada)))
              (update ctx :response #(some-> % (dissoc :auditoria)))))})

(defn- tentativa-de-entrada
  "Poe no request a funcao `:tentativa-da-entrada!` (ator -> nil) que o handler do mint chama quando ja' resolveu quem
  entra e ANTES de criar a sessao, e guarda em `::entrada` o que ela gravou. A funcao NUNCA lanca e NUNCA recusa a
  entrada, com ou sem `:exigir-tentativa?`: se a trilha nao grava, vai para o `log/error` e a sessao abre — trancar o
  login quando a trilha cai trancaria tambem quem vai consertar (ADR-0017, adendo de 05/10/2026)."
  [ctx acao]
  (let [entrada (atom nil)
        req     (:request ctx)
        grava!  (::registrar-tentativa-de-entrada! ctx)]
    (-> ctx
        (assoc ::entrada entrada)
        (assoc-in [:request :tentativa-da-entrada!]
                  (fn [ator]
                    (try
                      (when-let [n (grava! req acao ator)]
                        (reset! entrada {:ator ator :tentativa n}))
                      nil
                      (catch Exception e
                        (log/error e "auditoria: tentativa de ENTRADA nao gravada; a entrada SEGUE (a trilha nunca a tranca)"
                                   {:acao acao :ente-id (:ente-id ator)})
                        nil)))))))

(def tentativa
  "O interceptor da TENTATIVA (ADR-0017, adendo de 04/10/2026): logo antes do handler de uma escrita — depois da
  autenticacao, da authz da rota e da validacao da borda —, grava na corrente que o ato VAI comecar, em transacao
  propria ja' commitada. Se o processo cair ou o desfecho nao for gravado, a tentativa fica na corrente sem desfecho, e
  a leitura e a conferencia a acusam: o ato nao some.

  Se a tentativa NAO puder ser gravada (a trilha esta' fora):
  - PADRAO: `log/error` com a rota e a Casa, e o handler RODA — a trilha fora nao para a Casa (a sessao ao vivo nao
    pode cair porque a auditoria caiu). O ato so' fica fora da trilha se a propria trilha estiver fora, e isso fica no
    log.
  - com `:exigir-tentativa?` (AUDITORIA_EXIGIR_TENTATIVA=true): o pedido e' recusado com 503 e o handler nao roda.
  A ENTRADA (`logic/acoes-de-entrada`) e' a excecao: o ator so' existe dentro do handler, que grava a tentativa por
  `:tentativa-da-entrada!`, e a trilha fora NUNCA a recusa — nem com a exigencia ligada (ver `tentativa-de-entrada`).
  Sem o interceptor global da trilha (testes de borda de um modulo so'), nao faz nada."
  {:name  ::tentativa
   :enter (fn [ctx]
            (let [acao (get-in ctx [:route :route-name])]
              (cond
                (nil? (::registrar-tentativa! ctx)) ctx
                ;; a entrada nao tem ator antes do handler: ele grava a tentativa (ver `tentativa-de-entrada`)
                (logic/acoes-de-entrada acao) (tentativa-de-entrada ctx acao)
                :else
                (try
                  (if-let [n ((::registrar-tentativa! ctx) (:request ctx) acao)]
                    (assoc ctx ::tentativa n)
                    ctx)
                  (catch Exception e
                    (let [onde {:acao acao :ente-id (get-in ctx [:request :ator :ente-id])}]
                      (if (::exigir-tentativa? ctx)
                        (do (log/error e "auditoria: tentativa NAO gravada; o pedido foi RECUSADO" onde)
                            (chain/terminate
                             (assoc ctx :response
                                    (http/json-resposta
                                     503 {:erro "o registro de auditoria esta indisponivel; nada foi feito"}))))
                        (do (log/error e "auditoria: tentativa NAO gravada; o ato SEGUE sem rastro previo na trilha" onde)
                            ctx))))))))})

(defn com-tentativa
  "Poe `tentativa` logo antes do handler de TODA rota de escrita (table syntax). Feito no HOST sobre as rotas montadas,
  nao rota a rota: a escrita nova nasce com a tentativa (o teste estrutural confere a tabela inteira)."
  [rotas]
  (into #{}
        (map (fn [[caminho metodo cadeia & resto :as r]]
               (if (logic/escrita? metodo)
                 (let [v (if (vector? cadeia) cadeia [cadeia])]
                   (into [caminho metodo (conj (pop v) tentativa (peek v))] resto))
                 r)))
        rotas))

(def limite-da-pagina 50)

(defn- trilha-handler [repo seams]
  (fn [req]
    (let [ator   (:ator req)
          filtro (in-filtro/query->filtro (:query-params req))
          r      (controllers/trilha repo seams ator filtro limite-da-pagina)]
      (cond-> (http/json-resposta 200 (out/trilha->wire r))
        ;; ler a trilha de OUTRAS pessoas e' leitura sensivel (§22.5 Eixo E); a propria, nao
        (not= "propria" (:escopo r)) (assoc :auditoria {:classe "leitura_sensivel" :rotulo (str "trilha: " (:escopo r))})))))

(defn- integridade-handler [repo]
  (fn [req]
    (http/json-resposta 200 (out/integridade->wire (controllers/integridade repo (:ente-id (:ator req)))))))

(defn- exportar-handler [repo seams]
  (fn [req]
    (let [filtro (in-filtro/query->filtro (:query-params req))
          regs   (controllers/exportar repo seams (:ator req) filtro)]
      {:status 200
       :headers {"Content-Type" "text/csv; charset=utf-8"
                 "Content-Disposition" "attachment; filename=\"trilha-de-auditoria.csv\""}
       :body (out/->csv regs)
       :auditoria {:rotulo (str "exportou " (count regs) " registro(s)")}})))

(defn- selos-publicos-handler [repo resolver-ente casa-existe?]
  (fn [req]
    (let [ente (resolver-ente (get-in req [:path-params :ente]))]
      (if-not (casa-existe? ente)
        (http/json-resposta 404 {:erro "ente nao encontrado"})
        (http/json-resposta 200 (out/selos-publicos->wire (controllers/selos-publicos repo ente)))))))

(defn rotas
  "`seams` = {:nome-de :atuacao-da-operacao}; `resolver-ente-publico`/`casa-existe?` = os seams do portal."
  [{:keys [auth repo-auditoria seams resolver-ente-publico casa-existe?]}]
  (let [auditor (it/exige-papel "auditor")]
    #{["/auditoria" :get [auth (trilha-handler repo-auditoria seams)] :route-name :auditoria/trilha]
      ["/auditoria/integridade" :get [auth auditor (integridade-handler repo-auditoria)]
       :route-name :auditoria/integridade]
      ["/auditoria/exportar.csv" :get [auth auditor (exportar-handler repo-auditoria seams)]
       :route-name :auditoria/exportar]
      ["/portal/casa/:ente/integridade" :get [(selos-publicos-handler repo-auditoria resolver-ente-publico casa-existe?)]
       :route-name :auditoria/selos-publicos]}))
