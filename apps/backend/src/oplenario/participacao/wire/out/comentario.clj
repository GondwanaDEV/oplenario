(ns oplenario.participacao.wire.out.comentario
  "Representacao EXTERNA de SAIDA do comentario (§22.10 wire/out, ADR-0001) — os contratos de borda que o
  `adapters/out` produz. Tudo JSON-serializavel (Instant vira string). ReciboOut = a resposta 201 (SO id+
  estado — sem PII, nem para o proprio autor, mesmo padrao dos recibos de e-SIC/ouvidoria). PublicoOut = o
  item da lista PUBLICA (comentarios-da-materia) — NUNCA expoe autor/proposicao/tenant/estado interno
  (so' aparece la' o que JA e' aprovado)."
  (:require [oplenario.kernel.malli :as km]
            [oplenario.participacao.logic :as logic]))

(def ReciboOut
  "O recibo da criacao (resposta 201 de POST /portal/materias/:proposicao_id/comentarios). So o id + o
  estado inicial ('pendente'). Sem PII, sem proposicao/autor (o cliente ja os sabe — ele os enviou/e' ele)."
  [:map {:closed true}
   [:id :string]
   [:estado (km/enum-de logic/estados-comentario)]])

(def PublicoOut
  "Item da lista PUBLICA de comentarios de uma materia (SO aprovados). Sem autor, sem tenant, sem estado
  (implicito — a lista so' tem aprovados)."
  [:map {:closed true}
   [:id :string]
   ;; CONTEUDO DO USUARIO (texto livre, NAO sanitizado no backend): o consumidor DEVE escapar antes de
   ;; renderizar como HTML (React/Next escapa por padrao).
   [:corpo :string]
   [:criado-em :string]])

