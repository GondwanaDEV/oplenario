(ns oplenario.compliance.controllers
  "Orquestracao (impura) do modulo compliance (§22.10 controllers, ADR-0001): coordena o Repo-Component. O
  loop de runtime (materializa->avalia->monitora->audita) mora no Repo (F5.1-3); aqui ficam as operacoes
  da BORDA HTTP (F5.5): o read-model do painel (§16.11) + o ciclo da remessa (validar/submeter/registrar
  resposta). Trabalha SO em `models`/dados de dominio — NUNCA toca wire/adapters (o import-lint enforca);
  a traducao da borda fica no diplomat. Depende do Repo-Component, nunca do db/ direto. O `ator` (resolvido
  na borda) e' o sujeito de toda operacao (§22.5: sem ator = proibido)."
  (:require [oplenario.compliance.components.repositorio :as repo]))

(set! *warn-on-reflection* true)

(defn painel
  "Read-model do painel 'a Casa esta em dia com o TCE' (§16.11) p/ o tenant do `ator`. A authz GROSSA
  (papel) ja foi exigida na rota; o painel e' tenant-wide (sem recurso unico p/ camada fina — o escopo e'
  o proprio ente do ator, isolado por RLS). Os TETOS dos reads sao server-side (no Repo). Devolve o
  read-model {:resumo :em-aberto :remessas-recentes} (o diplomat projeta p/ wire)."
  [repo-compliance ator]
  (repo/painel repo-compliance (:ente-id ator) {}))

;; ---------- ciclo da remessa (§22.7.8): validar -> submeter -> registrar-resposta do TCE (F5.5b) ----------

(defn- transicionar-remessa
  "Aplica `transicao!` (thunk que chama o metodo de transicao do Repo — devolve a remessa ja transicionada
  ou nil no CAS perdido) e DESAMBIGUA o nil: a remessa AINDA existe no tenant -> conflito de ciclo (estado
  incompativel; o handler mapeia p/ 409); ausente -> nil (o handler mapeia p/ 404). O happy-path e' uma
  unica chamada — o existence-check (`remessa-existe?`) so paga no (raro) miss, e a RLS garante que so a
  remessa do proprio tenant e' visivel (sem vazamento cross-tenant)."
  [repo-compliance ente-id id transicao!]
  (or (transicao!)
      (when (repo/remessa-existe? repo-compliance ente-id id)
        (throw (ex-info "transicao de remessa em conflito (estado incompativel com o ciclo)"
                        {:tipo :conflito/remessa :id id})))))

(defn validar-remessa
  "Transiciona a remessa `id` rascunho->validada no tenant do `ator`. nil = inexistente (-> 404);
  :conflito/remessa = ja-transicionada / estado incompativel (-> 409)."
  [repo-compliance ator id]
  (let [ente-id (:ente-id ator)]
    (transicionar-remessa repo-compliance ente-id id
                          #(repo/validar-remessa! repo-compliance ente-id id))))

(defn submeter-remessa
  "Transiciona a remessa `id` validada->submetida no tenant do `ator`. Mesma semantica de erro de validar."
  [repo-compliance ator id]
  (let [ente-id (:ente-id ator)]
    (transicionar-remessa repo-compliance ente-id id
                          #(repo/submeter-remessa! repo-compliance ente-id id))))

(defn registrar-resposta-remessa
  "Registra a resposta do TCE (`estado` = aceita|rejeitada, ja validado na borda) na remessa `id`:
  submetida->{aceita|rejeitada}. So 'aceita' cumpre a obrigacao (costura remessa_enviada). Mesma semantica
  de erro de validar (nil -> 404; :conflito/remessa -> 409)."
  [repo-compliance ator id estado]
  (let [ente-id (:ente-id ator)]
    (transicionar-remessa repo-compliance ente-id id
                          #(repo/registrar-resposta-remessa! repo-compliance ente-id id estado))))
