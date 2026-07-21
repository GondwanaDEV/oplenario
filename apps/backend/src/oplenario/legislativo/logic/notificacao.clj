(ns oplenario.legislativo.logic.notificacao
  "Logica PURA da notificacao interna de `norma.publicada` (Onda E fatia 1) — sem I/O, unit-testavel.
  Espelha `transparencia.logic.notificacao` (mesmo idioma: renderizar + chave determinística), mas o
  conteudo aqui e' 'a SUA proposicao virou lei' — ciencia-de-fato, nao pedido de acao (spec D3: o que
  exige acao mora em pendencias; a inbox e' acompanhamento)."
  (:require [clojure.string :as str]))

(defn chave-idempotencia
  "Chave DETERMINISTICA da notificacao na inbox (UNIQUE ente_id, idempotency_key, mig 0062). Deriva de
  (norma-id, categoria, destinatario): uma notificacao logica por (norma publicada, MOTIVO, autor). Um
  redrive/backfill que re-execute o consumer gera a MESMA chave -> a insercao e' no-op. NAO usa a
  idempotency-key ALEATORIA do envelope (essa dedup o consumer, nao a mensagem logica).

  `categoria` e' parametro EXPLICITO, nao string fixa embutida: hoje so' existe UM produtor/motivo
  ('norma_publicada'), entao (norma-id, destinatario) sozinho ja' e' unico de fato — mas essa unicidade e'
  propriedade do UNICO PRODUTOR QUE EXISTE HOJE, nao do dominio. No dia em que uma 2a notificacao sobre a
  MESMA norma ao MESMO destinatario aparecer (ex.: 'norma_revogada'), ela colidiria com esta sem o
  discriminador, e o ON CONFLICT DO NOTHING a engoliria em silencio (sem log, sem metrica, sem teste
  vermelho). Cravar `categoria` na chave AGORA obriga o proximo produtor a decidir o motivo, em vez de
  herdar uma garantia que so' vale por acidente de so' haver um produtor."
  [norma-id categoria destinatario-identidade-id]
  (str "norma:" norma-id ":" categoria ":dest:" destinatario-identidade-id))

(defn- identificador-norma
  "Identificador humano da norma: '<Tipo> <numero>/<ano>' (ex.: 'Lei 3/2026')."
  [{:keys [tipo-norma numero ano]}]
  (str (str/capitalize (str tipo-norma)) " " numero "/" ano))

(defn renderizar
  "Renderiza {:assunto :corpo} — info PUBLICA (a norma publicada e' ato publico por natureza). O
  destinatario NAO aparece no texto (a entrega e' 1:1); nenhuma PII entra no evento nem na tabela."
  [norma]
  (let [id-norma (identificador-norma norma)]
    {:assunto (str "A sua proposicao virou lei — " id-norma)
     :corpo   (str "A proposicao de sua autoria foi promulgada e publicada como " id-norma ".\n\n"
                   "Ementa: " (:ementa norma) "\n"
                   "URN: " (:urn norma) "\n\n"
                   "O texto vigente esta' disponivel no acervo de legislacao da Camara.")}))
