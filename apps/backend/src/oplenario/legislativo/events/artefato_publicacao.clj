(ns oplenario.legislativo.events.artefato-publicacao
  "Evento de dominio do ARTEFATO DE PUBLICACAO OFICIAL ('DO-lite', F6c Slice 4b, feature 16.5) — ADR-0001:
  events/ = nome + schema Malli do payload. O ENVELOPE vem do kernel (eventos/evento); aqui mora o
  VOCABULARIO. `artefato.publicacao.gerado` e' emitido quando o Repo materializa (INSERT) um artefato de uma
  norma publicada (db/artefato-publicacao/inserir-versionada!), DENTRO da MESMA tx do INSERT (atomicidade
  outbox-com-o-ato §22.9 E2 — a linha do evento so' existe se o INSERT commitou). Carrega o snapshot PUBLICO
  do ponteiro + proveniencia p/ o read-model do portal (transparencia, §16.5) PROJETAR o artefato e servir a
  rota publica de download SEM consultar o legislativo (§22.10). O DONO da verdade (o binario + a linha
  imutavel) segue no legislativo; o evento so' NOTIFICA a projecao.

  `criado-em` viaja como STRING ISO-8601 (nao java.time.Instant): o outbox serializa o payload em jsonb e a
  serializacao de Instant quebra em silencio na fronteira (jsonista sem modulo java.time). O consumidor
  re-parseia (Instant/parse) ao projetar — mesma disciplina de events.norma/PublicadaPayload (`publicado-em`).

  `assinado?` (boolean) = `(some? assinado_por)` na linha: enquanto a assinatura ICP-Brasil real + o ator
  assinante nao estao fiados (stub 'STUB-ICP-v0', F1.4-carry) e' sempre false — o portal exibe 'assinatura em
  homologacao'. O snapshot NAO carrega assinatura_b64 (a assinatura destacada e' proveniencia interna do
  legislativo; o portal so' precisa saber SE ha, o algoritmo, e o hash de integridade)."
  (:require [oplenario.kernel.eventos :as eventos]))

(def gerado-tipo
  "Nome do evento emitido quando um artefato de publicacao e' gerado (materializado + assinado)."
  "artefato.publicacao.gerado")

(def GeradoPayload
  "Payload de `artefato.publicacao.gerado` — snapshot PUBLICO do ponteiro + proveniencia. Dado publico por
  natureza (o artefato E' o ato oficial publicado no veiculo da Camara)."
  [:map {:closed true}
   [:norma-id :uuid]
   [:artefato-id :uuid]
   [:versao :int]
   [:hash :string]                                          ; sha256:... do binario (integridade)
   [:objeto-store-ref :string]                              ; ponteiro p/ o binario no objeto_store (a rota le' dele)
   [:content-type :string]
   [:assinatura-algoritmo :string]                          ; 'STUB-ICP-v0' ([GAP] real ICP, §22.5 eixo F)
   [:assinado? :boolean]                                    ; (some? assinado_por) — false enquanto stub
   [:criado-em :string]])                                   ; ISO-8601 (ver docstring do ns: sem Instant no jsonb)

(defn gerado
  "Constroi o envelope de `artefato.publicacao.gerado` p/ o tenant `ente-id`, VALIDANDO o payload. Lanca
  :payload-invalido se nao casa — o outbox so' recebe evento bem-formado (e a tx do INSERT rola atras)."
  [ente-id payload]
  (eventos/evento-validado GeradoPayload gerado-tipo ente-id payload))
