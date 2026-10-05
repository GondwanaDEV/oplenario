(ns oplenario.catalogo-db-test
  "INTEGRACAO (PG real): ADR-0009 / ADR-0010 — as ferramentas do catalogo executando contra os repositorios de
  verdade, pelo caminho que o agente usa: a pessoa invoca o agente, o core emite a credencial delegada, a chamada
  resolve o ator AGORA (pessoa + `:via`) e executa. A materia achada pelo numero que uma pessoa fala, o tenant
  respeitado, a pauta da sessao da vez — e o TESTE DE VAZAMENTO na dimensao agente."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.catalogo :as catalogo]
            [oplenario.config :as config]
            [oplenario.identidade.autenticacao :as auten]
            [oplenario.identidade.components.repositorio :as repo-id]
            [oplenario.identidade.db.identidade :as id]
            [oplenario.identidade.db.vinculo :as vinc]
            [oplenario.integracao-ia.components.repositorio :as repo-ia]
            [oplenario.kernel.canonico]
            [oplenario.kernel.catalogo :as kcatalogo]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.migracao :as migracao]
            [oplenario.sessoes.components.repositorio :as repo-sessoes]
            [oplenario.sessoes.db.sessao :as sessao])
  (:import (java.time Instant)))

(def ^:dynamic *ds* nil)

(use-fixtures :once
  (fn [t]
    (let [c (component/start (datasource/datasource (config/carregar)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c)] (try (t) (finally (component/stop c)))))))

(defn- repo-identidade [] (assoc (repo-id/repositorio) :datasource {:ds *ds*}))
(defn- repo-integracao [] (repo-ia/map->RepoIntegracaoIAPg {:datasource {:ds *ds*}}))

(defn- deps []
  {:repo-legislativo (repo-leg/map->RepoLegislativoPg {:datasource {:ds *ds*}})
   :repo-sessoes (repo-sessoes/map->RepoSessoesPg {:datasource {:ds *ds*}})
   :registrar-chamada (catalogo/registrador (repo-integracao))})

(defn- dv [ds] (let [r (mod (reduce + (map * ds (range (inc (count ds)) 1 -1))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- cpf-valido [] (let [b (vec (repeatedly 9 #(rand-int 10))) d1 (dv b)] (apply str (concat b [d1 (dv (conj b d1))]))))

(defn- pessoa!
  "Uma pessoa com vinculo ativo e o `papel` na Casa `ente`. Devolve {:identidade-id :vinculo-id}."
  [ente papel]
  (let [iid (random-uuid) vid (random-uuid)]
    (id/inserir! *ds* {:id iid :cpf (cpf-valido) :nome "Pessoa da Casa"})
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (vinc/criar! tx {:id vid :ente-id ente :identidade-id iid
                         :tipo (if (= papel "vereador") "vereador" "servidor")})
        (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id ente :identidade-id iid :papel papel})))
    {:identidade-id iid :vinculo-id vid}))

(defn- credencial!
  "A pessoa invoca o agente numa tela: o core emite a credencial delegada da execucao."
  [ente {:keys [identidade-id]} publico & {:keys [classes] :or {classes #{:leitura}}}]
  (let [ator (auten/resolver-sessao (repo-identidade) {:identidade-id identidade-id :ente-id ente})]
    (auten/emitir-credencial-agente! (repo-identidade) ator {:agente "assistente-da-casa" :publico publico
                                                             :classes classes})))

(defn- ator-agente [{:keys [credencial]}] (auten/resolver-agente (repo-identidade) credencial))

(defn- agente-de [ente papel publico] (ator-agente (credencial! ente (pessoa! ente papel) publico)))

(defn- proposicao! [ente ementa]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (proposicao/protocolar! tx {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                                         :uf "CE" :municipio-nome "Fortaleza" :ementa ementa
                                         :autor-texto "Ver. Ana"}))))

(defn- sessao! [ente quando & {:keys [tipo] :or {tipo "ordinaria"}}]
  (tenancy/com-tenant* *ds* ente
    (fn [tx] (:id (sessao/agendar! tx {:id (random-uuid) :ente-id ente :sessao-legislativa-id (random-uuid)
                                       :tipo-sessao tipo :modalidade "presencial"
                                       :agendada-para (Instant/parse quando)})))))

(defn- erro [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:tipo :razao]))))

;; ---------- a credencial delegada ----------

(deftest a-credencial-carrega-a-pessoa-e-o-agente
  (let [ente (random-uuid)
        p (pessoa! ente "secretario")
        c (credencial! ente p :secretaria :classes #{:leitura :rascunho})
        ator (ator-agente c)]
    (is (= (:identidade-id p) (:identidade-id ator)) "o sujeito e' a pessoa")
    (is (= #{"secretario"} (:papeis ator)) "com os papeis que ela tem agora")
    (is (= {:agente "assistente-da-casa" :execucao-id (:execucao-id c) :publico :secretaria
            :classes #{:leitura :rascunho} :institucional? false}
           (:via ator)))
    (testing "revogada, nao abre mais nada"
      (repo-id/revogar-credencial-agente! (repo-identidade) (:execucao-id c))
      (is (nil? (ator-agente c))))
    (testing "segredo desconhecido"
      (is (nil? (auten/resolver-agente (repo-identidade) "nao-e-uma-credencial"))))))

(deftest vinculo-suspenso-derruba-o-agente-na-hora
  (let [ente (random-uuid)
        p (pessoa! ente "secretario")
        c (credencial! ente p :secretaria)]
    (is (some? (ator-agente c)))
    (tenancy/com-tenant* *ds* ente #(vinc/mudar-estado! % (:vinculo-id p) "suspenso"))
    (is (nil? (ator-agente c)) "a permissao e' recalculada a cada chamada, nao congelada na emissao")))

(deftest credencial-expirada-nao-resolve
  (let [ente (random-uuid)
        {:keys [identidade-id]} (pessoa! ente "secretario")
        emitida (Instant/parse "2020-01-01T00:00:00Z")]
    (is (thrown? Exception
                 (repo-id/emitir-credencial-agente! (repo-identidade)
                                                    {:execucao-id (random-uuid) :ente-id ente
                                                     :identidade-id identidade-id :agente "assistente-da-casa"
                                                     :publico "secretaria" :classes ["leitura"] :expira-em emitida}))
        "prazo no passado nem entra (expira_em > emitida_em)")
    (let [c (credencial! ente {:identidade-id identidade-id} :secretaria)]
      (jdbc/execute! *ds* ["UPDATE identidade.credencial_agente SET emitida_em = now() - interval '2 hours',
                                 expira_em = now() - interval '1 hour' WHERE execucao_id = ?" (:execucao-id c)])
      (is (nil? (ator-agente c))))))

(deftest institucional-nunca-recebe-ato
  (is (thrown? Exception
               (repo-id/emitir-credencial-agente! (repo-identidade)
                                                  {:execucao-id (random-uuid) :ente-id (random-uuid)
                                                   :identidade-id nil :agente "conferencia-normativa"
                                                   :publico "institucional" :classes ["leitura" "ato"]
                                                   :expira-em (.plusSeconds (Instant/now) 60)}))))

;; ---------- as ferramentas ----------

(deftest situacao-da-materia-pelo-numero-que-a-pessoa-fala
  (let [ente (random-uuid)
        p (proposicao! ente "Dispoe sobre a merenda escolar.")
        pelo-numero {:tipo "projeto_lei" :sequencial (:sequencial p) :ano 2026}
        secretaria (agente-de ente "secretario" :secretaria)]
    (testing "pelo numero (como o JSON do agente chega) e pelo id dao a mesma materia"
      (let [r (catalogo/executar! (deps) secretaria "situacao_da_materia" pelo-numero)]
        (is (= (str (:id p)) (:id r)))
        (is (= "Dispoe sobre a merenda escolar." (:ementa r))))
      (is (= (str (:id p)) (:id (catalogo/executar! (deps) (agente-de ente "vereador" :vereador) "situacao_da_materia"
                                                    {:proposicao-id (str (:id p))})))))
    (testing "sem id nem numero completo e' entrada invalida"
      (is (= :validacao/invalido
             (:tipo (erro #(catalogo/executar! (deps) secretaria "situacao_da_materia"
                                               {:tipo "projeto_lei" :ano 2026}))))))
    (testing "pessoa sem papel que a ferramenta atende e' negada"
      (is (= :autorizacao/negado
             (:tipo (erro #(catalogo/executar! (deps) (agente-de ente "admin_ente" :secretaria)
                                               "situacao_da_materia" pelo-numero))))))))

(deftest tramitacao-da-materia
  (let [ente (random-uuid)
        p (proposicao! ente "Dispoe sobre iluminacao publica.")
        r (catalogo/executar! (deps) (agente-de ente "secretario" :secretaria) "tramitacao_da_materia"
                              {:proposicao-id (str (:id p))})]
    (is (= (str (:id p)) (:proposicao-id r)))
    (is (vector? (:historico r)))))

(deftest pauta-da-sessao-da-vez
  (let [ente (random-uuid)
        depois (sessao! ente "2026-12-10T13:00:00Z")
        antes (sessao! ente "2026-12-03T13:00:00Z")
        vereador (agente-de ente "vereador" :vereador)]
    (testing "sem sessao informada, a proxima agendada"
      (is (= (str antes) (:sessao-id (catalogo/executar! (deps) vereador "pauta_da_sessao" {})))))
    (testing "com sessao informada, aquela"
      (is (= (str depois) (:sessao-id (catalogo/executar! (deps) vereador "pauta_da_sessao"
                                                          {:sessao-id (str depois)})))))
    (testing "sessao secreta nunca vai ao agente (a IA): nem como a da vez, nem pedida pelo id"
      (let [casa (random-uuid)
            secreta (sessao! casa "2026-11-01T13:00:00Z" :tipo "secreta")
            publica (sessao! casa "2026-11-20T13:00:00Z")
            v (agente-de casa "vereador" :vereador)]
        (is (= (str publica) (:sessao-id (catalogo/executar! (deps) v "pauta_da_sessao" {}))))
        (is (nil? (catalogo/executar! (deps) (agente-de casa "secretario" :secretaria) "pauta_da_sessao"
                                      {:sessao-id (str secreta)})))))
    (testing "sessao secreta nunca vai ao agente (a IA): nem como a da vez, nem pedida pelo id"
      (let [casa (random-uuid)
            secreta (sessao! casa "2026-11-01T13:00:00Z" :tipo "secreta")
            publica (sessao! casa "2026-11-20T13:00:00Z")]
        (is (= (str publica) (:sessao-id (catalogo/executar! (deps) (agente-de casa "vereador" :vereador)
                                                             "pauta_da_sessao" {}))))
        (is (nil? (catalogo/executar! (deps) (agente-de casa "secretario" :secretaria) "pauta_da_sessao"
                                      {:sessao-id (str secreta)})))))
    (testing "Casa sem sessao nenhuma: nada a mostrar"
      (is (nil? (catalogo/executar! (deps) (agente-de (random-uuid) "vereador" :vereador) "pauta_da_sessao" {}))))))

(deftest ferramentas-e-o-que-fica-fora
  (let [ente (random-uuid)
        secretaria (agente-de ente "secretario" :secretaria)]
    ;; a credencial de `agente-de` so' concede leitura: as tres de ATO da secretaria (pedir parecer juridico, encaminhar
    ;; as comissoes, designar relator) nem aparecem — ver `juridico_catalogo_test`
    ;; ADR-0020: as tres leituras de comunicado (o envio e' ato, fora desta credencial so' de leitura)
    ;; ADR-0021: as duas leituras das contas (registrar e notificar sao ato)
    (is (= ["ata_da_sessao" "buscar_dispositivos" "comissoes_da_casa" "contas_da_casa" "ler_caixa" "ler_comunicado"
            "ler_dispositivo" "ler_leitura_do_comunicado" "pareceres_juridicos_da_materia"
            "pauta_da_sessao" "prestacao_de_contas" "situacao_da_materia" "tramitacao_da_materia" "vereadores_da_casa"]
           (map :nome (catalogo/ferramentas secretaria))))
    (is (empty? (catalogo/ferramentas (agente-de ente "admin_ente" :secretaria)))
        "o conjunto do publico nao da' a ninguem o que o papel dele nao alcanca")
    (is (= :validacao/ferramenta-desconhecida
           (:tipo (erro #(catalogo/executar! (deps) secretaria "apagar_tudo" {})))))
    (is (= :validacao/ferramenta-desconhecida
           (:tipo (erro #(catalogo/executar! (deps) (agente-de ente "secretario" :cidadao) "pauta_da_sessao" {}))))
        "credencial do publico cidadao nao ve ferramenta da secretaria")
    (is (= :autorizacao/negado
           (:tipo (erro #(catalogo/executar! (deps) {:identidade-id (random-uuid) :ente-id ente :papeis #{"secretario"}}
                                             "pauta_da_sessao" {}))))
        "o catalogo so' atende ator de agente (credencial delegada)")))

;; ---------- TESTE DE VAZAMENTO, dimensao agente ----------

(deftest vazamento-o-agente-nunca-sai-da-casa-da-pessoa
  (let [a (random-uuid) b (random-uuid)
        materia-b (proposicao! b "Materia da outra Casa.")
        sessao-b (sessao! b "2026-12-03T13:00:00Z")
        agente-a (agente-de a "secretario" :secretaria)]
    (testing "o id de uma materia de outra Casa nao existe para o agente"
      (is (nil? (catalogo/executar! (deps) agente-a "situacao_da_materia" {:proposicao-id (str (:id materia-b))})))
      (is (nil? (catalogo/executar! (deps) agente-a "tramitacao_da_materia" {:proposicao-id (str (:id materia-b))})))
      (is (nil? (catalogo/executar! (deps) agente-a "situacao_da_materia"
                                    {:tipo "projeto_lei" :sequencial (:sequencial materia-b) :ano 2026}))))
    (testing "nem a sessao de outra Casa"
      (is (nil? (catalogo/executar! (deps) agente-a "pauta_da_sessao" {:sessao-id (str sessao-b)})))
      (is (nil? (catalogo/executar! (deps) agente-a "pauta_da_sessao" {}))))
    (testing "a credencial emitida na Casa A so' vale na Casa A, mesmo que a pessoa tenha vinculo na B"
      (let [p (pessoa! a "secretario")
            c (credencial! a p :secretaria)]
        (tenancy/com-tenant* *ds* b
          (fn [tx]
            (vinc/criar! tx {:id (random-uuid) :ente-id b :identidade-id (:identidade-id p) :tipo "servidor"})
            (vinc/adicionar-papel! tx {:id (random-uuid) :ente-id b :identidade-id (:identidade-id p)
                                       :papel "secretario"})))
        (is (= a (:ente-id (ator-agente c))) "o ente vem da credencial, nunca da chamada")))))

;; ---------- AUDIT das escritas por agente (Eixo 3.5) ----------

(def ^:private rascunho-de-teste
  (kcatalogo/entrada
   {:nome "rascunho_de_teste"
    :descricao "Entrada so' de teste: finge escrever um rascunho, para exercitar o audit das escritas por agente."
    :classe :rascunho :papeis #{"secretario"}
    :entrada [:map [:n :int]] :saida [:map [:n :int]] :rotas #{}
    :executar (fn [_ _ {:keys [n]}] {:n n})}))

(deftest escrita-por-agente-fica-registrada-na-casa
  (let [ente (random-uuid)
        c (credencial! ente (pessoa! ente "secretario") :secretaria :classes #{:leitura :rascunho})
        ator (ator-agente c)]
    (kcatalogo/executar rascunho-de-teste (deps) ator {:n 1})
    (erro #(kcatalogo/executar rascunho-de-teste (deps) ator {:n "x"}))
    (let [linhas (repo-ia/chamadas-da-execucao (repo-integracao) ente (:execucao-id c))]
      (is (= [["rascunho_de_teste" "rascunho" "ok"] ["rascunho_de_teste" "rascunho" "invalido"]]
             (map (juxt :ferramenta :classe :desfecho) linhas)))
      (is (every? #(= (:identidade-id ator) (:identidade-id %)) linhas) "pessoa + agente + execucao")
      (is (every? #(= "assistente-da-casa" (:agente %)) linhas)))
    (is (empty? (repo-ia/chamadas-da-execucao (repo-integracao) (random-uuid) (:execucao-id c)))
        "o registro e' da Casa (RLS)")))

(deftest toda-chamada-de-agente-vai-ao-audit-com-o-hash-da-saida
  ;; ADR-0024 item 2: a LEITURA tambem entra no audit, com o SHA-256 do JSON canonico da saida — prova o que a IA viu
  (let [ente (random-uuid)
        p (proposicao! ente "Merenda escolar")
        agente (agente-de ente "secretario" :secretaria)
        saida (catalogo/executar! (deps) agente "situacao_da_materia" {:proposicao-id (str (:id p))})
        [c] (repo-ia/chamadas-da-execucao (repo-integracao) ente (get-in agente [:via :execucao-id]))]
    (is (= ["situacao_da_materia" "leitura" "ok"] ((juxt :ferramenta :classe :desfecho) c)))
    (is (= (oplenario.kernel.canonico/sha256 saida) (:resultado-sha256 c)))
    (testing "nao achou: entra sem hash"
      (let [a (agente-de ente "secretario" :secretaria)]
        (is (nil? (catalogo/executar! (deps) a "situacao_da_materia" {:proposicao-id (str (random-uuid))})))
        (is (= [["nao_encontrado" nil]]
               (map (juxt :desfecho :resultado-sha256)
                    (repo-ia/chamadas-da-execucao (repo-integracao) ente (get-in a [:via :execucao-id])))))))
    (testing "sem o seam de audit, a chamada de agente nao roda (fail-closed)"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sem registro de audit"
                            (catalogo/executar! (dissoc (deps) :registrar-chamada) agente "situacao_da_materia"
                                                {:proposicao-id (str (:id p))}))))))
