(ns oplenario.participacao.wire.in.encarregado
  "Representacao EXTERNA de ENTRADA do ENCARREGADO/DPO (§22.10 wire/in, ADR-0001) — o corpo que o SERVIDOR envia
  ao DEFINIR/atualizar o contato publico do Encarregado (PUT /lgpd/encarregado). So os campos do contato:
  `nome`, `rotulo`, `email`. atualizado-por INJETADO do ator, NUNCA do corpo. Map CLOSED — fail-closed 400.")

(def DefinirEncarregado
  "Corpo de definicao do Encarregado/DPO. Closed: allowlist estrita (nome + rotulo + email). Os tetos (:max)
  espelham os CHECK de tamanho da mig 0041. Sem validacao semantica de email (mantido [GAP]: so nao-vazio + teto)."
  [:map {:closed true}
   [:nome   [:string {:min 1 :max 200}]]
   [:rotulo [:string {:min 1 :max 200}]]
   [:email  [:string {:min 1 :max 320}]]])
