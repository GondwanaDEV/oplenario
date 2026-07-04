(ns oplenario.cadastros.components.repositorio
  "Component de PERSISTENCIA do cadastros — o banco DISPONIBILIZADO como Stuart Sierra Component (ADR-0001
  §3, revisao). O protocolo RepoCadastros expoe as ACOES do banco (tenant-aware: trata `com-tenant*` por
  dentro); o record RepoCadastrosPg segura o `:datasource` (injetado via `using`); o `db/` e' a IMPL
  atras do protocolo. O controller depende DESTE Component, nunca do `db/` direto. Trocavel/fakeavel
  como cache/objeto_store/idp. `transacao` permite compor varias acoes numa UNICA tx do tenant."
  (:require [oplenario.cadastros.db.comissao :as comissao]
            [oplenario.cadastros.db.estrutura :as estrutura]
            [oplenario.cadastros.db.vereador :as vereador]
            [oplenario.cadastros.relacoes.cadastro :as rel-cadastro]
            [oplenario.kernel.tenancy :as tenancy]))

(defprotocol RepoCadastros
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant — compoe varias acoes atomicamente.")
  ;; ente / legislatura / sessao
  (criar-ente! [this ente-id ente])
  (buscar-ente [this ente-id])
  (criar-legislatura! [this ente-id legislatura])
  (buscar-legislatura [this ente-id id])
  (legislatura-vigente [this ente-id])
  (criar-sessao-legislativa! [this ente-id sessao])
  ;; vereador / mandato / licenca / suplencia
  (criar-vereador! [this ente-id vereador])
  (buscar-vereador [this ente-id id])
  (vereador-por-identidade [this ente-id identidade-id])
  (criar-mandato! [this ente-id mandato])
  (mudar-estado-mandato! [this ente-id mandato])
  (mandatos-do-vereador [this ente-id vereador-id])
  (criar-licenca! [this ente-id licenca])
  (criar-suplencia! [this ente-id suplencia])
  ;; comissao / cargo / membro
  (criar-comissao! [this ente-id comissao])
  (buscar-comissao [this ente-id id])
  (mesa-vigente [this ente-id data])
  (criar-cargo! [this ente-id cargo])
  (criar-membro! [this ente-id membro])
  (membros-da-comissao [this ente-id comissao-id])
  (membros-da-casa [this ente-id data]
    "Nº de vereadores com mandato vigente em `data` (relacao ja usada pelo motor de regras — F2; exposta
     aqui p/ o host injetar em outros modulos via inversao de dependencia, §22.10, FE Onda A1)."))

(defrecord RepoCadastrosPg [datasource]
  RepoCadastros
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  (criar-ente! [this ente-id ente] (transacao this ente-id #(estrutura/inserir-ente! % ente)))
  (buscar-ente [this ente-id] (transacao this ente-id estrutura/buscar-ente))
  (criar-legislatura! [this ente-id leg] (transacao this ente-id #(estrutura/inserir-legislatura! % leg)))
  (buscar-legislatura [this ente-id id] (transacao this ente-id #(estrutura/buscar-legislatura % id)))
  (legislatura-vigente [this ente-id] (transacao this ente-id #(estrutura/legislatura-vigente % ente-id)))
  (criar-sessao-legislativa! [this ente-id s] (transacao this ente-id #(estrutura/inserir-sessao-legislativa! % s)))
  (criar-vereador! [this ente-id v] (transacao this ente-id #(vereador/inserir! % v)))
  (buscar-vereador [this ente-id id] (transacao this ente-id #(vereador/buscar % id)))
  (vereador-por-identidade [this ente-id ident] (transacao this ente-id #(vereador/por-identidade % ente-id ident)))
  (criar-mandato! [this ente-id m] (transacao this ente-id #(vereador/inserir-mandato! % m)))
  (mudar-estado-mandato! [this ente-id m] (transacao this ente-id #(vereador/mudar-estado! % m)))
  (mandatos-do-vereador [this ente-id ver-id] (transacao this ente-id #(vereador/mandatos-do-vereador % ente-id ver-id)))
  (criar-licenca! [this ente-id l] (transacao this ente-id #(vereador/inserir-licenca! % l)))
  (criar-suplencia! [this ente-id s] (transacao this ente-id #(vereador/inserir-suplencia! % s)))
  (criar-comissao! [this ente-id c] (transacao this ente-id #(comissao/inserir! % c)))
  (buscar-comissao [this ente-id id] (transacao this ente-id #(comissao/buscar % id)))
  (mesa-vigente [this ente-id data] (transacao this ente-id #(comissao/mesa-vigente % data)))
  (criar-cargo! [this ente-id c] (transacao this ente-id #(comissao/inserir-cargo! % c)))
  (criar-membro! [this ente-id m] (transacao this ente-id #(comissao/inserir-membro! % m)))
  (membros-da-comissao [this ente-id com-id] (transacao this ente-id #(comissao/membros % com-id)))
  (membros-da-casa [this ente-id data] (transacao this ente-id #(rel-cadastro/membros-da-casa % data))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoCadastrosPg nil))
