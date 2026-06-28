(ns oplenario.governanca.auditoria
  "Registro de auditoria append-only do cruzamento da porta (B4): proveniência + decisão + redações +
  vendor + timestamp. SEM duplicar conteúdo (só resumo + hash + contagem). Accountability LGPD —
  demonstra que sigiloso nunca cruzou e que a minimização foi aplicada. Relógio injetado (§22.6).")

(defn registro
  [{:keys [decisao vendor liberadas bloqueadas redacoes payload-hash agora]}]
  {:timestamp        agora
   :decisao          decisao                       ; :liberado | :bloqueado
   :vendor           vendor
   :n-liberadas      (count liberadas)
   :n-bloqueadas     (count bloqueadas)
   :motivos-bloqueio (mapv :motivo bloqueadas)     ; só o motivo, não o conteúdo
   :redacoes         redacoes                      ; {:cpf 2 :email 1} — contagem, não conteúdo
   :payload-hash     payload-hash})                ; ref ao conteúdo, NÃO o conteúdo
