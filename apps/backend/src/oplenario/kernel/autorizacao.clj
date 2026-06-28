(ns oplenario.kernel.autorizacao
  "Mecanica de policy.check (§22.5 eixo E, disciplinas 3/6/8). Defesa em profundidade TWO-LAYER:
  camada GROSSA (esfera + papel estatico — independe do recurso, roda no middleware) e camada FINA
  (policy.check com o recurso ja carregado, roda na operacao de dominio). A POLITICA declarativa mora
  no modulo DONO do recurso; aqui so o MECANISMO. Em F2 a `politica` vira expressao da DSL avaliada
  pelo MESMO avaliador do motor (disciplina 5); aqui ela e' uma fn (ator recurso -> bool) — o seam
  estavel. Kernel — nao importa modulo.")

(set! *warn-on-reflection* true)

(def ator-sistema
  "Ator explicito p/ jobs/workers (§22.5: operacoes sem ator sao PROIBIDAS em codigo de produto —
  jobs usam este ator-sistema, nunca ausencia de ator)."
  {:identidade-id :sistema :papeis #{:sistema} :ente-id nil :sistema? true})

(defn negar!
  "Lanca a negacao de autorizacao. O interceptor/controller traduz p/ 403 (write + deny vao ao audit, F7).
  A aridade-3 preserva a `causa` original (ex-info 3o arg) -> getCause()/stack p/ diagnostico em prod."
  ([razao info] (negar! razao info nil))
  ([razao info causa]
   (throw (ex-info "autorizacao negada" (merge {:tipo :autorizacao/negado :razao razao} info) causa))))

(defn negado?
  "True se `e` e' uma negacao de autorizacao (p/ o interceptor mapear -> 403)."
  [e]
  (= :autorizacao/negado (:tipo (ex-data e))))

;; --------------------------------------------------------------------------
;; Camada GROSSA (middleware) — independe do recurso
;; --------------------------------------------------------------------------
(defn checar-esfera!
  "Op `:tenant` exige ator COM ente-id; op `:supratenant` exige ator SEM ente-id (operador SaaS).
  Cross-esfera = negado (§22.10, sem fail-open silencioso). Devolve o ator se ok."
  [ator esfera]
  (when (nil? ator) (negar! :ator-ausente {:esfera esfera}))
  (case esfera
    :tenant      (when-not (:ente-id ator) (negar! :exige-escopo-tenant {:ator (:identidade-id ator)}))
    :supratenant (when (:ente-id ator)     (negar! :exige-escopo-supratenant {:ator (:identidade-id ator)}))
    (negar! :esfera-desconhecida {:esfera esfera}))
  ator)

(defn tem-papel?
  "O ator tem o papel estatico (do snapshot do token, §22.5 eixo D)?"
  [ator papel]
  (contains? (:papeis ator) papel))

(defn exige-papel!
  "Camada grossa: o papel estatico do snapshot autoriza a CATEGORIA da acao. Devolve o ator se ok."
  [ator papel]
  (when (nil? ator) (negar! :ator-ausente {:papel papel}))
  (when-not (tem-papel? ator papel)
    (negar! :papel-insuficiente {:papel papel :ator (:identidade-id ator)}))
  ator)

;; --------------------------------------------------------------------------
;; Camada FINA (in-domain) — policy.check com o recurso carregado
;; --------------------------------------------------------------------------
(defn check!
  "Camada fina: avalia `politica` (fn [ator recurso] -> bool) com o RECURSO ja carregado. Permite
  (devolve true) ou nega. Politica que LANCA = negacao (quem nao consegue decidir NEGA, nunca fica
  indeterminada -> 500). Em F2 a `politica` vira expressao da DSL avaliada pelo motor (disciplina 5)."
  [ator acao recurso politica]
  (when (nil? ator) (negar! :ator-ausente {:acao acao}))
  (let [permitido? (try (politica ator recurso)
                        (catch Exception ex
                          (negar! :politica-erro {:acao acao :recurso-tipo (:tipo recurso)
                                                  :recurso-id (:id recurso) :causa-msg (ex-message ex)
                                                  :causa-data (ex-data ex)} ex)))]
    (if permitido?
      true
      (negar! :politica {:acao acao :recurso-tipo (:tipo recurso) :recurso-id (:id recurso)
                         :ator (:identidade-id ator)}))))
