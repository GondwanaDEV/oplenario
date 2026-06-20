(ns oplenario.motor.runtime
  "Avaliador executável + loop de runtime do motor (§22.7.7): materializa -> avalia ->
  monitora -> audita. Implementa as DUAS decisões centrais do Eixo de runtime:

    - Dois sabores: COM prazo materializa instância (prazo_dominio_ativo) com relógio;
      CONTÍNUA (sem bloco prazo) não materializa — só audita.
    - compliance_avaliacao APPEND-ONLY = a prova de compliance (Invariante 10).

  Ciclo da obrigação = enum FIXO em código: pendente -> cumprida|vencida|dispensada|cancelada.
  Relógio injetado (determinístico, como instante em §22.6). Valores regulatórios são
  FIXTURES ilustrativos: o [GAP] de §22.7.5 segue GAP; aqui só exercita a forma.

  Estado mutável do motor vive num ATOM (read-modify-write single-thread nos testes)."
  (:require [oplenario.motor.catalogo :as cat]
            [oplenario.motor.nucleo :as nuc])
  (:import [java.time LocalDate]))

;; Cache de parse: o sweep reavalia as MESMAS strings de regra muitas vezes e parse é puro.
;; (memo não-limitado — o conjunto de strings de regra é pequeno; em produção isto vira
;;  AST pré-compilada na regra carregada. Fronteira de persistência: chat de stack.)
(def ^:private parse-memo (memoize nuc/parse-expr))

;; ===========================================================================
;; Datas + helpers de calendário
;; ===========================================================================
(defn ldate [y m d] (LocalDate/of (int y) (int m) (int d)))

(defn- util? [^LocalDate d feriados]
  (and (<= (.getValue (.getDayOfWeek d)) 5) (not (contains? feriados d))))

(defn- prox-dia-util [^LocalDate d feriados]
  (loop [x (.plusDays d 1)] (if (util? x feriados) x (recur (.plusDays x 1)))))

(defn- soma-dias-uteis [^LocalDate d n feriados]
  (loop [x d passos 0]
    (if (>= passos n) x
        (let [x2 (.plusDays x 1)] (recur x2 (if (util? x2 feriados) (inc passos) passos))))))

(defn- fim-de [comp]
  (let [{:keys [ano mes]} comp
        prox (if (= mes 12) (ldate (inc ano) 1 1) (ldate ano (inc mes) 1))]
    (.minusDays ^LocalDate prox 1)))

(defn comp-chave [comp] (format "%04d-%02d" (int (:ano comp)) (int (:mes comp))))

;; ===========================================================================
;; Construtores de fixtures (valores de domínio + tabelas-espelho do schema)
;; ===========================================================================
(defn competencia [ano mes] {:ano ano :mes mes})
(defn ente [id populacao membros] {:id id :populacao populacao :membros membros})
(defn ato-despesa [id registro-contabil publicada] {:id id :registro-contabil registro-contabil :publicada publicada})
(defn ato-legislativo [id tipo publicado promulgacao] {:id id :tipo tipo :publicado publicado :promulgacao promulgacao})
(defn votacao [id materia favoraveis] {:id id :materia materia :favoraveis favoraveis})
(defn prazo-vigente* [jurisdicao tipo-prazo chave-periodo data-limite fonte vigente]
  {:jurisdicao jurisdicao :tipo-prazo tipo-prazo :chave-periodo chave-periodo
   :data-limite data-limite :fonte fonte :vigente vigente})
(defn estado [] {:feriados #{} :prazos [] :remessas #{} :bindings {}})

;; ===========================================================================
;; Avaliador de expressão (tree-walk). ctx = {:estado :agora :fonte (atom)}.
;; ===========================================================================
(declare avaliar a-chamada a-binop arredonda-cima prazo-vigente-lookup)

(defn avaliar [no amb ctx]
  (case (:t no)
    :lit (:valor no)
    :ident (let [nm (:nome no)]
             (cond
               (contains? amb nm) (get amb nm)
               (cat/dominio-do-literal nm) nm                 ; literal de enum = seu símbolo
               :else (throw (ex-info (str "identificador sem valor em runtime: " (pr-str nm)) {:erro :runtime}))))
    :campo (get (avaliar (:obj no) amb ctx) (keyword (:campo no)))
    :conjunto-lit (set (map #(avaliar % amb ctx) (:elementos no)))
    :chamada (a-chamada no amb ctx)
    :binop (a-binop no amb ctx)
    :unop (let [v (avaliar (:operando no) amb ctx)]
            (if (= (:op no) "nao") (not v)
                (throw (ex-info (str "operador unário desconhecido: " (:op no)) {:erro :runtime}))))
    (throw (ex-info (str "nó desconhecido: " (pr-str no)) {:erro :runtime}))))

;; Duracao em runtime = mapa-tag {:duracao-dias n} (homoicônico, comparável por valor).
(defn- duracao-val? [x] (and (map? x) (contains? x :duracao-dias)))

(defn- a-mais [a b]
  (cond
    (and (number? a) (number? b)) (+ a b)
    (and (instance? LocalDate a) (duracao-val? b)) (.plusDays ^LocalDate a (long (:duracao-dias b)))
    (and (duracao-val? a) (instance? LocalDate b)) (.plusDays ^LocalDate b (long (:duracao-dias a)))
    (and (duracao-val? a) (duracao-val? b)) {:duracao-dias (+ (:duracao-dias a) (:duracao-dias b))}
    :else (throw (ex-info (str "soma inválida em runtime: " (pr-str a) " + " (pr-str b)) {:erro :runtime}))))

(defn- a-menos [a b]
  (cond
    (and (number? a) (number? b)) (- a b)
    (and (instance? LocalDate a) (duracao-val? b)) (.minusDays ^LocalDate a (long (:duracao-dias b)))
    (and (instance? LocalDate a) (instance? LocalDate b)) {:duracao-dias (- (.toEpochDay ^LocalDate a) (.toEpochDay ^LocalDate b))}
    (and (duracao-val? a) (duracao-val? b)) {:duracao-dias (- (:duracao-dias a) (:duracao-dias b))}
    :else (throw (ex-info (str "subtração inválida em runtime: " (pr-str a) " - " (pr-str b)) {:erro :runtime}))))

(defn- a-binop [no amb ctx]
  (let [op (:op no)]
    (case op
      ;; curto-circuito: o lado direito só é avaliado se necessário — regra VÁLIDA com um
      ;; ramo morto que falharia (ex.: `falso e prazo_vigente(...)`) nunca explode em runtime.
      "e"  (boolean (and (avaliar (:esq no) amb ctx) (avaliar (:dir no) amb ctx)))
      "ou" (boolean (or  (avaliar (:esq no) amb ctx) (avaliar (:dir no) amb ctx)))
      (let [a (avaliar (:esq no) amb ctx)
            b (avaliar (:dir no) amb ctx)]
        (case op
          "in" (contains? b a)
          ">"  (pos? (compare a b))
          ">=" (>= (compare a b) 0)
          "<"  (neg? (compare a b))
          "<=" (<= (compare a b) 0)
          "==" (= a b)
          "!=" (not= a b)
          "*"  (* a b)
          "+"  (a-mais a b)
          "-"  (a-menos a b)
          (throw (ex-info (str "operador binário desconhecido: " op) {:erro :runtime})))))))

(defn- arredonda-cima [x]
  (if (ratio? x)
    (quot (+ (numerator x) (dec (denominator x))) (denominator x))   ; ceil exato p/ ratio positivo
    (long x)))

(defn- prazo-vigente-lookup [ctx jurisdicao tipo comp]
  (let [chave (comp-chave comp)
        achados (filter #(and (= (:jurisdicao %) jurisdicao) (= (:tipo-prazo %) tipo)
                              (= (:chave-periodo %) chave) (:vigente %))
                        (:prazos (:estado ctx)))]
    (when (empty? achados)
      (throw (ex-info (str "prazo_vigente: sem prazo p/ " jurisdicao "/" tipo "/" chave " [GAP de conteúdo]") {:erro :runtime})))
    (reset! (:fonte ctx) (:fonte (first achados)))       ; captura p/ prazo_fonte_ref (S1/S3)
    (:data-limite (first achados))))

(defn- a-chamada [no amb ctx]
  (let [args (mapv #(avaliar % amb ctx) (:args no))
        nome (:nome no)
        st (:estado ctx)
        fer (:feriados st)]
    (case nome
      ("hoje" "agora") (:agora ctx)
      "fim_de" (fim-de (nth args 0))
      "proximo_dia_util" (prox-dia-util (nth args 0) fer)
      "soma_dias_uteis" (soma-dias-uteis (nth args 0) (nth args 1) fer)
      "arredonda_cima" (arredonda-cima (nth args 0))
      "fracao" (/ (nth args 0) (nth args 1))             ; EXATO — ratio Clojure, nunca float
      "dias" {:duracao-dias (nth args 0)}                ; construtor de Duracao
      "prazo_vigente" (prazo-vigente-lookup ctx (nth args 0) (nth args 1) (nth args 2))
      "parametro_tenant" (let [e (get amb "ente")
                               binding (get (:bindings st) (:id e) {})]
                           (if (contains? binding (nth args 0)) (get binding (nth args 0))
                               (throw (ex-info (str "parametro_tenant: " (pr-str (nth args 0)) " não configurado p/ " (:id e)) {:erro :runtime}))))
      "populacao" (:populacao (nth args 0))
      "membros_da_casa" (:membros (nth args 0))
      "remessa_enviada" (let [[e sistema comp] args]
                          (contains? (:remessas st) [(:id e) sistema (comp-chave comp)]))
      "publicada_no_portal" (:publicada (nth args 0))
      "data_registro_contabil" (:registro-contabil (nth args 0))
      "publicado" (:publicado (nth args 0))
      "data_promulgacao" (:promulgacao (nth args 0))
      "votos_favoraveis" (:favoraveis (nth args 0))
      (throw (ex-info (str "função sem implementação de runtime: " (pr-str nome)) {:erro :runtime})))))

(defn eval-expr
  "Conveniência p/ avaliar uma expressão-string solta (usado nos testes de aritmética)."
  ([s] (eval-expr s {} (estado) (ldate 2026 6 19)))
  ([s amb st agora] (avaliar (nuc/parse-expr s) amb {:estado st :agora agora :fonte (atom nil)})))

;; ===========================================================================
;; Motor: o loop materializa -> avalia -> monitora -> audita
;; ===========================================================================
(def ^:private LIMIAR-A-VENCER-DIAS 5)   ; "a vencer" = derivação de LEITURA, não estado persistido

(defn motor [estado agora]
  (atom {:estado estado :agora agora
         :obrigacoes {} :avaliacoes [] :eventos [] :contexto {} :seq 0}))

(defn- next-id! [eng prefixo]
  (str prefixo "-" (:seq (swap! eng update :seq inc))))

(defn- emitir! [eng evt] (swap! eng update :eventos conj evt))

(defn- auditar! [eng ente-id obrig-id template reg-ver veredito severidade origem detalhe]
  (let [id (next-id! eng "aval")
        av {:id id :ente-id ente-id :obrigacao-id obrig-id :template-chave template
            :registry-versao-ref reg-ver :veredito veredito :severidade (or severidade "aviso")
            :occurred-at (:agora @eng) :origem-avaliacao origem :detalhe detalhe}]
    (swap! eng update :avaliacoes conj av)             ; append-only
    (emitir! eng (str "ObrigacaoComplianceAvaliada(" id ", " template ", " veredito ")"))
    av))

(defn avaliar-regra!
  "Um passo do loop para UMA regra contra UM objeto. Idempotente na materialização."
  ([eng regra reg-ver amb objeto-tipo objeto-id]
   (avaliar-regra! eng regra reg-ver amb objeto-tipo objeto-id "evento"))
  ([eng regra reg-ver amb objeto-tipo objeto-id origem]
   (let [st (:estado @eng) agora (:agora @eng)
         e (get amb "ente") ente-id (:id e)
         ctx {:estado st :agora agora :fonte (atom nil)}]
     (if-not (avaliar (parse-memo (:aplica-quando regra)) amb ctx)
       ;; aplica_quando=falso -> inaplicável (não materializa)
       (auditar! eng ente-id nil (:template regra) reg-ver "inaplicavel" (:severidade regra) origem "aplica_quando=falso")
       (let [conforme (boolean (avaliar (parse-memo (:exige regra)) amb ctx))
             veredito (if conforme "conforme" "nao_conforme")
             prazo (:prazo regra)
             tem-prazo (and (map? prazo) (not (contains? prazo :__malformado__)))]
         (if-not tem-prazo
           ;; sabor CONTÍNUO: não materializa instância, só audita
           (auditar! eng ente-id nil (:template regra) reg-ver veredito (:severidade regra) origem "regra contínua (sem prazo)")
           ;; sabor DEADLINE-BOUND: materializa/atualiza obrigação
           (let [_ (reset! (:fonte ctx) nil)
                 vence-em (avaliar (parse-memo (get prazo "janela")) amb ctx)
                 fonte @(:fonte ctx)
                 chave [ente-id (:template regra) objeto-tipo objeto-id]
                 obrig0 (get (:obrigacoes @eng) chave)
                 obrig
                 (if (nil? obrig0)
                   (let [id (next-id! eng "obr")
                         ob {:id id :ente-id ente-id :template-chave (:template regra)
                             :objeto-tipo objeto-tipo :objeto-id objeto-id
                             :vence-em vence-em :prazo-fonte-ref fonte :estado "pendente" :cumprida-em nil}]
                     (swap! eng update :obrigacoes assoc chave ob)
                     (swap! eng update :contexto assoc id [regra amb])
                     (emitir! eng (str "ObrigacaoComplianceMaterializada(" id ", " (:template regra) ", vence=" vence-em ")"))
                     ob)
                   ;; re-stamp do prazo só enquanto ABERTA (pendente). cumprida/vencida não movem (S3).
                   (if (and (= (:estado obrig0) "pendente") (not= (:vence-em obrig0) vence-em))
                     (let [ob (assoc obrig0 :vence-em vence-em :prazo-fonte-ref fonte)]
                       (emitir! eng (str "ObrigacaoComplianceReprazada(" (:id obrig0) ": " (:vence-em obrig0) " -> " vence-em ", fonte=" fonte ")"))
                       (swap! eng update :obrigacoes assoc chave ob)
                       ob)
                     obrig0))
                 ;; transição de estado (enum fixo)
                 obrig2
                 (cond
                   conforme
                   (if (not= (:estado obrig) "cumprida")
                     (let [ob (assoc obrig :estado "cumprida" :cumprida-em agora)]
                       (emitir! eng (str "ObrigacaoComplianceCumprida(" (:id ob) ")"))
                       (swap! eng update :obrigacoes assoc chave ob) ob)
                     obrig)
                   (and (= (:estado obrig) "pendente") (.isAfter ^LocalDate agora ^LocalDate (:vence-em obrig)))
                   (let [ob (assoc obrig :estado "vencida")]
                     (emitir! eng (str "ObrigacaoComplianceVencida(" (:id ob) ")"))
                     (swap! eng update :obrigacoes assoc chave ob) ob)
                   :else obrig)]
             (auditar! eng ente-id (:id obrig2) (:template regra) reg-ver veredito (:severidade regra) origem
                       (str "obrigacao=" (:estado obrig2))))))))))

(defn monitorar
  "Derivação de LEITURA sobre vence_em (não persiste). 'a_vencer' é leitura, não estado."
  [eng]
  (let [agora (:agora @eng)]
    (mapv (fn [o]
            (let [situacao
                  (if (or (not= (:estado o) "pendente") (nil? (:vence-em o)))
                    (:estado o)
                    (let [dias (- (.toEpochDay ^LocalDate (:vence-em o)) (.toEpochDay ^LocalDate agora))]
                      (cond
                        (neg? dias) "vencida(derivada)"
                        (<= dias LIMIAR-A-VENCER-DIAS) (str "a_vencer(" dias "d)")
                        :else (str "no_prazo(" dias "d)"))))]
              {:obrigacao (:id o) :template (:template-chave o) :vence-em (:vence-em o)
               :estado (:estado o) :situacao situacao}))
          (vals (:obrigacoes @eng)))))

(defn aplicar-circular!
  "S3: Ofício Circular desliza o prazo de domínio -> re-sweep das obrigações ABERTAS."
  [eng jurisdicao tipo-prazo chave-periodo nova-data fonte]
  (swap! eng update-in [:estado :prazos]
         (fn [prazos]
           (conj (mapv (fn [p]
                         (if (and (= (:jurisdicao p) jurisdicao) (= (:tipo-prazo p) tipo-prazo)
                                  (= (:chave-periodo p) chave-periodo))
                           (assoc p :vigente false) p))
                       prazos)
                 (prazo-vigente* jurisdicao tipo-prazo chave-periodo nova-data fonte true))))
  (emitir! eng (str "PrazoDominioDeslizado(" jurisdicao "/" tipo-prazo "/" chave-periodo " -> " nova-data ", " fonte ")"))
  ;; re-sweep: reavalia obrigações abertas afetadas (origem=sweep)
  (doseq [[_chave obrig] (:obrigacoes @eng)]
    (when (and (= (:estado obrig) "pendente") (contains? (:contexto @eng) (:id obrig)))
      (let [[regra amb] (get (:contexto @eng) (:id obrig))]
        (avaliar-regra! eng regra cat/CATALOGO-VERSAO amb (:objeto-tipo obrig) (:objeto-id obrig) "sweep")))))
