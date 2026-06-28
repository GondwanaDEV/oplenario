(ns oplenario.motor.components.repositorio
  "Component de PERSISTENCIA do motor — o banco DISPONIBILIZADO como Stuart Sierra Component (RepoMotor,
  ADR-0001 §3-bis) sobre as 5 tabelas estaticas do schema `motor` (§22.7.6 Eixo B). O motor e' BIBLIOTECA
  (kernel/motor nunca importam modulo, §22.10) e e' DONO do schema `motor` — logo le o proprio schema
  direto, sem violar fronteira.

  Esfera por tabela:
    - 4 de DOMINIO (template_compliance/prazo_dominio_vigente/calendario_feriado/registry_catalogo_versao):
      sem ente_id, sem RLS -> rodam sobre o `:ds` direto.
    - 1 de TENANT (compliance_regra_tenant): FORCE RLS retrofitada em F1.0 -> roda via com-tenant*
      (a `tx` ja carrega o GUC app.ente_id; a policy isola).
  O `db/` (HoneySQL sobre `tx`) e' a IMPL atras deste protocolo; o caller depende DESTE Component."
  (:require [oplenario.kernel.tenancy :as tenancy]
            [oplenario.motor.db.calendario :as calendario]
            [oplenario.motor.db.prazo-vigente :as prazo]
            [oplenario.motor.db.regra-tenant :as regra-binding]
            [oplenario.motor.db.registry-versao :as registry]
            [oplenario.motor.db.template-compliance :as template]))

(defprotocol RepoMotor
  ;; --- DOMINIO (sobre :ds) ---
  (criar-template! [this template])
  (templates-vigentes [this dominio chave-dominio] "DEFINICOES 'vigente' do escopo (dominio, chave_dominio).")
  (template-por-chave-versao [this chave-template versao])
  (criar-prazo! [this prazo])
  (prazo-vigente [this dominio chave-dominio tipo-prazo chave-periodo] "{:data-limite :fonte} ou nil ([GAP]).")
  (criar-feriado! [this feriado])
  (feriados [this jurisdicao municipio-id] "Set de LocalDate.")
  (registrar-versao-catalogo! [this versao])
  (versao-catalogo [this versao])
  ;; --- TENANT (via com-tenant*) ---
  (transacao [this ente-id f] "Roda (f tx) na tx isolada do tenant — compoe acoes tenant atomicamente (nome alinhado a RepoCadastros).")
  (criar-binding! [this ente-id binding])
  (binding-do-ente [this ente-id template-chave])
  (bindings-ativos [this ente-id]))

(defrecord RepoMotorPg [datasource]
  RepoMotor
  ;; dominio: :ds direto (motor e' dono do schema; sem tenant)
  (criar-template! [_ t] (template/inserir! (:ds datasource) t))
  (templates-vigentes [_ dom chave] (template/vigentes-por-dominio (:ds datasource) dom chave))
  (template-por-chave-versao [_ chave versao] (template/por-chave-versao (:ds datasource) chave versao))
  (criar-prazo! [_ p] (prazo/inserir! (:ds datasource) p))
  (prazo-vigente [_ dom chave tipo periodo] (prazo/vigente (:ds datasource) dom chave tipo periodo))
  (criar-feriado! [_ f] (calendario/inserir! (:ds datasource) f))
  (feriados [_ jur mun] (calendario/feriados (:ds datasource) jur mun))
  (registrar-versao-catalogo! [_ v] (registry/inserir! (:ds datasource) v))
  (versao-catalogo [_ v] (registry/por-versao (:ds datasource) v))
  ;; tenant: com-tenant* (RLS)
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  (criar-binding! [this ente-id b] (transacao this ente-id #(regra-binding/inserir! % b)))
  (binding-do-ente [this ente-id chave] (transacao this ente-id #(regra-binding/por-ente-template % ente-id chave)))
  (bindings-ativos [this ente-id] (transacao this ente-id #(regra-binding/ativos-do-ente % ente-id))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoMotorPg nil))
