(ns oplenario.transparencia.events.notificacao
  "Evento de dominio do fan-out de notificacao (ADR-0001: events/ = nome + schema Malli do payload). F7 E2:
  o ACOMPANHAMENTO do cidadao (F6c Slice 2) vira ENTREGA. Quando uma materia seguida transiciona, transparencia
  faz o fan-out — UM `notificacao.requisitada` POR seguidor ativo — que `paineis` (dono da entrega duravel,
  mig 0004) consome e materializa no ledger. Direcao transparencia->paineis cravada por review architect na
  mig 0045: transparencia tem `acompanhamento` (a lista de seguidores) same-schema e renderiza do `materia`;
  `paineis` NAO pode ler transparencia.acompanhamento (JOIN cross-schema §22.10) NEM expor 'liste seguidores'
  (vazaria a PII 'quem-segue-o-que'). O evento carrega o destinatario JA' resolvido — paineis so' entrega.

  DUPLA IDEMPOTENCIA (§22.9 E2): o ENVELOPE (kernel/eventos) carrega uma idempotency-key ALEATORIA, que dedup
  o CONSUMER (paineis) por (consumidor, key) — como cada fan-out emite o evento UMA vez, aleatoria basta. O
  PAYLOAD carrega uma `idempotency-key` DETERMINISTICA (f(transicao-id, destinatario)) que vira a chave do
  LEDGER (UNIQUE ente_id, idempotency_key, mig 0004): se um redrive/backfill FUTURO re-executar o fan-out (com
  um envelope-key NOVO), a insercao no ledger e' no-op — a mesma notificacao logica nunca duplica a entrega.

  SEM PII DURAVEL: `destinatario-identidade-id` e' o UUID de identidade (handle pseudonimo), NAO e-mail/nome/CPF.
  `assunto`/`corpo` sao renderizados de info PUBLICA (ementa + estado, ja' no portal). O evento (outbox duravel,
  cross-modulo) e o ledger nunca guardam contato real — a resolucao UUID->e-mail + o envio sao carry infra.

  ONDA E (fatia 1), COMPLETA: o vocabulario cresceu AQUI — `canal` admite \"in_app\" e `categoria`
  (opcional) entrou; o contrato segue :closed. A inbox interna (`paineis.notificacao_caixa`) e' um
  SEGUNDO projetor do MESMO evento, com tabela propria, e `legislativo` tem uma COPIA deste schema
  (events/notificacao.clj) porque §22.10 proibe import cross-modulo — o drift entre as duas e' barrado
  por `eventos-notificacao-contrato-test`."
  (:require [malli.core :as m]
            [oplenario.kernel.eventos :as eventos]))

(def requisitada-tipo
  "Nome do evento do fan-out: uma entrega de notificacao foi REQUISITADA para um destinatario. Consumido por
  `paineis` (materializa o intent no ledger de entrega)."
  "notificacao.requisitada")

(def RequisitadaPayload
  "Payload de `notificacao.requisitada`. Um evento POR destinatario (o fan-out ja' foi feito por transparencia)."
  [:map {:closed true}
   ;; o seguidor a notificar (identidade UUID — handle pseudonimo, resolvido do acompanhamento; NAO e' PII).
   ;; viaja como STRING no jsonb do outbox (jsonista nao tem modulo UUID — mesma disciplina dos demais eventos).
   [:destinatario-identidade-id :string]
   ;; canal de entrega. "email" (fan-out do cidadao, F7 E2) | "in_app" (inbox interna, Onda E fatia 1).
   ;; Cada PROJETOR trata APENAS o seu canal (spec §4.3), e ambos os filtros ja' estao em
   ;; `paineis/components/repositorio.clj`: `registrar-intent!` (ledger de entrega) ignora != "email" e
   ;; `projetar-inbox!` ignora != "in_app". Sem essa guarda o worker `entregar-pendentes!` tentaria
   ;; mandar e-mail de um in_app — e' ela que deixa os dois canais coexistirem no mesmo evento.
   [:canal :string]
   ;; base de consentimento (mig 0004.consent_base): "acompanhamento" — o proprio ato de seguir e' o opt-in
   ;; (§22.5, consent-gated). O fan-out so' emite p/ seguidor 'ativo' -> consent-gating por construcao.
   [:consent-base :string]
   ;; chave DETERMINISTICA do ledger (dedup no-op em replay — ver docstring do ns). f(transicao-id, destinatario).
   [:idempotency-key :string]
   ;; conteudo JA' renderizado por transparencia (info publica). paineis e' entrega burra — nao re-renderiza.
   [:assunto :string]
   [:corpo :string]
   ;; rastreabilidade OPACA (ref polimorfica, mesma convencao de paineis.pendencia): o QUE a notificacao trata.
   ;; "proposicao"/proposicao-id nesta fatia — opaco p/ paineis (nao acopla a legislativo).
   [:objeto-tipo :string]
   [:objeto-id :string]
   ;; classe da MENSAGEM (spec D5: "falha" e' categoria de dominio, nao estado de entrega). OPCIONAL de
   ;; proposito — o produtor do cidadao (F7 E2) nao a manda e nao deve ser tocado por esta fatia.
   [:categoria {:optional true} :string]])

(defn requisitada
  "Constroi o envelope de `notificacao.requisitada` p/ o tenant `ente-id`, VALIDANDO o payload contra o
  contrato. Lanca :payload-invalido se nao casa — defesa na fonte: o outbox so recebe evento bem-formado."
  [ente-id payload]
  (when-not (m/validate RequisitadaPayload payload)
    (throw (ex-info "payload de notificacao.requisitada invalido (contrato do evento)"
                    {:erro :payload-invalido :explain (m/explain RequisitadaPayload payload)})))
  (eventos/evento requisitada-tipo ente-id payload))
