(ns oplenario.participacao.logic.complemento
  "PURO (§22.10 logic): o COMPLEMENTO DA RESPOSTA (ADR-0022) — o texto que a secretaria acrescenta a um protocolo que a Casa
  ja' respondeu, depois que a janela de 10 minutos dos anexos fechou. Sem IO e sem relogio.

  - QUANDO cabe: so' em protocolo que ja' tem ato de resposta da Casa (resposta, indeferimento ou, no e-SIC, a decisao do
    recurso). Na ouvidoria, a manifestacao apenas ARQUIVADA nao aceita: arquivar e' encerrar sem resposta de merito. Protocolo
    ainda aberto nao cabe (responda antes);
  - O QUE NAO FAZ: nao muda estado, nao mexe no prazo (a resposta ja' o cumpriu), nao reabre recurso, nao conta como nova
    resposta nas metricas. E' texto a mais no historico;
  - O QUE FAZ com os anexos: conta como o ULTIMO ato de resposta da Casa (`logic.anexo/ultimo-ato-de-resposta`), entao
    reabre por mais 10 minutos a janela de anexos da origem `casa`. O limite de 5 e a janela do requerente nao mudam;
  - QUANTOS: sem limite numerico. Cada um e' um ato com autor e instante."
  (:require [oplenario.participacao.logic.anexo :as anexo]))

(set! *warn-on-reflection* true)

(def max-corpo
  "O teto do texto: o MESMO do texto da resposta (a CHECK `corpo` de participacao.resposta_* e de participacao.complemento)."
  50000)

(defn pode-complementar?
  "A Casa pode complementar a resposta deste protocolo? `especie` = :esic | :ouvidoria | :lgpd; `dados` = o que os detalhes do
  balcao ja' leem ({:estado :respostas :recurso ...}). Verdadeiro so' se ha ato de resposta da Casa. Os complementos ja'
  gravados nao mudam a resposta: um complemento nunca existe sem resposta."
  [especie dados]
  (some? (anexo/ultimo-ato-de-resposta especie dados)))
