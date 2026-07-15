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
            [oplenario.kernel.tenancy :as tenancy])
  (:import (org.postgresql.util PSQLException)))

(defprotocol RepoCadastros
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant — compoe varias acoes atomicamente.")
  ;; ente / legislatura / sessao
  (criar-ente! [this ente-id ente])
  (buscar-ente [this ente-id])
  (uf-e-municipio [this ente-id]
    "uf + nome do municipio do ente — o FATO que legislativo/protocolar! precisa (injetado pelo host,
     inversao de dependencia §22.10, Onda B Slice 2).")
  (criar-legislatura! [this ente-id legislatura])
  (buscar-legislatura [this ente-id id])
  (legislatura-vigente [this ente-id])
  (criar-sessao-legislativa! [this ente-id sessao])
  ;; vereador / mandato / licenca / suplencia
  (criar-vereador! [this ente-id vereador])
  (buscar-vereador [this ente-id id])
  (listar-vereadores [this ente-id data]
    "Vereadores da Casa com mandato+cargo-na-Mesa vigentes em `data` (Task 1, `vereador/listar`).")
  (ficha-vereador [this ente-id id data]
    "Leitura composta NUMA UNICA tx (mesma disciplina de ficha-completa-da-proposicao):
     {:vereador :mandato :legislatura :comissoes}, ou nil se o vereador nao existe.")
  (vereador-por-identidade [this ente-id identidade-id])
  (criar-mandato! [this ente-id mandato])
  (mudar-estado-mandato! [this ente-id mandato])
  (mandatos-do-vereador [this ente-id vereador-id])
  (criar-licenca! [this ente-id licenca])
  (criar-suplencia! [this ente-id suplencia])
  (atualizar-vereador! [this ente-id id campos]
    "UPDATE parcial de nome/nome-parlamentar da linha efetivada. Devolve update-count (0 = inexistente).")
  (ligar-identidade! [this ente-id id identidade-id]
    "Liga vereador -> identidade. Passo (2) do provisionamento; NAO concede acesso (spec §4.2). Devolve
     update-count (0 = vereador inexistente/de-outro-tenant); throws :conflito/identidade-ja-vinculada se
     a identidade ja' estiver ligada a OUTRO vereador nesta Casa (indice UNIQUE parcial, 23505).")
  (registrar-mandato! [this ente-id mandato]
    "INSERT de mandato 'vigente' numa tx: 404 (nil) se vereador/legislatura ausente; throws
     :conflito/mandato-sobreposto se ja' ha' vigente sobreposto (guard + a rede EXCLUDE); senao {:id}.")
  (registrar-licenca! [this ente-id vereador-id licenca data]
    "Licenca record-only numa tx: 404 (nil) se vereador ausente; throws :conflito/sem-mandato-vigente se
     nao ha' mandato vigente cobrindo `data`; senao INSERT licenca + UPDATE mandato.estado='licenciado' -> {:id}.")
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
  (uf-e-municipio [this ente-id] (transacao this ente-id estrutura/uf-e-municipio))
  (criar-legislatura! [this ente-id leg] (transacao this ente-id #(estrutura/inserir-legislatura! % leg)))
  (buscar-legislatura [this ente-id id] (transacao this ente-id #(estrutura/buscar-legislatura % id)))
  (legislatura-vigente [this ente-id] (transacao this ente-id #(estrutura/legislatura-vigente % ente-id)))
  (criar-sessao-legislativa! [this ente-id s] (transacao this ente-id #(estrutura/inserir-sessao-legislativa! % s)))
  (criar-vereador! [this ente-id v] (transacao this ente-id #(vereador/inserir! % v)))
  (buscar-vereador [this ente-id id] (transacao this ente-id #(vereador/buscar % ente-id id)))
  (listar-vereadores [this ente-id data] (transacao this ente-id #(vereador/listar % ente-id data)))
  (ficha-vereador [this ente-id id data]
    (transacao this ente-id
      (fn [tx]
        (when-let [v (vereador/buscar tx ente-id id)]
          (let [m (vereador/mandato-vigente tx ente-id id data)
                leg (when (:legislatura-id m) (estrutura/buscar-legislatura tx (:legislatura-id m)))
                cs (comissao/comissoes-do-vereador tx ente-id id data)]
            {:vereador v :mandato m :legislatura leg :comissoes cs})))))
  (vereador-por-identidade [this ente-id ident] (transacao this ente-id #(vereador/por-identidade % ente-id ident)))
  (criar-mandato! [this ente-id m] (transacao this ente-id #(vereador/inserir-mandato! % m)))
  (mudar-estado-mandato! [this ente-id m] (transacao this ente-id #(vereador/mudar-estado! % ente-id m)))
  (mandatos-do-vereador [this ente-id ver-id] (transacao this ente-id #(vereador/mandatos-do-vereador % ente-id ver-id)))
  (criar-licenca! [this ente-id l] (transacao this ente-id #(vereador/inserir-licenca! % l)))
  (criar-suplencia! [this ente-id s] (transacao this ente-id #(vereador/inserir-suplencia! % s)))
  (atualizar-vereador! [this ente-id id campos]
    (transacao this ente-id #(vereador/atualizar! % ente-id id campos)))
  (ligar-identidade! [this ente-id id identidade-id]
    ;; 23505 do indice UNIQUE parcial (ente_id,identidade_id) WHERE identidade_id IS NOT NULL -> conflito
    ;; de dominio (409, nunca 500) — mesmo predicado 23505 de participacao/interpor-recurso! e
    ;; legislativo/registrar-voto!.
    (try
      (transacao this ente-id #(vereador/ligar-identidade! % ente-id id identidade-id))
      (catch PSQLException e
        (if (= "23505" (.getSQLState e))
          (throw (ex-info "identidade ja vinculada a outro vereador nesta Casa"
                          {:tipo :conflito/identidade-ja-vinculada :id id :identidade-id identidade-id}))
          (throw e)))))
  (registrar-mandato! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (cond
          (nil? (vereador/buscar tx ente-id (:vereador-id m)))                 nil
          (nil? (estrutura/buscar-legislatura tx (:legislatura-id m)))         nil
          (vereador/mandato-sobreposto? tx ente-id (:vereador-id m)
                                        (:vigencia-inicio m) (:vigencia-fim m))
          (throw (ex-info "mandato vigente sobreposto" {:tipo :conflito/mandato-sobreposto}))
          :else (do (vereador/inserir-mandato! tx m) {:id (:id m)})))))
  (registrar-licenca! [this ente-id vereador-id l data]
    (transacao this ente-id
      (fn [tx]
        (if (nil? (vereador/buscar tx ente-id vereador-id))
          nil
          (if-let [mv (vereador/mandato-vigente-de-vereador tx ente-id vereador-id data)]
            (do (vereador/inserir-licenca! tx (assoc l :mandato-id (:id mv)))
                (vereador/mudar-estado! tx ente-id {:id (:id mv) :estado "licenciado"})
                {:id (:id l)})
            (throw (ex-info "sem mandato vigente para licenciar" {:tipo :conflito/sem-mandato-vigente})))))))
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
