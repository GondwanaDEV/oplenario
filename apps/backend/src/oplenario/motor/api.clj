(ns oplenario.motor.api
  "Fachada publica do motor de regras (§22.7) — o SEAM in-process que os modulos importam.
  §22.10: 'kernel/motor nunca importam um modulo'; o motor e BIBLIOTECA compartilhada (coracao
  dos 4 usos da DSL — tramitacao, autorizacao, plenario, compliance), nao servico HTTP. O compliance
  OPERA este seam: materializa obrigacao/audita avaliacao nas SUAS tabelas (schema compliance, §22.7.7)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [oplenario.motor.components.registro-fatos :as rf]
            [oplenario.motor.components.repositorio :as rm]
            [oplenario.motor.nucleo :as nuc]
            [oplenario.motor.runtime :as rt]
            [oplenario.motor.verificador :as v]))

(defn verificar-fonte
  "Type-check do save time (Eixo A dec.2) — REAL/pronto: puro, le o catalogo declarado em codigo,
  sem fato externo. Parseia o envelope + tipa. Devolve {:status VALIDA|INVALIDA :erros :avisos
  :registry-versao-ref}. So VALIDA grava forma_compilada como 'vigente' em motor.template_compliance."
  [fonte-yaml]
  (v/verificar-template (nuc/carregar-envelope fonte-yaml)))

(defn validar-guarda
  "Save-time (Inv.4 / disciplina 5) do GUARD de uma transicao de tramitacao (§22.4 eixo C): tira a falha
  de tramitacao do caminho critico — um regimento com guard mal-escrito e' REJEITADO na config, nunca no
  meio de um fluxo. `fonte` nil/em-branco = guard ausente (sempre passa) = VALIDA. Devolve
  {:status \"VALIDA\"|\"INVALIDA\" :erros [<msg>]}.

  ESCOPO F3.3b = validacao SINTATICA (parseia como expressao DSL — o `guarda-dsl` faria o mesmo parse no
  runtime; antecipa-lo p/ o save move a falha p/ a config). Type-check estatico COMPLETO (a expressao tipa
  p/ Booleano contra o vocabulario de tramitacao — registros `proposicao`/`contexto`) e' [CARRY]: depende
  da catalogacao do eixo C no registry (analogo a §22.7.5 p/ compliance); sem isso o type-checker nao
  conhece esses registros. Ate la, o parse e' a rede; o runtime ainda avalia o tipo ao disparar.

  Aridade-2 acrescenta a ALLOWLIST de vocabulario (a frente `guarda-so-apurado`, 11/09/2026 — um
  canal do `amb` que carrega o corpo bruto de uma requisicao nao pode ser lido dentro de um GUARD,
  pois deixaria o operador afirmar a propria precondicao): `opts` traz
  `{:vocabulario #{<identificador> ...}}`. Este `motor/` NAO conhece o vocabulario de nenhum
  modulo — so' compara os identificadores-RAIZ que `nuc/identificadores-raiz` acha na expressao
  (via `nuc/parse-expr`) contra o conjunto que o CHAMADOR (o modulo dono da coluna) declara.
  Identificador fora do vocabulario = INVALIDA, com `:erros` NOMEANDO o identificador ilegal e
  listando o vocabulario permitido — a mensagem e' a prova, quem cadastra o rito tem de saber o
  que pode escrever. Fonte em branco/nil continua VALIDA (guarda ausente = sempre passa).

  CHAMAR A ARIDADE-2 SEM VOCABULARIO LANCA, nao degrada. Se a omissao caisse de volta em
  'so' sintatico', o gate teria um caminho PERMISSIVO acionado por ESQUECIMENTO — a mesma classe de
  fail-open que esta frente existe p/ fechar, um nivel acima. Quem quer so' a sintaxe chama a
  aridade-1, que declara isso na assinatura. Vocabulario VAZIO (`#{}`) e' diferente de ausente: e'
  a declaracao legitima de 'nada e' permitido aqui' (o caso do template inexistente em
  `criar-transicao!`) e devolve INVALIDA — resposta de dominio, nao excecao de programacao."
  ([fonte]
   (if (str/blank? fonte)
     {:status "VALIDA" :erros []}
     (try
       (nuc/parse-expr fonte)
       {:status "VALIDA" :erros []}
       (catch clojure.lang.ExceptionInfo e
         {:status "INVALIDA" :erros [(ex-message e)]}))))
  ([fonte {:keys [vocabulario] :as opts}]
   (when (nil? vocabulario)
     (throw (ex-info (str "validar-guarda/2 exige :vocabulario (conjunto de identificadores permitidos); "
                          "para validacao so' sintatica use a aridade-1")
                     {:erro :vocabulario-ausente :opts opts})))
   (let [base (validar-guarda fonte)]
     (if (or (not= "VALIDA" (:status base)) (str/blank? fonte))
       base
       (let [no (nuc/parse-expr fonte)
             usados (nuc/identificadores-raiz no)
             ilegais (set/difference usados (set vocabulario))]
         (if (empty? ilegais)
           base
           {:status "INVALIDA"
            :erros [(str "identificador nao permitido no guard: " (str/join ", " (sort ilegais))
                         " — vocabulario permitido: " (str/join ", " (sort vocabulario)))]}))))))

(defn comp-chave
  "Normaliza um valor de Competencia ({:ano :mes}) p/ a chave 'AAAA-MM' — o MESMO formato que o motor usa
  no prazo/competencia. Reexposto na fachada p/ os modulos que precisam casar a chave (ex.: compliance/relacoes
  na costura `remessa_enviada`) sem acoplar a um namespace INTERNO do motor (runtime) — review clojure #4."
  [competencia]
  (rt/comp-chave competencia))

(defn avaliar
  "Seam de avaliacao (F2.3) — avalia UMA regra `vigente` contra fatos REAIS, fora do atom-fixture:
    - FATOS DE DOMINIO (populacao, tribunal_competente, …) → `resolver-para` sobre o RegistroFatos +
      a `tx` do tenant (resolucao por nome; o motor nunca importa o modulo, §22.10).
    - BUILTINS own-schema → RepoMotor (mesma esfera `motor`): prazo_vigente → motor.prazo_dominio_vigente;
      parametro_tenant → motor.compliance_regra_tenant (binding do ente); feriados → motor.calendario_feriado.
  NAO persiste o ciclo: quem OPERA (o compliance) grava obrigacao/avaliacao nas SUAS tabelas (§22.7.7).
  Devolve {:avaliacao <ultima> :obrigacoes [<materializadas>] :eventos [<emitidos>]}.

  `arg-map`: :registro (RegistroFatos started) :repo-motor (RepoMotor started) :tx (tx do tenant p/ os
  fatos) :ente-id :regra (envelope de nucleo/carregar-envelope) :reg-ver :objeto-tipo :objeto-id :amb
  (valores dos parametros do template) :agora (LocalDate — compliance opera em datas; o runtime lanca se
  vier outro tipo) :feriados-jurisdicao (default 'nacional')."
  [{:keys [registro repo-motor tx ente-id regra reg-ver objeto-tipo objeto-id amb agora feriados-jurisdicao]}]
  (let [resolver    (rf/resolver-para registro tx)
        prazo-fonte (fn [dominio chave-dominio tipo periodo]
                      (rm/prazo-vigente repo-motor dominio chave-dominio tipo periodo))
        ;; o binding (ente, template) é invariante DENTRO de uma avaliação — lido UMA vez (não por
        ;; chamada de parametro_tenant: evita split-read se um update do binding cair no meio).
        b-params    (some-> (rm/binding-do-ente repo-motor ente-id (:template regra)) :parametros-tenant)
        ;; param-fonte devolve [val] (achado) | nil (ausente) — distingue valor nil/false de 'nao configurado'
        param-fonte (fn [chave]
                      (when (and b-params (contains? b-params chave)) [(get b-params chave)]))
        feriados    (rm/feriados repo-motor (or feriados-jurisdicao "nacional") nil)
        eng (rt/motor (assoc (rt/estado) :feriados feriados) agora resolver ente-id
                      {:prazo-fonte prazo-fonte :param-fonte param-fonte})]
    (rt/avaliar-regra! eng regra reg-ver amb objeto-tipo objeto-id)
    {:avaliacao  (last (:avaliacoes @eng))
     :obrigacoes (vec (vals (:obrigacoes @eng)))
     :eventos    (:eventos @eng)}))

(defn- exigir-booleano!
  "Fail-closed do TOPO de uma expressao booleana (guard de tramitacao, politica de autorizacao): o valor
  que sai do avaliador tem de ser `true` ou `false`. Qualquer outra coisa LANCA `{:erro :runtime}`.

  POR QUE ISTO EXISTE (achado da revisao adversarial da borda de tramitacao, fatia 4). Ate' aqui os dois
  seams fechavam com `(boolean (rt/avaliar ...))`, e `boolean` NAO checa nada — ele CONVERTE. Toda
  expressao que avaliasse para um valor truthy nao-booleano virava um `true`:

    guard `alegado.parecer`            -> a string 'desfavoravel' vira TRUE  (a materia avanca)
    guard `quem_exerce_presidencia(…)` -> o UUID do presidente vira TRUE     (o guard nunca nega)
    guard `populacao()`                -> 2 700 000 vira TRUE

  Isto e' fail-ABERTO num ponto que so' existe para NEGAR, e as duas docstrings da borda ja' afirmavam o
  contrario ('tipo nao-booleano no runtime … LANCA'). Ou o codigo passava a fazer o que a documentacao
  dizia, ou a documentacao tinha de parar de dizer. Vale a primeira: guard e' AUTORIZACAO, e quem nao
  decide, NEGA — a mesma postura que `criar-transicao!` ja' pratica no save (guard que nao parseia nao
  entra) e que `autorizacao/check!` pratica na politica (lance -> negacao).

  `nil` tambem lanca, e nao e' descuido: `nil` no topo significa que o rito perguntou algo que nao tem
  resposta (campo ausente no mapa, fato que devolveu nada). Converte-lo para `false` em silencio seria
  responder 'a Casa nao permite' sobre uma pergunta que ninguem conseguiu fazer — o mesmo diagnostico
  errado que a fatia 2 desfez ao separar os quatro motivos de recusa. Quem quiser tratar ausencia como
  negacao escreve isso NO RITO (`nao(...)`, `== verdadeiro`), que e' onde a regra mora (Inv.4).

  O `:erro :runtime` da ex-data e' o MESMO que o avaliador ja' usa para fato-sem-fn e identificador-sem-
  valor — e' o que `diplomat/http/in/guard-inavaliavel?` reconhece para responder 500 NOMEADO (o rito da
  Casa nao pode ser avaliado) em vez de 500 opaco, e o que `autorizacao/check!` traduz em negacao.

  ESTA FUNCAO SOZINHA NAO BASTA, e a fatia 5 mediu por que: ela olha o no' de TOPO, e os operadores
  logicos `e`/`ou`/`nao` DEVOLVEM booleano mesmo tendo coagido um operando truthy la' dentro — bastava
  `verdadeiro e alegado.parecer` para a String \"nao\" voltar a valer como autorizacao. A metade que falta
  vive no avaliador, em `runtime/exigir-booleano-operando!`: os operadores logicos EXIGEM operandos
  booleanos em vez de coagi-los. As duas juntas e' que fecham a expressao inteira — esta cobre o topo,
  aquela cobre cada junta interna."
  [v uso expr]
  (if (boolean? v)
    v
    (throw (ex-info (str uso ": a expressao nao avaliou para booleano (fail-closed) — valor de tipo "
                         (if (nil? v) "nil" (.getName (class v))))
                    {:erro :runtime :uso uso :expr expr
                     :tipo-avaliado (if (nil? v) "nil" (.getName (class v)))}))))

(defn- alcancavel-pela-dsl
  "Acrescenta ALIAS com `_` para toda chave kebab-case do mapa, sem remover as originais.

  Por que isto precisa existir (achado CRITICO-2 da revisao de seguranca da 3-A): o acesso a campo do
  motor e' `(keyword (:campo no))` (runtime.clj) e o lexer da DSL nao aceita `-` num identificador — `-` e'
  o operador de SUBTRACAO. Entao `ator.identidade-id` nao e' o campo `:identidade-id`, e' `ator.identidade`
  MENOS `id`. E `ator.identidade_id` procura `:identidade_id`, que nao existe: os mapas de dominio deste
  repo sao kebab-case por convencao Clojure (`:identidade-id`, `:ente-id`, `:vinculo-ativo-id`).

  Resultado, antes deste alias: TODA chave de mais de uma palavra era inalcancavel pela DSL. O ator de
  producao (`identidade/autenticacao.clj`) e' `{:identidade-id :ente-id :tipo-vinculo :vinculo-ativo-id
  :papeis}` — so' `:papeis` dava para usar. E os cinco fatos que a 3-A cita como razao de existir
  (`é_presidente_da_mesa`, `é_secretario_da_mesa`, `quem_exerce_presidencia`, `é_membro_de_comissao`,
  `é_presidente_de_comissao`) exigem IDENTIDADE-ID como 1o argumento: nenhum era escrevivel. A 3-A
  entregava, na pratica, o mesmo eixo de papel estatico do gate grosso que ela existia para superar.

  ADITIVO de proposito: a chave original continua la'. Politica ja' escrita nao muda de comportamento —
  o alias so' torna alcancavel o que antes respondia nil. Combinado com o fail-closed de argumento nil
  (runtime/a-chamada), o campo ERRADO agora NEGA em vez de virar permissao silenciosa."
  [m]
  (if-not (map? m)
    m
    (reduce-kv (fn [acc k v]
                 (let [n (name k)]
                   (cond-> acc
                     (str/includes? n "-")
                     (assoc (keyword (str/replace n "-" "_")) v))))
               m m)))

(defn politica-dsl
  "Compila uma expressao DSL de politica (booleana) num predicado `(fn [ator recurso] -> bool)` — o
  seam que `kernel/autorizacao/check!` roda na camada FINA (§22.5 eixo E). disciplina 5: a politica usa
  o MESMO avaliador do motor e o MESMO registry de fatos (resolver-para sobre a tx do tenant). `ator` e
  `recurso` entram no `amb` (acesso a campo: `ator.identidade`, `recurso.autor`); fatos de relacao
  (`é_o_próprio`, `tem_mandato_vigente`, …) resolvem por nome. A politica declarativa MORA no modulo
  dono do recurso (so o mecanismo aqui). Expressao nao-booleana / fato-sem-fn = lanca — o nao-booleano
  por `exigir-booleano!` logo acima MAIS `runtime/exigir-booleano-operando!` dentro do avaliador, que
  juntos e' que tornam esta frase VERDADEIRA (antes da fatia 4 o seam fechava com `(boolean …)`, que
  converte em vez de checar, e todo truthy virava permissao; ate' a fatia 5 um unico `e`/`ou`/`nao` na
  politica ainda coagia o operando e devolvia a permissao pelo mesmo caminho). O `check!`
  traduz lance -> negacao (quem nao decide, NEGA). Sem prazo/obrigacao: politica e' avaliacao pura.

  `arg-map`: :registro (RegistroFatos) :tx (tx do tenant p/ os fatos) :expr (fonte da expressao DSL)
  :agora (LocalDate/Instant — default de `hoje()`/`agora()`)."
  [{:keys [registro tx expr agora]}]
  (let [no (nuc/parse-expr expr)
        resolver (rf/resolver-para registro tx)]
    (fn [ator recurso]
      (let [amb {"ator" (alcancavel-pela-dsl ator) "recurso" (alcancavel-pela-dsl recurso)}
            ctx {:estado (rt/estado) :agora agora :fonte (atom nil)
                 :resolver resolver :ente-id (:ente-id ator)}]
        (exigir-booleano! (rt/avaliar no amb ctx) "politica" expr)))))

(defn guarda-dsl
  "Compila o GUARD de uma transicao de tramitacao (§22.4 eixo C) num predicado `(fn [amb] -> bool)` — o
  seam que o motor de transicao do legislativo roda. disciplina 5: MESMO avaliador/registry do motor (o
  `amb` carrega o contexto da transicao — ex.: {\"proposicao\" {...} \"contexto\" {...}}; fatos de relacao
  resolvem por nome sobre a tx). Expressao nao-booleana / fato-sem-fn = LANCA `{:erro :runtime}` — o
  nao-booleano por `exigir-booleano!` acima MAIS `runtime/exigir-booleano-operando!` dentro do avaliador,
  que juntos e' que fazem esta frase ser verdadeira: ate' a fatia 4 o seam fechava com `(boolean …)` e um
  guard que avaliasse para string/UUID/numero virava `true`; ate' a fatia 5 escrever esse mesmo guard com
  um `e`/`ou` (`alegado.parecer_favoravel e verdadeiro`) o reabria, porque a coercao acontecia no operando
  e o operador devolvia booleano. Fail-ABERTO exatamente no ponto que so' existe para negar. Quem chama trata o lance: `transicionar!`
  deixa PROPAGAR (nao transicionou) e a borda responde 500 nomeado. Sem prazo/obrigacao: avaliacao pura.

  `arg-map`: :registro (RegistroFatos) :tx (tx do tenant) :expr (fonte da expressao DSL) :ente-id
  :agora (LocalDate/Instant — default de `hoje()`/`agora()`)."
  [{:keys [registro tx expr agora ente-id]}]
  (let [no (nuc/parse-expr expr)
        resolver (rf/resolver-para registro tx)]
    (fn [amb]
      (exigir-booleano!
        (rt/avaliar no amb {:estado (rt/estado) :agora agora :fonte (atom nil)
                            :resolver resolver :ente-id ente-id})
        "guarda" expr))))

;; [SEAMs ainda NAO estabilizados — F5 (Compliance/remessa)]
;; - regras-aplicaveis(repo-motor, ente) : resolve POR ESCOPO juntando motor.template_compliance +
;;   motor.compliance_regra_tenant (mesmo schema, sem cross-schema JOIN, §22.10) — a orquestracao do
;;   sweep/materializacao + persistencia nas tabelas de runtime do compliance (§22.7.7) e operada la.
