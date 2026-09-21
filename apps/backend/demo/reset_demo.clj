(ns reset-demo
  "Reset DESTRUTIVO do banco da demo — SO' homolog, SO' via workflow com dupla confirmacao. Dropa todos
  os schemas do APP (todos menos os de sistema e `public`) + o log do Migratus (`public.schema_migrations`,
  a tabela default do :store :database), deixando o banco no MESMO estado de um banco virgem. Depois
  `semear-tudo!` (que roda `migracao/migrar!` ANTES de semear — e ja' e' provado tolerar 'banco vazio')
  reconstroi o schema do zero e semeia uma demo LIMPA.

  POR QUE PRECISA EXISTIR: as 4 sementes sao idempotentes POR PULAR (gate de existencia). Um re-seed
  sobre um banco ja' semeado NUNCA remove dado velho — artefatos de teste, pareceres ja' assinados,
  sessoes descartaveis, itens de pauta extras. Para uma demo pristina antes de uma apresentacao, o unico
  caminho e' zerar e re-semear. O banco `oplenario` e' dedicado ao app (a conexao aponta pra ele), entao
  dropar todos os schemas nao-sistema deste banco e' o slate limpo correto — nao ha' outro inquilino aqui.

  `public` NAO e' dropado (pode abrigar extensoes como pgcrypto); so' a sua tabela de controle do Migratus
  e' removida, o que forca `migrar!` a re-aplicar TODAS as migrations (que recriam os schemas via
  CREATE SCHEMA IF NOT EXISTS)."
  (:refer-clojure :exclude [reset!])
  (:require [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.config :as config]
            [oplenario.sistema :as sistema]))

(defn reset!
  "-X entrypoint. `_` = kwargs do -X (nao usado). Boota o sistema so' pelo datasource, dropa os schemas
  do app + o log do Migratus, para no finally."
  [_]
  (let [sys (component/start (sistema/novo-sistema (config/carregar)))
        ds  (:ds (:datasource sys))]
    (try
      ;; `(comp val first)`: pega o VALOR da unica coluna de cada linha independentemente de como o
      ;; next.jdbc nomeia a chave (qualificada por tabela/alias) — antes eu lia `:schema_name` e vinha nil,
      ;; gerando `DROP SCHEMA "" CASCADE` (identificador vazio) e um erro na 1a iteracao. Guarda extra:
      ;; remove nil/branco antes de montar o DDL.
      (let [schemas (->> (jdbc/execute! ds
                           ["SELECT nspname FROM pg_namespace
                             WHERE nspname NOT LIKE 'pg\\_%' AND nspname NOT IN ('information_schema','public')"])
                         (map (comp val first))
                         (remove str/blank?))]
        ;; ATOMICO (DDL do Postgres faz rollback): ou dropa TODOS os schemas + o log do Migratus, ou
        ;; NENHUM. Sem a tx, um DROP que falhasse no meio (ex.: schema de outro dono) deixaria o homolog
        ;; PARCIALMENTE dropado — um estado pior que o de partida. Com a tx, uma falha preserva o banco.
        (jdbc/with-transaction [tx ds]
          (doseq [s schemas]
            (println "DROP SCHEMA" s "CASCADE")
            (jdbc/execute! tx [(str "DROP SCHEMA IF EXISTS \"" s "\" CASCADE")]))
          ;; log do Migratus (:store :database, tabela default `schema_migrations` no schema default/public):
          ;; sem remover isto, `migrar!` acha que tudo ja' rodou e NAO recria os schemas que acabamos de dropar.
          (jdbc/execute! tx ["DROP TABLE IF EXISTS public.schema_migrations CASCADE"]))
        (println "==> reset! OK — schemas dropados:" (count schemas)
                 (pr-str (vec schemas)) "+ log do Migratus. Banco pronto p/ semear-tudo! do zero."))
      (finally (component/stop sys)))))
