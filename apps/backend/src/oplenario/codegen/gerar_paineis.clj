(ns oplenario.codegen.gerar-paineis
  "Entrypoint do codegen Malli->TS do modulo PAINEIS (Onda E fatia 1). Espelha oplenario.codegen.gerar-portal
  (mesmo racional/ferramenta, manifesto proprio). Ate' aqui `paineis` nao tinha manifesto: os tipos da Mesa e
  do board saem por `gerar.clj` (host-level, FE Onda A1) e permanecem la' — este arquivo nasce com a INBOX e
  e' o lugar onde os proximos wire/out do modulo entram. Roda via:
    clojure -M -m oplenario.codegen.gerar-paineis [caminho-de-saida]
  Default = target/generated-ts/contrato-paineis.gen.ts."
  (:require [clojure.java.io :as io]
            [oplenario.codegen.malli-ts :as ts]
            [oplenario.paineis.wire.out.notificacao :as notificacao]))

(def manifesto
  "NotificacaoOut ANTES de MinhasNotificacoesOut (referencia nomeada: o campo :notificacoes aninha
  NotificacaoOut — a igualdade estrutural exige a entrada ja' presente no mapa nome-por-schema, mesmo
  racional do manifesto do portal)."
  [["NotificacaoOut" notificacao/NotificacaoOut]
   ["MinhasNotificacoesOut" notificacao/MinhasNotificacoesOut]
   ["MarcarLidaOut" notificacao/MarcarLidaOut]])

(defn gerar-tudo [] (ts/gerar manifesto))

(defn -main [& [saida]]
  (let [caminho (or saida "target/generated-ts/contrato-paineis.gen.ts")
        conteudo (gerar-tudo)]
    (io/make-parents caminho)
    (spit caminho conteudo)
    (println "[oplenario] tipos TS de paineis gerados em" caminho "(" (count manifesto) "interfaces)")))
