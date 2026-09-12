(ns oplenario.participacao.diplomat.consumers)

;; inbound: assina eventos de dominio (V1 Slice 1: NENHUM — o sweep de vencimento e' job do Repo
;; `varrer-vencimentos!` disparado por scheduler, F6.3, nao consumer). Stub p/ a silhueta ADR-0001.
;;
;; CARRY DE INFRA CONFIRMADO (frente 'truncamento-familia', sitio (a) — nao e' truncamento, e' AUSENCIA):
;; `varrer-vencimentos!` NUNCA roda em producao. Prova por grep (repetivel): os UNICOS chamadores no
;; repositorio inteiro sao testes — `test/integration/oplenario/participacao/sweep_test.clj` e
;; `ouvidoria_test.clj`. Nao ha scheduler nenhum a chamar: `oplenario.kernel.components.scheduler` so' tem
;; as primitivas de advisory-lock que o RELAY do outbox usa para eleger lider (§22.9 Eixo 3) — nao existe,
;; em lugar nenhum do sistema, um job runner periodico generico. O MESMO vale para
;; `compliance/varrer-vencimentos!` (mesmo grep, mesmo resultado): esta e' uma lacuna de INFRA
;; compartilhada pelos dois modulos, nao algo introduzido por esta fatia.
;;
;; O teto (`db/prazo-ativo/teto-sweep`, 1000) NAO e' o risco real: o predicado SE AUTO-ESVAZIA — cada CAS
;; `vencer-se-pendente!` exige `estado='pendente'` e move a linha para 'vencida', tirando-a do WHERE da
;; PROXIMA passada (`pendentes-vencidas-ate` so' le' `estado='pendente'`). Sem head-of-line: um backlog
;; > 1000 drena em passadas sucessivas, como a docstring de `varrer-vencimentos!` ja' documentava. Prova
;; viva no repo: `sweep-e-idempotente` (mesmo ns de teste) ja' mostra que uma 2a passada e' no-op sobre o
;; que a 1a ja' venceu — o mecanismo de auto-esvaziamento e' exercitado, so' nunca e' INVOCADO fora de teste.
;;
;; O RISCO REAL: prazos de e-SIC (LAI) e de solicitacao do titular (LGPD) que vencem HOJE, em QUALQUER Casa
;; em producao, jamais transicionam para 'vencida' e jamais emitem `participacao.prazo.vencido` — nao ha
;; quem chame o job. Isto e' silencioso (sem erro, sem 500, sem log) e bate direto na Aposta 3 do produto
;; (confianca operacional / compliance): um prazo legal estourado que o sistema nunca percebe e' pior do que
;; um teto de listagem truncando. NAO E' CONSERTAVEL com um campo `-total`/`-truncado` (a familia
;; 'truncamento-familia' inteira e' sobre CORTE de dado que EXISTE; aqui o dado nem chega a mudar de
;; estado) — o conserto real e' infra (um job runner + wiring, ou uma rota administrativa que dispare o
;; sweep sob demanda) e fica fora do escopo desta fatia, registrado aqui para nao ser confundido com "ja'
;; funciona, so' tem um teto".
