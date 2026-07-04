(ns oplenario.codegen.gerar
  "Entrypoint do codegen Malli->TS (FE0 + FE Onda A1). Host-level: declara o MANIFESTO de models/wire a
  exportar e escreve o .ts. Roda via:
    clojure -M -m oplenario.codegen.gerar [caminho-de-saida]
  Default = target/generated-ts/oplenario-tipos.ts."
  (:require [clojure.java.io :as io]
            [oplenario.cadastros.models.cadastro :as cad]
            [oplenario.codegen.malli-ts :as ts]
            [oplenario.paineis.wire.out.mesa :as mesa-wire]))
;; NOTA (ADR-0001/§22.10): a fonte CORRETA dos tipos TS e' o `wire/out` (externo, ja filtrado), nao
;; models/ internos. cadastros ainda entra via models/ (sem PII, provisorio). identidade FORA — o model
;; Identidade carrega :cpf; exporta-lo vazaria PII no contrato do front. Entra via wire/out sem-CPF (FE0).

(def manifesto
  "Os tipos exportados como TS do front. Ordem deterministica; as entradas de MesaOut (Tramitacao/
  Pendencias/Sessoes/Presenca/Esic/RelatorPendente/RelatoresPendentes/CardIndisponivel) vem ANTES de
  MesaOut no mapa p/ a referencia nomeada (Task codegen A7) casar por igualdade estrutural nos campos
  aninhados — inclusive os 3 campos [:or <forma-fechada> CardIndisponivelOut] (degradacao por card,
  Critical review fix pos-A5/A6)."
  [["Ente" cad/Ente]
   ["Legislatura" cad/Legislatura]
   ["Vereador" cad/Vereador]
   ["Mandato" cad/Mandato]
   ["Comissao" cad/Comissao]
   ["ComissaoMembro" cad/ComissaoMembro]
   ;; FE Onda A1 — dashboard da Mesa. Todos vindos de paineis/wire/out/mesa (a copia estrutural que
   ;; MesaOut de fato contem; igualdade estrutural — nao identidade de modulo — e' o que o codegen casa).
   ["TramitacaoResumoOut" mesa-wire/TramitacaoResumoOut]
   ["PendenciasResumoOut" mesa-wire/PendenciasResumoOut]
   ["SessoesResumoOut" mesa-wire/SessoesResumoOut]
   ["PresencaResumoOut" mesa-wire/PresencaResumoOut]
   ["EsicCumprimentoOut" mesa-wire/EsicCumprimentoOut]
   ["RelatorPendenteOut" mesa-wire/RelatorPendenteOut]
   ["RelatoresPendentesOut" mesa-wire/RelatoresPendentesOut]
   ["CardIndisponivelOut" mesa-wire/CardIndisponivelOut]
   ["MesaOut" mesa-wire/MesaOut]])

(defn gerar-tudo [] (ts/gerar manifesto))

(defn -main [& [saida]]
  (let [caminho (or saida "target/generated-ts/oplenario-tipos.ts")
        conteudo (gerar-tudo)]
    (io/make-parents caminho)
    (spit caminho conteudo)
    (println "[oplenario] tipos TS gerados em" caminho "(" (count manifesto) "interfaces)")))
