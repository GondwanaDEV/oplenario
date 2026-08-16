(ns oplenario.codegen.gerar-sessoes-test
  "Unit: o manifesto do codegen do modulo SESSOES (fatia 1b-WIRE, primeira emissao — espelha
  gerar-paineis-test/gerar-portal-test)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.set :as set]
            [clojure.string :as str]
            [oplenario.codegen.gerar-sessoes :as gerar-sessoes]
            [oplenario.sessoes.wire.out :as wire-out]))

(deftest gerar-tudo-emite-todas-as-interfaces-com-banner
  (let [out (gerar-sessoes/gerar-tudo)]
    (is (str/starts-with? out "// GERADO") "banner de 'nao editar a mao'")
    (is (every? #(str/includes? out (str "export interface " % " {"))
                ["SessaoOut" "PautaOut" "SegmentosOut" "PresencaResumoOut"
                 "LinhaChamadaOut" "ChamadaQuorumOut" "ChamadaOut"])
        "as interfaces do manifesto presentes, inclusive as da chamada")
    (is (not (re-find #": unknown;" out)) "nenhum campo caiu no fallback bare 'unknown'")))

(deftest chamada-referencia-linha-e-quorum-por-nome
  ;; LinhaChamadaOut/ChamadaQuorumOut vem ANTES de ChamadaOut no manifesto p/ a igualdade estrutural casar
  ;; nos campos aninhados (mesmo racional de PautaItemOut/PautaOut).
  (let [out (gerar-sessoes/gerar-tudo)]
    (is (str/includes? out "linhas: LinhaChamadaOut[];")
        "referencia nomeada, nao Record<string, unknown> inlinado")
    (is (str/includes? out "quorum: ChamadaQuorumOut;")
        "referencia nomeada, nao Record<string, unknown> inlinado")))

(deftest linha-chamada-tem-os-campos-nulaveis-certos
  (let [out (gerar-sessoes/gerar-tudo)]
    (is (str/includes? out "nomeParlamentar: string | null;"))
    (is (str/includes? out "cargoMesa: string | null;"))
    (is (str/includes? out "desde: string | null;"))
    (is (str/includes? out "justificativa: ") "justificativa e' nulavel (map aninhado sem entrada propria no manifesto)")))

;; ---------- B4 (Etapa 4a): o carry do codegen — o .gen.ts nunca tinha sido emitido ----------

(deftest b4-manifesto-cobre-TODO-o-wire-out
  ;; O gate anti-drift que faltava. O manifesto era escrito a mao e envelheceu em silencio: as Etapas 1 e 2
  ;; acrescentaram PresencaLoteReciboOut, os quatro contratos de justificativa e ChamadaConduzidaOut ao
  ;; wire/out sem toca-lo. O sintoma nao era um erro — era `chamadasConduzidas: Record<string, unknown>[]`
  ;; no arquivo gerado, isto e', o FE perdendo o tipo justamente do dado novo. Comparar o manifesto com
  ;; `ns-publics` faz o compilador do proximo contrato lembrar por nos.
  (let [no-wire (->> (ns-publics 'oplenario.sessoes.wire.out)
                     keys (map name) (filter #(str/ends-with? % "Out")) set)
        no-manifesto (set (map first gerar-sessoes/manifesto))]
    (is (= no-wire no-manifesto)
        (str "manifesto fora de sincronia com wire/out — faltando: "
             (sort (set/difference no-wire no-manifesto))
             " / sobrando: " (sort (set/difference no-manifesto no-wire)))))
  ;; Cobrir o NOME nao basta: a entrada tem de apontar para o var do wire/out, nao para um schema colado no
  ;; manifesto (que passaria no teste acima e envelheceria em silencio, que e' o defeito que ele existe para
  ;; pegar). Aferido no contrato novo desta fatia.
  (is (= wire-out/QuorumSessaoOut (get (into {} gerar-sessoes/manifesto) "QuorumSessaoOut"))
      "a entrada do manifesto E' o schema do wire/out, nao uma copia")
  ;; Achado da revisao adversarial da Etapa 6 fatia 3: o spot-check acima afere o contrato da Etapa 4a, e o
  ;; comentario dizia estar aferindo "o contrato desta fatia". Os SEIS contratos novos da fatia 3 nao eram
  ;; aferidos por identidade — so' pelo NOME, que e' exatamente o que o segundo `is` existe para nao bastar.
  (let [por-nome (into {} gerar-sessoes/manifesto)]
    (doseq [[nome schema] {"AssiduidadeSessaoOut" wire-out/AssiduidadeSessaoOut
                           "AssiduidadeVereadorOut" wire-out/AssiduidadeVereadorOut
                           "AssiduidadePorVereadorOut" wire-out/AssiduidadePorVereadorOut
                           "AssiduidadeDetalheLinhaOut" wire-out/AssiduidadeDetalheLinhaOut
                           "AssiduidadeTotaisOut" wire-out/AssiduidadeTotaisOut
                           "AssiduidadeOut" wire-out/AssiduidadeOut}]
      (is (= schema (get por-nome nome))
          (str "a entrada `" nome "` do manifesto E' o var do wire/out, nao uma copia colada")))))

(deftest b4-geracao-e-reproduzivel
  ;; `gerar-tudo` e' pura por construcao, e este teste e' o que impede que deixe de ser (uma ordenacao por
  ;; hash de mapa em qualquer ponto do caminho produziria um .gen.ts que muda de diff a cada execucao, e o
  ;; arquivo e' COMMITADO — ruido de diff eterno).
  (is (= (gerar-sessoes/gerar-tudo) (gerar-sessoes/gerar-tudo))
      "duas execucoes produzem byte a byte o mesmo arquivo"))

(deftest b4-tipos-que-a-tela-do-telao-consome
  (let [out (gerar-sessoes/gerar-tudo)]
    (is (str/includes? out "export interface QuorumSessaoOut {")
        "a leitura MAGRA de quorum (Etapa 4a) chega tipada ao FE")
    (is (str/includes? out "quorum: ChamadaQuorumOut;")
        "e reusa o MESMO bloco de contagem da chamada — nao um segundo shape de quorum no TS")
    (is (str/includes? out "chamadasConduzidas: ChamadaConduzidaOut[];")
        "ChamadaConduzidaOut por NOME (antes do carry, caia em Record<string, unknown>[])")
    (is (str/includes? out "recibos: PresencaReciboOut[];")
        "o lote de presenca (Etapa 2c) referencia o recibo por nome")
    (is (str/includes? out "justificativas: LinhaJustificativaOut[];")
        "a lista de justificativas (Etapa 2) tipada")))
