(ns oplenario.motor.api
  "Fachada publica do motor de regras (§22.7) — o SEAM in-process que os modulos importam.
  §22.10: 'kernel/motor nunca importam um modulo'; o motor e BIBLIOTECA compartilhada (coracao
  dos 4 usos da DSL — tramitacao, autorizacao, plenario, compliance), nao servico HTTP. O compliance
  OPERA este seam: materializa obrigacao/audita avaliacao nas SUAS tabelas (schema compliance, §22.7.7)."
  (:require [oplenario.motor.nucleo :as nuc]
            [oplenario.motor.verificador :as v]))

(defn verificar-fonte
  "Type-check do save time (Eixo A dec.2) — REAL/pronto: puro, le o catalogo declarado em codigo,
  sem fato externo. Parseia o envelope + tipa. Devolve {:status VALIDA|INVALIDA :erros :avisos
  :registry-versao-ref}. So VALIDA grava forma_compilada como 'vigente' em motor.template_compliance."
  [fonte-yaml]
  (v/verificar-template (nuc/carregar-envelope fonte-yaml)))

;; [SEAMs ainda NAO estabilizados — expostos quando a fiacao chegar; nao blessar como API estavel agora]
;; - avaliar: o avaliador de expressao existe puro em oplenario.motor.runtime/avaliar (tree-walk), MAS o
;;   resolvedor de fatos de PRODUCAO (funcoes de relacao por contexto dono, §22.5.3 disc.5) difere do
;;   :estado em-memoria do prototipo; a injecao do resolvedor e o seam aberto (NAO chamar rt/avaliar
;;   direto em producao). O compliance OPERA a avaliacao: persiste o ciclo nas SUAS tabelas (§22.7.7).
;; - regras-aplicaveis(ds, ente) [db, §22.4.4 deferida]: resolve POR ESCOPO juntando motor.template_compliance
;;   + motor.compliance_regra_tenant (mesmo schema, sem cross-schema JOIN, §22.10).
;; - prazo-vigente(ds, dominio, tipo, competencia) [db, §22.4.4 deferida]: le a linha 'vigente' de
;;   motor.prazo_dominio_vigente (versao persistida do builtin homonimo de runtime).
