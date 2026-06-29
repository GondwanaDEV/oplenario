(ns oplenario.sessoes.components.repositorio
  "Component de PERSISTENCIA do modulo SESSOES — banco DISPONIBILIZADO como Stuart Sierra Component
  (ADR-0001 §3). O protocolo RepoSessoes expoe as ACOES (tenant-aware: trata `com-tenant*` por dentro); o
  record segura o :datasource (via `using`); o db/ e' a IMPL. O controller depende DESTE Component, nunca do
  db/ direto. (Eventos de dominio Sessao*/real-time = eixos posteriores do F4.)"
  (:require [oplenario.kernel.tenancy :as tenancy]
            [oplenario.sessoes.db.sessao :as sessao]))

(defprotocol RepoSessoes
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant — compoe acoes atomicamente.")
  ;; §22.6 eixo A — sessao
  (agendar-sessao! [this ente-id m] "Numera gapless + resolve capabilities + insere 'agendada', atomico.")
  (transicionar-sessao! [this ente-id m] "Move o estado pela maquina (fail-closed) com CAS.")
  (buscar-sessao [this ente-id id])
  (sessoes-da-legislativa [this ente-id sessao-legislativa-id]))

(defrecord RepoSessoesPg [datasource bus]
  RepoSessoes
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  (agendar-sessao! [this ente-id m] (transacao this ente-id #(sessao/agendar! % (assoc m :ente-id ente-id))))
  (transicionar-sessao! [this ente-id m] (transacao this ente-id #(sessao/transicionar! % (assoc m :ente-id ente-id))))
  (buscar-sessao [this ente-id id] (transacao this ente-id #(sessao/buscar % ente-id id)))
  (sessoes-da-legislativa [this ente-id slid] (transacao this ente-id #(sessao/listar-por-sessao-legislativa % ente-id slid))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoSessoesPg nil nil))
