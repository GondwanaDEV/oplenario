(ns oplenario.identidade.wire.in.acesso
  "Corpos de requisicao da superficie ADMINISTRATIVA de identidade (§22.10 wire/in, ADR-0001).
  `:closed` em todos: chave forjada (id, ente-id) e' RECUSADA, nao ignorada.")

;; Papeis concedíveis por ESTA rota. admin_ente fica DE FORA de proposito: conceder o papel que concede
;; papeis seria escalada de privilegio por auto-servico. Bootstrap do 1o admin_ente = admin_sistema (carry).
(def papeis-concediveis #{"vereador" "auditor"})

;; O papel acompanha o tipo do vinculo: vereador e' vinculo de vereador; o `auditor` (controle interno, procuradoria —
;; ADR-0017) e' um servidor da Casa que so' LE a trilha de auditoria.
(def papeis-por-tipo {"vereador" #{"vereador"} "servidor" #{"auditor"}})

(def CriarIdentidade
  [:map {:closed true}
   [:cpf [:re #"^\d{11}$"]]
   [:nome [:string {:min 1}]]])

(def ConcederAcesso
  [:map {:closed true}
   [:identidade-id :string]
   [:tipo [:enum "vereador" "servidor"]]
   [:papeis [:vector {:min 1} (into [:enum] (sort papeis-concediveis))]]
   [:email [:re #"^[^@\s]+@[^@\s]+\.[^@\s]+$"]]])
