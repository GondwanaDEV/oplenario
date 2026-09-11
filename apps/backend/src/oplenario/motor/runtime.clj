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

(defn- data-apos?
  "a > b para DATAS de compliance (vencimento). Compliance opera em LocalDate; fail-LOUD se vier outro
  tipo (o hint ^LocalDate cru gerava ClassCastException silenciosa se um Instant chegasse)."
  [a b]
  (when-not (and (instance? LocalDate a) (instance? LocalDate b))
    (throw (ex-info "comparacao de prazo exige LocalDate (compliance usa datas)"
                    {:a (class a) :b (class b)})))
  (.isAfter ^LocalDate a ^LocalDate b))

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
;; `ente` SAIU como construtor de valor (§4-bis): a Casa é a `tx`/o tenant — não um valor do amb.
;; populacao()/membros_da_casa(data) resolvem pelo :resolver (registry), não por um Registro Ente.
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

(defn- nome-do-tipo [v] (if (nil? v) "nil" (.getName (class v))))

(defn- exigir-booleano-operando!
  "Operando de operador lógico (`e`, `ou`, `nao`): tem de SER booleano — nunca ser coagido a um.

  POR QUE ISTO EXISTE (achado da revisão adversarial da borda de tramitação, fatia 5). `exigir-booleano!`
  (motor/api) já fazia o guard de tramitação e a política de autorização falharem FECHADO quando a
  expressão não avaliava para booleano — mas ele olha o nó de TOPO, e os operadores lógicos DEVOLVEM
  booleano mesmo tendo coagido um operando truthy lá dentro. Com `contexto.x` = a String \"nao\":

    contexto.x                -> lançava (fail-closed correto: o topo é a String)
    falso ou contexto.x       -> devolvia TRUE   (o `ou` fechava com `(boolean (or …))`)
    verdadeiro e contexto.x   -> devolvia TRUE   (o `e` fechava com `(boolean (and …))`)
    nao contexto.x            -> devolvia FALSE  (negação SILENCIOSA de uma pergunta sem resposta)

  Isto é, bastava UM `e`/`ou`/`nao` na expressão para o fail-closed evaporar — e esses operadores são
  exatamente o que aparece em regra de rito real. Os dois seams atingidos são os que só existem para
  NEGAR: `guarda-dsl` (guard de tramitação) e `politica-dsl` (o `policy.check` do F2). Um regimento com
  `alegado.parecer_favoravel e verdadeiro` voltava a aceitar a String \"nao\" como autorização.

  O conserto é de LINGUAGEM, não de seam, e não é regra nova: `verificador/inf-binop` já declara
  \"operador 'e' exige Booleano\" no type-check do save time (§22.7 Eixo A dec. 2) — era o RUNTIME que
  divergia da própria especificação de tipos do núcleo. Alinhá-lo não muda o que o type-checker aceita;
  muda o que o avaliador faz quando não há type-checker, que é justamente o caso do guard de tramitação
  (`validar-guarda` é só sintático — o type-check estático é [CARRY] do eixo C) e da política.

  `nil` também lança, pelo mesmo motivo de `exigir-booleano!`: `nil` significa que o rito perguntou algo
  sem resposta (campo ausente, fato que devolveu nada). Convertê-lo a `false` em silêncio responderia
  \"a Casa não permite\" a uma pergunta que ninguém conseguiu fazer. Quem quiser ausência-como-negação
  escreve isso NO RITO (`== verdadeiro`), que é onde a regra mora (Invariante 4).

  A mensagem nomeia a SUBEXPRESSÃO culpada (via `nucleo/expr->fonte`), não a expressão inteira: sem isso
  quem escreve o rito recebe \"não é booleano\" sobre cinco termos e não sabe onde olhar. `:erro :runtime`
  é a MESMA tag de fato-sem-fn/identificador-sem-valor — o que `guard-inavaliavel?` traduz em 500 nomeado
  e `autorizacao/check!` traduz em negação."
  [v op no]
  (if (boolean? v)
    v
    (throw (ex-info (str "operador '" op "': o operando `" (nuc/expr->fonte no)
                         "` não é booleano (fail-closed) — avaliou para tipo " (nome-do-tipo v))
                    {:erro :runtime :op op
                     :subexpressao (nuc/expr->fonte no)
                     :tipo-avaliado (nome-do-tipo v)}))))

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
    :unop (if (= (:op no) "nao")
            (not (exigir-booleano-operando! (avaliar (:operando no) amb ctx) "nao" (:operando no)))
            (throw (ex-info (str "operador unário desconhecido: " (:op no)) {:erro :runtime})))
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
      ;; `e`/`ou` EXIGEM operandos booleanos (ver `exigir-booleano-operando!`): `boolean`/`and`/`or`
      ;; do Clojure CONVERTEM truthy, e converter aqui furava o fail-closed do guard e da política.
      ;;
      ;; O curto-circuito é preservado: o lado direito só é avaliado — e portanto só é CHECADO — se o
      ;; esquerdo não decidiu sozinho. Uma regra VÁLIDA com ramo morto que falharia (o clássico
      ;; `falso e prazo_vigente(...)`) continua sem explodir; ramo morto é morto, inclusive para tipo.
      "e"  (if (exigir-booleano-operando! (avaliar (:esq no) amb ctx) "e" (:esq no))
             (exigir-booleano-operando! (avaliar (:dir no) amb ctx) "e" (:dir no))
             false)
      "ou" (if (exigir-booleano-operando! (avaliar (:esq no) amb ctx) "ou" (:esq no))
             true
             (exigir-booleano-operando! (avaliar (:dir no) amb ctx) "ou" (:dir no)))
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

(defn- prazo-vigente-lookup
  "Builtin own-schema: a linha 'vigente' p/ (dominio, chave-dominio, tipo, periodo). `chave-dominio` é o
  1º arg do DSL (ex.: \"TCE-CE\"); `dominio` vem do ctx (a regra). Duas fontes: db-backed (`:prazo-fonte`
  injetada pelo seam `motor/avaliar` — lê motor.prazo_dominio_vigente) ou fixture (`:estado :prazos`)."
  [ctx chave-dominio tipo comp]
  (let [chave (comp-chave comp)
        achado (if-let [pf (:prazo-fonte ctx)]
                 (pf (:dominio ctx) chave-dominio tipo chave)                  ; {:data-limite :fonte} | nil
                 (first (filter #(and (= (:jurisdicao %) chave-dominio) (= (:tipo-prazo %) tipo)
                                      (= (:chave-periodo %) chave) (:vigente %))
                                (:prazos (:estado ctx)))))]
    (when (nil? achado)
      (throw (ex-info (str "prazo_vigente: sem prazo p/ " chave-dominio "/" tipo "/" chave " [GAP de conteúdo]") {:erro :runtime})))
    (reset! (:fonte ctx) (:fonte achado))                ; captura p/ prazo_fonte_ref (S1/S3)
    (:data-limite achado)))

(defn- a-chamada [no amb ctx]
  (let [args (mapv #(avaliar % amb ctx) (:args no))
        nome (:nome no)
        st (:estado ctx)
        fer (:feriados st)]
    (case nome
      ;; ---- BUILTINS (in-engine, §2): calendário/aritmética puros, OU leitura do PRÓPRIO schema
      ;;      `motor` (prazo_vigente → motor.prazo_dominio_vigente; parametro_tenant →
      ;;      motor.compliance_regra_tenant). O motor é dono dessas tabelas — não é cross-módulo. ----
      ("hoje" "agora") (:agora ctx)
      "fim_de" (fim-de (nth args 0))
      "proximo_dia_util" (prox-dia-util (nth args 0) fer)
      "soma_dias_uteis" (soma-dias-uteis (nth args 0) (nth args 1) fer)
      "arredonda_cima" (arredonda-cima (nth args 0))
      "fracao" (/ (nth args 0) (nth args 1))             ; EXATO — ratio Clojure, nunca float
      "dias" {:duracao-dias (nth args 0)}                ; construtor de Duracao
      "prazo_vigente" (prazo-vigente-lookup ctx (nth args 0) (nth args 1) (nth args 2))
      "parametro_tenant" (let [chave (nth args 0)
                               ;; db-backed (:param-fonte, lê motor.compliance_regra_tenant) ou fixture
                               achado (if-let [pf (:param-fonte ctx)] (pf chave)
                                          (let [b (get (:bindings st) (:ente-id ctx) {})]
                                            (when (contains? b chave) [(get b chave)])))]
                           (if (some? achado) (first achado)
                               (throw (ex-info (str "parametro_tenant: " (pr-str chave) " não configurado p/ " (:ente-id ctx)) {:erro :runtime}))))
      ;; ---- FATO RESOLVIDO (sai do motor, §2/§3): tudo com forma de DOMÍNIO → o :resolver injetado
      ;;      (RegistroFatos do host). O motor chama por NOME; nunca importa o módulo (§22.10). ----
      ;;
      ;; FAIL-CLOSED EM ARGUMENTO nil (achado CRÍTICO-1 da revisão de segurança da 3-A). Um fato resolvido
      ;; recebe CHAVES DE LOOKUP (identidade-id, comissão-id, data). `nil` ali nunca é uma pergunta legítima
      ;; — é uma chave que não resolveu (campo ausente no `amb`, nome escrito errado, ator parcial). Sem
      ;; esta guarda o `nil` descia ao SQL da relação, não casava linha, e voltava um `false` LIMPO E
      ;; BOOLEANO — que `nao`/`!=` convertem em PERMISSÃO:
      ;;
      ;;     autorizacao = 'nao é_presidente_da_mesa(ator.identidade_id, hoje())'
      ;;
      ;; parseia, passa o gate do save, e autoriza TODO MUNDO — inclusive o próprio presidente. Nenhum
      ;; erro, nenhum log, 200. A expressão é sintaticamente correta e semanticamente plausível: é
      ;; literalmente como se escreve "o relator não pode ser quem preside".
      ;;
      ;; O fail-closed de `exigir-booleano!` (topo) e de `exigir-booleano-operando!` (operandos de e/ou/nao)
      ;; não alcançava isto, porque o valor que volta JÁ É booleano — o defeito está um nível abaixo, no
      ;; ARGUMENTO. Aqui fecha o terceiro e último ponto por onde um valor não-decidido virava decisão.
      (do (when-let [i (first (keep-indexed (fn [i a] (when (nil? a) i)) args))]
            (throw (ex-info (str "fato '" nome "': argumento " (inc i) " é nil (fail-closed) — chave de "
                                 "lookup não resolvida; um fato nunca responde a uma pergunta sem sujeito")
                            {:erro :runtime :fato nome :argumento (inc i) :aridade (count args)})))
          ((:resolver ctx) nome args)))))

;; ===========================================================================
;; Resolvedor de fatos (§3/§4): o seam injetado. `resolver-vazio` = sem fatos (fail-closed, p/ exprs
;; só-builtin); `resolver-fixture` = teste. Em produção é `resolver-para` (motor/components, fecha
;; sobre a `tx` do tenant + o RegistroFatos). a-chamada chama `((:resolver ctx) nome args)`.
;; ===========================================================================
(defn resolver-vazio
  "Resolvedor sem fatos: todo fato de domínio lança (fail-closed). Default das exprs só-builtin."
  [nome _args]
  (throw (ex-info (str "fato sem fn registrada: " (pr-str nome)) {:erro :runtime :nome nome})))

(defn resolver-fixture
  "Resolvedor de teste: mapa {nome → fn-de-args-de-domínio}. Aplica a fn aos args (sem `tx` — fixture).
   Espelha o contrato de `resolver-para` sem tocar banco."
  [m]
  (fn [nome args]
    (if-let [f (get m nome)] (apply f args)
        (throw (ex-info (str "fato sem fn (fixture): " (pr-str nome)) {:erro :runtime :nome nome})))))

(defn eval-expr
  "Conveniência p/ avaliar uma expressão-string solta (usado nos testes de aritmética). Sem fatos de
  domínio (resolver-vazio): exercita builtins/aritmética/temporal."
  ([s] (eval-expr s {} (estado) (ldate 2026 6 19)))
  ([s amb st agora]
   (avaliar (nuc/parse-expr s) amb {:estado st :agora agora :fonte (atom nil) :resolver resolver-vazio :ente-id nil})))

;; ===========================================================================
;; Motor: o loop materializa -> avalia -> monitora -> audita
;; ===========================================================================
(def ^:private LIMIAR-A-VENCER-DIAS 5)   ; "a vencer" = derivação de LEITURA, não estado persistido

(defn motor
  "Engine atom. O resolvedor (registry injetado), o ente-id corrente e — opcional — as fontes db-backed
  dos builtins own-schema (`:prazo-fonte`/`:param-fonte`, injetadas pelo seam `motor/avaliar`) entram
  aqui; `avaliar-regra!` os repassa ao `ctx`. Sem fontes → fixture (`:estado`). 2-arg = só builtins."
  ([estado agora] (motor estado agora resolver-vazio nil nil))
  ([estado agora resolver ente-id] (motor estado agora resolver ente-id nil))
  ([estado agora resolver ente-id {:keys [prazo-fonte param-fonte]}]
   (atom {:estado estado :agora agora :resolver resolver :ente-id ente-id
          :prazo-fonte prazo-fonte :param-fonte param-fonte
          :obrigacoes {} :avaliacoes [] :eventos [] :contexto {} :seq 0})))

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
         ente-id (:ente-id @eng)                          ; a Casa = o tenant (não vem do amb)
         ctx {:estado st :agora agora :fonte (atom nil) :resolver (:resolver @eng) :ente-id ente-id
              :prazo-fonte (:prazo-fonte @eng) :param-fonte (:param-fonte @eng)
              :dominio (:dominio regra)}]               ; domínio da regra → resolução db do prazo_vigente
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
                   (and (= (:estado obrig) "pendente") (data-apos? agora (:vence-em obrig)))
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
  ;; re-sweep: reavalia obrigações abertas afetadas (origem=sweep). Um snapshot do engine p/ o lote
  ;; (single-thread; o snapshot evita reler :contexto a cada iteração e é seguro se virar concorrente).
  (let [snap @eng]
    (doseq [[_chave obrig] (:obrigacoes snap)]
      (when (and (= (:estado obrig) "pendente") (contains? (:contexto snap) (:id obrig)))
        (let [[regra amb] (get (:contexto snap) (:id obrig))]
          (avaliar-regra! eng regra cat/CATALOGO-VERSAO amb (:objeto-tipo obrig) (:objeto-id obrig) "sweep"))))))
