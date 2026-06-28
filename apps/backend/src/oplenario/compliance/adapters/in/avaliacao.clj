(ns oplenario.compliance.adapters.in.avaliacao)

;; ADAPTER IN (gate de ENTRADA): wire/in -> model. Valida + coage + defende o que entra no
;; nucleo (request HTTP / evento consumido / resposta de upstream). Fail-closed na borda.
