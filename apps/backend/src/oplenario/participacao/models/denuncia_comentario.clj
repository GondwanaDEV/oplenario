(ns oplenario.participacao.models.denuncia-comentario
  "Representacao INTERNA (dominio) da DENUNCIA de um comentario (§22.10 models/, ADR-0001) — Malli.
  APPEND-ONLY (Inv.10): o registro de cada ato de denunciar; a IDEMPOTENCIA (1 por cidadao/comentario) e'
  garantida pela UNIQUE(ente_id, comentario_id, denunciante_identidade_id) da mig 0043, nao por este schema.
  `motivo` e' texto livre OPCIONAL (nao e' vocabulario fixo — diferente de motivo-rejeicao da moderacao)."
  (:require [oplenario.kernel.malli :as km]))

(def DenunciaComentario
  "Denuncia persistida (participacao.denuncia_comentario)."
  [:map {:closed true}
   [:id :uuid]
   [:ente-id km/EnteId]
   [:comentario-id :uuid]
   [:denunciante-identidade-id :uuid]
   [:motivo [:maybe [:string {:max 2000}]]]
   [:denunciado-em km/Instante]
   [:criado-em km/Instante]])
