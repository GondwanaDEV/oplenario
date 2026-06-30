(ns oplenario.compliance.models.remessa
  "Representacao INTERNA (dominio) do ARTEFATO de remessa ao TCE (§22.7.8) — Malli (§22.10 models/). O
  artefato e' IMUTAVEL por VERSAO (re-emissao = nova versao, nunca muta hash/ref); ja' o `estado` de
  submissao evolui no ciclo FIXO em codigo (rascunho|validada|submetida|aceita|rejeitada). O binario mora
  no `objeto_store` — aqui so metadados + o ponteiro (`objeto-store-ref`) + o `hash` de integridade.
  Costura `remessa_enviada`: so a linha em 'aceita' cumpre a obrigacao. Enums de compliance.logic."
  (:require [oplenario.compliance.logic :as logic]
            [oplenario.kernel.malli :as km]))

(def Remessa
  "Artefato de remessa persistido (compliance.remessa_gerada). `competencia` = chave 'AAAA-MM'."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id km/EnteId]
   [:template-chave [:string {:min 1}]]
   [:sistema [:string {:min 1}]]                            ; sistema de entrega do TCE (ex.: SIM) — chave da costura
   [:competencia [:re #"^\d{4}-(0[1-9]|1[0-2])$"]]          ; comp_chave "AAAA-MM" (mes 01-12; chave da costura)
   [:versao [:int {:min 1}]]                                ; re-emissao = NOVA versao
   [:spec-layout-versao [:string {:min 1}]]                 ; versao do descritor declarativo de layout (dado, dec. 2b)
   [:registry-versao-ref [:string {:min 1}]]                ; versao do registry usado como fonte (B3)
   [:hash [:string {:min 1}]]                               ; hash do binario (integridade)
   [:objeto-store-ref [:string {:min 1}]]                   ; ponteiro p/ o binario no objeto_store
   [:estado (km/enum-de logic/estados-remessa)]
   [:submetida-em {:optional true} [:maybe km/Instante]]
   [:resposta-em {:optional true} [:maybe km/Instante]]
   [:criado-em km/Instante]])
