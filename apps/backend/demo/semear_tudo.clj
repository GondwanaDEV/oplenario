(ns semear-tudo
  "Orquestrador da semente NARRATIVA da demo (plano `docs/superpowers/plans/2026-09-07-prontidao-de-
  apresentacao.md`, Task 0.7) — chama, na ordem OBRIGATORIA, as 4 sementes que compartilham a MESMA
  Casa: `casa/semear!` -> `acervo/semear!` -> `sessoes/semear!` -> `participacao/semear!`. A ordem e'
  obrigatoria porque cada semente DEPENDE da anterior ja ter rodado (`acervo` precisa do roster da
  `casa`; `sessoes` precisa das proposicoes do `acervo`; `participacao` precisa dos comentarios reais
  do `acervo` e das identidades da `casa` — ver o cabecalho de cada ns).

  Cada uma das 4 e' idempotente POR SI (gate proprio, documentado em cada ns — ver `casa/ja-semeada?`,
  o gate de `template-do-rito` em `acervo`, o gate de `id-encerrada` em `sessoes`, o gate de
  `id-pedido-aberto` em `participacao`): rodar este orquestrador duas vezes seguidas RELE em vez de
  duplicar. Este ns nao adiciona gate proprio algum — so' sequencia as 4 chamadas sobre o MESMO sistema
  Component booted (mesmo contrato de `casa/semear!`: `sistema` e' `(component/start (oplenario.sistema/
  novo-sistema (config/carregar)))`, o unico padrao de boot de sistema completo do repo, ja usado por
  `test/integration/oplenario/demo/*_test.clj`).

  Chamado via `clojure -X` (mesmo padrao de `seed-demo/base` em `seed_demo.clj`, documentado no README de
  `e2e/semear.sh`) pelo `demo/semear-tudo.sh` na raiz do projeto — nao tem alias proprio em `deps.edn`;
  o `-Sdeps` inline do script resolve `:extra-paths [\"demo\"]`, deixando `deps.edn` intocado."
  (:require [acervo]
            [casa]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [oplenario.config :as config]
            [oplenario.migracao :as migracao]
            [oplenario.sistema :as sistema]
            [participacao]
            [sessoes]))

(defn semear-tudo!
  "Ponto de entrada do `-X`. `_` e' o mapa de kwargs do `-X` (nao usado — sem parametros hoje). Boota o
  sistema, MIGRA (idempotente — mesmo padrao de `with-sistema` em `casa_test.clj`; garante o schema em
  pe mesmo se este container for o primeiro a tocar um banco vazio) e chama as 4 sementes em ordem,
  parando o sistema no `finally` mesmo se uma delas lancar.

  Imprime um resumo de cada etapa em stdout (o script shell so' precisa do artefato `demo-ids.edn` p/ a
  barreira de projecao — este log e' so' para o operador ver o que rodou)."
  [_]
  (let [sys (component/start (sistema/novo-sistema (config/carregar)))]
    (try
      (migracao/migrar! (:ds (:datasource sys)))
      (log/info "semear-tudo!: sistema booted e migrado — semeando a Casa")
      (let [{:keys [ente] :as casa-r} (casa/semear! sys)]
        (println "==> casa:" (pr-str {:ente ente :legislatura (:legislatura casa-r)
                                       :n-vereadores (count (:vereadores casa-r))}))
        (let [acervo-r (acervo/semear! sys ente)]
          (println "==> acervo:" (pr-str acervo-r))
          (let [sessoes-r (sessoes/semear! sys ente)]
            (println "==> sessoes:" (pr-str sessoes-r))
            (let [participacao-r (participacao/semear! sys ente)]
              (println "==> participacao:"
                (pr-str (-> participacao-r
                            (dissoc :comentarios)
                            (assoc :n-comentarios (count (:comentarios participacao-r))))))
              (println "==> semear-tudo! OK — ente" ente)
              {:ente ente :casa casa-r :acervo acervo-r :sessoes sessoes-r :participacao participacao-r}))))
      (finally (component/stop sys)))))
