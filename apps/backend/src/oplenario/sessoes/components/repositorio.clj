(ns oplenario.sessoes.components.repositorio
  "Component de PERSISTENCIA do modulo SESSOES — banco DISPONIBILIZADO como Stuart Sierra Component
  (ADR-0001 §3). O protocolo RepoSessoes expoe as ACOES (tenant-aware: trata `com-tenant*` por dentro); o
  record segura o :datasource (via `using`); o db/ e' a IMPL. O controller depende DESTE Component, nunca do
  db/ direto. (Eventos de dominio Sessao*/real-time = eixos posteriores do F4.)"
  (:require [oplenario.kernel.tenancy :as tenancy]
            [oplenario.sessoes.db.pauta :as pauta]
            [oplenario.sessoes.db.sessao :as sessao]))

(defprotocol RepoSessoes
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant — compoe acoes atomicamente.")
  ;; §22.6 eixo A — sessao
  (agendar-sessao! [this ente-id m] "Numera gapless + resolve capabilities + insere 'agendada', atomico.")
  (transicionar-sessao! [this ente-id m] "Move o estado pela maquina (fail-closed) com CAS.")
  (buscar-sessao [this ente-id id])
  (sessoes-da-legislativa [this ente-id sessao-legislativa-id])
  ;; §22.6 eixo B — pauta (camada viva)
  (criar-pauta! [this ente-id m] "Cria a pauta 1:1 da sessao.")
  (buscar-pauta-por-sessao [this ente-id sessao-id])
  (adicionar-item! [this ente-id m] "Insere item (ordem=max+1) + LOGA inclusao, atomico.")
  (reordenar-item! [this ente-id m] "Move item p/ nova ordem + LOGA inversao, atomico.")
  (remover-item! [this ente-id m] "Remocao soft (ativo=false) + LOG, atomico (nunca DELETE).")
  (buscar-item [this ente-id id])
  (listar-itens [this ente-id pauta-sessao-id] "Itens ATIVOS em ordem.")
  (listar-alteracoes [this ente-id pauta-sessao-id]))

(defrecord RepoSessoesPg [datasource bus]
  RepoSessoes
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  (agendar-sessao! [this ente-id m] (transacao this ente-id #(sessao/agendar! % (assoc m :ente-id ente-id))))
  (transicionar-sessao! [this ente-id m] (transacao this ente-id #(sessao/transicionar! % (assoc m :ente-id ente-id))))
  (buscar-sessao [this ente-id id] (transacao this ente-id #(sessao/buscar % ente-id id)))
  (sessoes-da-legislativa [this ente-id slid] (transacao this ente-id #(sessao/listar-por-sessao-legislativa % ente-id slid)))
  (criar-pauta! [this ente-id m] (transacao this ente-id #(pauta/criar-pauta! % (assoc m :ente-id ente-id))))
  (buscar-pauta-por-sessao [this ente-id sessao-id] (transacao this ente-id #(pauta/buscar-pauta-por-sessao % ente-id sessao-id)))
  (adicionar-item! [this ente-id m] (transacao this ente-id #(pauta/adicionar-item! % (assoc m :ente-id ente-id))))
  (reordenar-item! [this ente-id m] (transacao this ente-id #(pauta/reordenar-item! % (assoc m :ente-id ente-id))))
  (remover-item! [this ente-id m] (transacao this ente-id #(pauta/remover-item! % (assoc m :ente-id ente-id))))
  (buscar-item [this ente-id id] (transacao this ente-id #(pauta/buscar-item % ente-id id)))
  (listar-itens [this ente-id pauta-sessao-id] (transacao this ente-id #(pauta/listar-itens % ente-id pauta-sessao-id)))
  (listar-alteracoes [this ente-id pauta-sessao-id] (transacao this ente-id #(pauta/listar-alteracoes % ente-id pauta-sessao-id))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoSessoesPg nil nil))
