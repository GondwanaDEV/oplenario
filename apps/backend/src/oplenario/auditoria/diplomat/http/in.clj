(ns oplenario.auditoria.diplomat.http.in
  "A borda HTTP da trilha de auditoria (ADR-0017):
  - o INTERCEPTOR global que registra cada ato na corrente da Casa (o host o poe entre os globais, por fora do
    interceptor de erro: ve a resposta FINAL — 403 inclusive — e o ator que a autenticacao resolveu);
  - `GET /auditoria` — a trilha no escopo do papel (auditor: a Casa; admin_ente: os acessos e os proprios atos;
    qualquer pessoa: os proprios);
  - `GET /auditoria/integridade` e `GET /auditoria/exportar.csv` — so' o `auditor`;
  - `GET /portal/casa/:ente/integridade` — os selos do dia, publicos (a ancora visivel da corrente)."
  (:require [oplenario.auditoria.adapters.in.filtro :as in-filtro]
            [oplenario.auditoria.adapters.out.trilha :as out]
            [oplenario.auditoria.controllers :as controllers]
            [oplenario.http :as http]
            [oplenario.interceptors :as it]))

(set! *warn-on-reflection* true)

(defn interceptor
  "O interceptor da trilha. `seams` = {:ancorar!}. Nunca muda a resposta; a chave `:auditoria` que um handler deixa na
  resposta (o resumo do efeito) e' consumida aqui e nao vai para o fio."
  [repo-auditoria seams]
  {:name  ::trilha
   :leave (fn [ctx]
            (let [acao (get-in ctx [:route :route-name])]
              (controllers/registrar-requisicao! repo-auditoria seams (:request ctx) (:response ctx) acao)
              (update ctx :response #(some-> % (dissoc :auditoria)))))})

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
