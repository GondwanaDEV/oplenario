(ns oplenario.identidade.wire.in.acesso
  "Corpos de requisicao da superficie ADMINISTRATIVA de identidade (§22.10 wire/in, ADR-0001).
  `:closed` em todos: chave forjada (id, ente-id) e' RECUSADA, nao ignorada.")

;; Papeis concedíveis por ESTA rota. admin_ente fica DE FORA de proposito: conceder o papel que concede
;; papeis seria escalada de privilegio por auto-servico. Bootstrap do 1o admin_ente = admin_sistema (carry).
(def papeis-concediveis #{"vereador" "auditor" "juridico"})

;; O papel acompanha o tipo do vinculo: vereador e' vinculo de vereador; o `auditor` (controle interno, procuradoria —
;; ADR-0017) e' um servidor da Casa que so' LE a trilha de auditoria; o `juridico` (ADR-0019) e' o servidor que assina
;; o parecer juridico, e carrega qualificacao + OAB.
(def papeis-por-tipo {"vereador" #{"vereador"} "servidor" #{"auditor" "juridico"}})

;; As qualificacoes do advogado que assina (ADR-0019 Eixo 1): a que titulo o parecer e' assinado.
(def qualificacoes-juridico ["efetivo" "comissionado" "contratado"])

(def CriarIdentidade
  [:map {:closed true}
   [:cpf [:re #"^\d{11}$"]]
   [:nome [:string {:min 1}]]])

(def ConcederAcesso
  [:map {:closed true}
   [:identidade-id :string]
   [:tipo [:enum "vereador" "servidor"]]
   [:papeis [:vector {:min 1} (into [:enum] (sort papeis-concediveis))]]
   [:email [:re #"^[^@\s]+@[^@\s]+\.[^@\s]+$"]]
   ;; So' com o papel `juridico`; a exigencia (obrigatorios com ele, proibidos sem) e' do adapter, nao do schema.
   [:qualificacao {:optional true} (into [:enum] qualificacoes-juridico)]
   [:oab {:optional true} :string]])

;; ADR-0005 (adendo "Revogar acesso"): revoga-se o que a tela concede — o MESMO conjunto de papeis (`admin_ente` e
;; `secretario` ficam de fora: o administrador nao tira o proprio papel por aqui, nem a secretaria por ele). O motivo e'
;; obrigatorio e fica no registro: a Casa responde pelo acesso que tirou.
(def RevogarAcesso
  [:map {:closed true}
   [:papel (into [:enum] (sort papeis-concediveis))]
   [:motivo [:string {:min 3 :max 500}]]])
