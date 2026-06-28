(ns oplenario.motor.api
  "Fachada publica do motor de regras (§22.7) — o SEAM in-process que os modulos importam.
  §22.10: 'kernel/motor nunca importam um modulo'; o motor e BIBLIOTECA compartilhada (coracao
  dos 4 usos da DSL — tramitacao, autorizacao, plenario, compliance), nao servico HTTP. O compliance
  OPERA este seam: materializa obrigacao/audita avaliacao nas SUAS tabelas (schema compliance, §22.7.7)."
  (:require [oplenario.motor.components.registro-fatos :as rf]
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

(defn politica-dsl
  "Compila uma expressao DSL de politica (booleana) num predicado `(fn [ator recurso] -> bool)` — o
  seam que `kernel/autorizacao/check!` roda na camada FINA (§22.5 eixo E). disciplina 5: a politica usa
  o MESMO avaliador do motor e o MESMO registry de fatos (resolver-para sobre a tx do tenant). `ator` e
  `recurso` entram no `amb` (acesso a campo: `ator.identidade`, `recurso.autor`); fatos de relacao
  (`é_o_próprio`, `tem_mandato_vigente`, …) resolvem por nome. A politica declarativa MORA no modulo
  dono do recurso (so o mecanismo aqui). Expressao nao-booleana / fato-sem-fn = lanca; o `check!`
  traduz lance -> negacao (quem nao decide, NEGA). Sem prazo/obrigacao: politica e' avaliacao pura.

  `arg-map`: :registro (RegistroFatos) :tx (tx do tenant p/ os fatos) :expr (fonte da expressao DSL)
  :agora (LocalDate/Instant — default de `hoje()`/`agora()`)."
  [{:keys [registro tx expr agora]}]
  (let [no (nuc/parse-expr expr)
        resolver (rf/resolver-para registro tx)]
    (fn [ator recurso]
      (let [amb {"ator" ator "recurso" recurso}
            ctx {:estado (rt/estado) :agora agora :fonte (atom nil)
                 :resolver resolver :ente-id (:ente-id ator)}]
        (boolean (rt/avaliar no amb ctx))))))

;; [SEAMs ainda NAO estabilizados — F5 (Compliance/remessa)]
;; - regras-aplicaveis(repo-motor, ente) : resolve POR ESCOPO juntando motor.template_compliance +
;;   motor.compliance_regra_tenant (mesmo schema, sem cross-schema JOIN, §22.10) — a orquestracao do
;;   sweep/materializacao + persistencia nas tabelas de runtime do compliance (§22.7.7) e operada la.
