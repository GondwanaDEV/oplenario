(ns oplenario.motor.motor-test
  "Suíte de aceitação do port Clojure. É o PROPÓSITO do protótipo: os 4 templates do
  Eixo C SÃO os casos de aceitação; os negativos provam o save-time type-check; o loop
  de runtime valida §22.7.7 end-to-end. Paridade com motor-dsl/test_motor.py."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [oplenario.motor.nucleo :as nuc]
            [oplenario.motor.verificador :as v]
            [oplenario.motor.catalogo :as cat]
            [oplenario.motor.templates :as tpl]
            [oplenario.motor.runtime :as rt]))

(defn- estado-t1 []
  (update (rt/estado) :prazos conj
          (rt/prazo-vigente* "TCE-CE" "SIM_mensal" "2026-05" (rt/ldate 2026 6 30) "IN 04/2019" true)))

(def ^:private chave-cmf ["cmf" "remessa_mensal_sim" "competencia" "2026-05"])

;; ---------------------------------------------------------------------------
(deftest typecheck-canonicos
  ;; T1/T2/T3: VALIDA + carimba registry
  (doseq [chave ["T1" "T2" "T3"]]
    (let [r (v/verificar-template (nuc/carregar-envelope (get tpl/CANONICOS chave)))]
      (is (= "VALIDA" (:status r)) (str chave " erros=" (:erros r)))
      (is (= cat/CATALOGO-VERSAO (:registry-versao-ref r)) (str chave " carimba registry"))))
  ;; T4: núcleo tipa, envelope de compliance NÃO cabe (S4)
  (let [r4 (v/verificar-template (nuc/carregar-envelope (get tpl/CANONICOS "T4")))]
    (is (true? (:expressao-ok r4)) "T4 expressao-ok (núcleo reaproveitável)")
    (is (= "INVALIDA" (:status r4)) "T4 status INVALIDA (envelope errado)")
    (is (nil? (:registry-versao-ref r4)) "T4 não carimba (não persiste como vigente)")))

(deftest typecheck-negativos
  (doseq [[chave frag] {"N1" "arg" "N2" "desconhecida" "N3" "esperava Booleano"
                        "N4" "comparação" "N5" "chave desconhecida"}]
    (let [r (v/verificar-template (nuc/carregar-envelope (get tpl/NEGATIVOS chave)))]
      (is (= "INVALIDA" (:status r)) (str chave " INVALIDA"))
      (is (some #(str/includes? % frag) (:erros r))
          (str chave " erro pertinente (" frag ") erros=" (:erros r))))))

(deftest aritmetica-exata
  ;; 2/3 de 7 = 4.666… → ceil = 5. Truncar float daria 4 (errado).
  (is (= 5 (rt/eval-expr "arredonda_cima( fracao(2,3) * 7 )")) "ceil(2/3·7) == 5 (exato)")
  (is (= 4 (long (* (/ 2.0 3) 7))) "truncar float daria 4 (bug evitado)")
  (is (= 6 (rt/eval-expr "fracao(2,3) * 9")) "2/3·9 == 6 exato (ratio)"))

(deftest runtime-deadline-bound
  (let [eng (rt/motor (estado-t1) (rt/ldate 2026 6 19))
        amb {"ente" (rt/ente "cmf" 2700000 43) "competencia" (rt/competencia 2026 5)}
        regra (nuc/carregar-envelope tpl/T1)
        av (rt/avaliar-regra! eng regra cat/CATALOGO-VERSAO amb "competencia" "2026-05")
        obrig (get (:obrigacoes @eng) chave-cmf)]
    (is (= "nao_conforme" (:veredito av)) "remessa não enviada → nao_conforme")
    (is (= "pendente" (:estado obrig)) "obrigação materializada pendente")
    (is (= (rt/ldate 2026 6 30) (:vence-em obrig)) "vence_em = prazo vigente")
    (is (= "IN 04/2019" (:prazo-fonte-ref obrig)) "prazo_fonte_ref carimbado")
    ;; envia a remessa e reavalia (mesmo objeto → idempotente)
    (swap! eng update-in [:estado :remessas] conj ["cmf" "SIM" "2026-05"])
    (let [av2 (rt/avaliar-regra! eng regra cat/CATALOGO-VERSAO amb "competencia" "2026-05")
          obrig2 (get (:obrigacoes @eng) chave-cmf)]
      (is (= "conforme" (:veredito av2)) "após envio → conforme")
      (is (= "cumprida" (:estado obrig2)) "obrigação vira cumprida")
      (is (= (rt/ldate 2026 6 19) (:cumprida-em obrig2)) "cumprida_em carimbado")
      (is (= 2 (count (:avaliacoes @eng))) "auditoria append-only cresce")
      (is (= 1 (count (:obrigacoes @eng))) "não duplicou obrigação (idempotência)"))))

(deftest runtime-continua
  (let [r (v/verificar-template (nuc/carregar-envelope tpl/CONTINUA))]
    (is (= "VALIDA" (:status r)) (str "regra contínua é VALIDA erros=" (:erros r))))
  (let [eng (rt/motor (rt/estado) (rt/ldate 2026 6 19))
        despesa (rt/ato-despesa "d1" (rt/ldate 2026 6 10) false)
        av (rt/avaliar-regra! eng (nuc/carregar-envelope tpl/CONTINUA) cat/CATALOGO-VERSAO
                              {"ente" (rt/ente "cmf" 2700000 43) "despesa" despesa} "despesa" "d1")]
    (is (= 0 (count (:obrigacoes @eng))) "contínua NÃO materializa obrigação")
    (is (nil? (:obrigacao-id av)) "contínua gera avaliação sem obrigacao_id")
    (is (= "nao_conforme" (:veredito av)) "despesa não publicada → nao_conforme")))

(deftest runtime-aplica-quando
  (let [eng (rt/motor (rt/estado) (rt/ldate 2026 6 19))
        despesa (rt/ato-despesa "d9" (rt/ldate 2026 6 10) false)
        av (rt/avaliar-regra! eng (nuc/carregar-envelope tpl/T2) cat/CATALOGO-VERSAO
                              {"ente" (rt/ente "vila_pequena" 8000 9) "despesa" despesa} "despesa" "d9")]
    (is (= "inaplicavel" (:veredito av)) "população ≤10k → inaplicavel")
    (is (= 0 (count (:obrigacoes @eng))) "inaplicável não materializa obrigação")))

(deftest s3-restamp-circular
  (let [eng (rt/motor (estado-t1) (rt/ldate 2026 6 19))
        amb {"ente" (rt/ente "cmf" 2700000 43) "competencia" (rt/competencia 2026 5)}
        regra (nuc/carregar-envelope tpl/T1)]
    (rt/avaliar-regra! eng regra cat/CATALOGO-VERSAO amb "competencia" "2026-05")
    (let [n-antes (count (:avaliacoes @eng))]
      (rt/aplicar-circular! eng "TCE-CE" "SIM_mensal" "2026-05" (rt/ldate 2026 7 15) "OC 16/2026")
      (let [obrig (get (:obrigacoes @eng) chave-cmf)]
        (is (= (rt/ldate 2026 7 15) (:vence-em obrig)) "pendente re-carimbada p/ nova data")
        (is (= "OC 16/2026" (:prazo-fonte-ref obrig)) "nova fonte de prazo registrada")
        (is (some #(str/includes? % "Reprazada") (:eventos @eng)) "evento de reprazo emitido")
        (is (some #(= "sweep" (:origem-avaliacao %)) (:avaliacoes @eng)) "re-sweep gerou avaliação (origem=sweep)")
        (is (> (count (:avaliacoes @eng)) n-antes) "auditoria só cresce (append-only)")))))

(deftest s3-cumprida-nao-move
  (let [est (update (estado-t1) :remessas conj ["cmf" "SIM" "2026-05"])   ; já enviada → vai cumprir
        eng (rt/motor est (rt/ldate 2026 6 19))
        amb {"ente" (rt/ente "cmf" 2700000 43) "competencia" (rt/competencia 2026 5)}]
    (rt/avaliar-regra! eng (nuc/carregar-envelope tpl/T1) cat/CATALOGO-VERSAO amb "competencia" "2026-05")
    (let [obrig (get (:obrigacoes @eng) chave-cmf)
          venc-antes (:vence-em obrig)]
      (is (= "cumprida" (:estado obrig)) "obrigação está cumprida")
      (rt/aplicar-circular! eng "TCE-CE" "SIM_mensal" "2026-05" (rt/ldate 2026 7 15) "OC 16/2026")
      (let [obrig2 (get (:obrigacoes @eng) chave-cmf)]
        (is (= venc-antes (:vence-em obrig2)) "cumprida mantém vence_em (não move)")))))

;; ===========================================================================
;; Endurecimento (auditoria ecc:clojure-reviewer) — semântica temporal, curto-circuito,
;; bordas do parser, e bloco prazo vazio. Travam o comportamento, não só o exercitam.
;; ===========================================================================
(def ^:private TEMPORAL-OK "
template: temporal_data_mais_duracao
contexto: compliance
dominio: regimento_tenant
parametros: { ato: AtoLegislativo }
aplica_quando: verdadeiro
exige: publicado(ato)
prazo:
  janela: data_promulgacao(ato) + dias(5)
  a_partir_de: data_promulgacao(ato)
severidade: aviso
referencia_normativa: \"teste de aritmética temporal\"
")

(def ^:private TEMPORAL-MIX "
template: temporal_mistura_invalida
contexto: compliance
dominio: federal
parametros: { despesa: AtoDespesa }
aplica_quando: agora() < data_registro_contabil(despesa)
exige: publicada_no_portal(despesa)
severidade: aviso
referencia_normativa: \"n/a\"
")

(def ^:private PRAZO-VAZIO "
template: prazo_bloco_vazio
contexto: compliance
dominio: federal
parametros: { despesa: AtoDespesa }
aplica_quando: verdadeiro
exige: publicada_no_portal(despesa)
prazo:
severidade: aviso
referencia_normativa: \"n/a\"
")

(deftest temporal-semantica
  ;; tipo: Data + Duracao → Data (janela aceita Data) ⇒ VALIDA
  (let [r (v/verificar-template (nuc/carregar-envelope TEMPORAL-OK))]
    (is (= "VALIDA" (:status r)) (str "Data + Duracao tipa erros=" (:erros r))))
  ;; tipo: Instante < Data é mistura temporal inválida ⇒ INVALIDA (antes passava)
  (let [r (v/verificar-template (nuc/carregar-envelope TEMPORAL-MIX))]
    (is (= "INVALIDA" (:status r)) "Instante < Data rejeitado")
    (is (some #(str/includes? % "mesmo tipo temporal") (:erros r))
        (str "erro de mistura temporal erros=" (:erros r))))
  ;; runtime: aritmética real de datas/durações
  (is (= {:duracao-dias 5} (rt/eval-expr "dias(5)")) "dias(5) constrói Duracao")
  (is (= (rt/ldate 2026 6 24) (rt/eval-expr "hoje() + dias(5)")) "Data + Duracao desloca os dias")
  (is (= {:duracao-dias 11}
         (rt/eval-expr "fim_de(c) - hoje()" {"c" (rt/competencia 2026 6)} (rt/estado) (rt/ldate 2026 6 19)))
      "Data - Data → Duracao (30-19 = 11 dias)"))

(deftest curto-circuito
  (let [amb {"c" (rt/competencia 2026 6)} est (rt/estado) ag (rt/ldate 2026 6 19)]
    ;; lado direito (prazo inexistente, explodiria) NÃO é avaliado quando o esquerdo já decide
    (is (false? (rt/eval-expr "falso e prazo_vigente(\"X\",\"Y\",c) > hoje()" amb est ag))
        "`e` com esquerdo falso não toca o ramo morto")
    (is (true? (rt/eval-expr "verdadeiro ou prazo_vigente(\"X\",\"Y\",c) > hoje()" amb est ag))
        "`ou` com esquerdo verdadeiro não toca o ramo morto")
    ;; prova de que o ramo direito DE FATO explodiria se avaliado (esquerdo verdadeiro força)
    (is (thrown? clojure.lang.ExceptionInfo
                 (rt/eval-expr "verdadeiro e prazo_vigente(\"X\",\"Y\",c) > hoje()" amb est ag))
        "esquerdo verdadeiro → ramo direito avaliado → erro de prazo ausente propaga")))

(deftest parser-bordas
  ;; cada um destes deve LANÇAR erro de sintaxe limpo — nunca passar, nunca NPE
  (doseq [src ["" "1 + 2 3" "(1 + 2" "1 & 2" "e e e" "{1, 2" "\"abc"]]
    (is (thrown? clojure.lang.ExceptionInfo (nuc/parse-expr src))
        (str "parse deve falhar em: " (pr-str src))))
  ;; loader: chave vazia no envelope é rejeitada, não silenciada
  (is (thrown? clojure.lang.ExceptionInfo (nuc/carregar-envelope "contexto: compliance\n: orfa"))
      "chave vazia (`: valor`) no envelope é erro de sintaxe"))

(deftest prazo-vazio-malformado
  ;; bloco `prazo:` declarado mas vazio ≠ ausência de prazo: INVÁLIDO, não "contínua" silenciosa
  (let [r (v/verificar-template (nuc/carregar-envelope PRAZO-VAZIO))]
    (is (= "INVALIDA" (:status r)) "prazo: vazio é malformado, não contínua")
    (is (some #(str/includes? % "malformado") (:erros r))
        (str "erro de prazo malformado erros=" (:erros r)))))
