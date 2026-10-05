(ns oplenario.transparencia.logic.notificacao
  "Logica PURA do fan-out de notificacao (F7 E2) — sem I/O, unit-testavel. Renderiza o conteudo (assunto/corpo)
  a partir da materia (info PUBLICA) + a transicao, e deriva a chave de idempotencia DETERMINISTICA do ledger.
  Separada de components/repositorio (que le' db e emite) p/ testar a renderizacao/chave sem PG."
  (:require [clojure.string :as str]))

(defn chave-idempotencia
  "Chave DETERMINISTICA da entrega no ledger de `paineis` (UNIQUE ente_id, idempotency_key, mig 0004). Deriva
  de (transicao-id, destinatario): uma entrega logica por (transicao, seguidor). Um redrive/backfill FUTURO
  que re-execute o fan-out gera a MESMA chave -> a insercao no ledger e' no-op (nunca duplica a entrega).
  NAO usa a idempotency-key ALEATORIA do envelope (essa dedup o consumer, nao a entrega logica)."
  [transicao-id destinatario-identidade-id]
  (str "transicao:" transicao-id ":dest:" destinatario-identidade-id))

(def ^:private sigla-por-tipo
  "Sigla da especie como o portal a mostra (`formatarNumeroProposicao`, frontend `proposicoes-vista.ts`). Copia
  de proposito: transparencia nao importa o `legislativo` (§22.10) e o portal tambem mantem a sua. Tipo fora
  do mapa cai na versao humanizada, nunca na chave crua."
  {"projeto_lei"                 "PL"
   "projeto_lei_complementar"    "PLC"
   "projeto_resolucao"           "PR"
   "projeto_decreto_legislativo" "PDL"
   "proposta_emenda_lom"         "PELOM"
   "indicacao"                   "IND"
   "requerimento"                "REQ"
   "mocao"                       "MOÇ"})

(def ^:private rotulo-por-fase
  "Rotulo da fase como o portal a mostra (`derivarTramitacao`, frontend `tramitacao-vista.ts`). O evento de
  transicao traz so' a chave do estado; o estado e' string LIVRE por Camara (template), entao o mapa cobre o
  rito ilustrativo e o resto cai em `humanizar`."
  {"protocolada"    "Protocolado"
   "em_comissoes"   "Em comissões"
   "em_pauta"       "Em pauta"
   "primeiro_turno" "Em 1º turno"
   "segundo_turno"  "Em 2º turno"
   "em_sancao"      "Em sanção"
   "aprovada"       "Aprovado"
   "arquivada"      "Arquivada"})

(defn- humanizar
  "'aguardando_pauta' -> 'Aguardando pauta': troca separador por espaco e sobe a inicial. Nao inventa
  semantica (nem acento); e' so' melhor que a chave."
  [x]
  (let [limpo (-> (str x) (str/replace #"[_-]+" " ") str/trim)]
    (if (str/blank? limpo) (str x) (str (str/upper-case (subs limpo 0 1)) (subs limpo 1)))))

(defn- identificador-materia
  "Identificador humano da materia, no formato do portal: '<SIGLA> <sequencial>/<ano>' (ex.: 'PL 12/2026')."
  [{:keys [tipo sequencial ano]}]
  (str (or (get sigla-por-tipo tipo) (humanizar tipo)) " " sequencial "/" ano))

(defn renderizar
  "Renderiza {:assunto :corpo} da notificacao de transicao — info PUBLICA (ementa + novo estado). `materia` =
  {:tipo :ano :sequencial :ementa ...}; `para` = o novo estado da tramitacao (do evento, autoritativo). Texto
  para o cidadao, em portugues, com a especie e a fase como o portal as mostra; sem PII (o destinatario nao
  aparece no corpo — a entrega e' 1:1)."
  [materia para]
  (let [id-materia (identificador-materia materia)]
    {:assunto (str "Movimentação: " id-materia)
     :corpo   (str "A matéria " id-materia " que você acompanha teve movimentação.\n\n"
                   "Ementa: " (:ementa materia) "\n"
                   "Nova fase: " (or (get rotulo-por-fase para) (humanizar para)) "\n\n"
                   "Veja a matéria no portal da Câmara.")}))
