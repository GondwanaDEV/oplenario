(ns oplenario.identidade.wire.out.agente-institucional
  "Representacao EXTERNA de SAIDA dos agentes institucionais da Casa (B.8, ADR-0013): o que o `admin_ente` pode ligar e
  desligar, e em que pe' esta' cada um. So' a data da concessao — nunca quem concedeu (id de pessoa nao sai aqui).")

(def AgenteInstitucionalOut
  [:map {:closed true}
   [:agente :string]
   [:titulo :string]
   [:descricao :string]
   [:classes [:vector [:enum "leitura" "rascunho"]]]
   [:ligado :boolean]
   [:ligado-em [:maybe :string]]])

(def AgentesInstitucionaisOut
  [:map {:closed true} [:itens [:vector AgenteInstitucionalOut]]])
