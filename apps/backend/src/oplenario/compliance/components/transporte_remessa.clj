(ns oplenario.compliance.components.transporte-remessa
  "Port de SAIDA TransporteRemessa (§22.7.8 D8): a SUBMISSAO do artefato ao TCE. Adapter **'download
  manual' na V1** — o operador baixa o binario do objeto_store, sobe no portal do TCE e CONFIRMA o envio
  (a confirmacao e' o que assere a costura `remessa_enviada`, transitando a obrigacao pendente->cumprida);
  o transporte NAO submete automatico (arquivo regulatorio nao se envia 100% automatico, §22.7.8). Adapter
  de API/webservice do TCE-CE deferido [GAP] (pode nem existir) — disparado por demanda, sem refactor da
  forma (o port ja' existe).")

(set! *warn-on-reflection* true)

(defprotocol TransporteRemessa
  (submeter! [this remessa]
    "Submete (ou prepara a submissao de) a remessa. `remessa` carrega ao menos {:objeto-store-ref :sistema
     :competencia}. Devolve o resultado do transporte ({:modo :status ...}). O adapter manual NAO envia —
     devolve o ponteiro p/ o operador baixar e o status 'aguardando confirmacao'."))

(defrecord TransporteDownloadManual []
  TransporteRemessa
  (submeter! [_ remessa]
    {:modo :manual
     :status "aguardando_confirmacao_operador"
     :objeto-store-ref (:objeto-store-ref remessa)}))

(defn transporte-download-manual
  "Cria o adapter de transporte 'download manual' (V1). Sem estado/Lifecycle."
  []
  (->TransporteDownloadManual))
