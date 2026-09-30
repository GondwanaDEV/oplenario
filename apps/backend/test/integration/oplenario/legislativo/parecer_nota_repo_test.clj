(ns oplenario.legislativo.parecer-nota-repo-test
  "INTEGRACAO (PG real): a fatia 2a da ADR-0019 pelo Repo-Component — a NOTA TECNICA da IA como RASCUNHO do parecer
  juridico (Eixo 5) e o parametro que ANTECIPA o parecer ao portal (Eixo 4). Prova: a tx unica (pedido + rascunho +
  origem + nota aproveitada), o reaproveitamento do pedido da secretaria, os 409 sem efeito colateral, o texto sem as
  marcas de citacao e SEM conclusao, a origem que sobrevive a assinatura sem mexer na imutabilidade, a corrida de dois
  advogados, a RLS entre Casas e o portal antecipado x nao antecipado (a consulta avulsa nunca vai)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.components.repositorio-juridico :as juridico]
            [oplenario.legislativo.components.repositorio-nota-juridica :as nota-jur]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf]))

(def ^:dynamic *ds* nil)
(def ^:dynamic *repo* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *repo* (repo/->RepoLegislativoPg c (outbox/bus))]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(def ^:private assinante {:por (random-uuid) :nome "Paulo Bezerra" :oab "CE 12345" :qualificacao "efetivo"})

(defn- protocolar! [ente]
  (:id (repo/protocolar! *repo* ente {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026 :uf "CE"
                                      :municipio-nome "Fortaleza" :ementa "Dispoe sobre hortas comunitarias"})))

(def ^:private texto-da-nota
  (str "O projeto institui o programa de hortas. [[materia:x | institui o programa de hortas]]\n\n"
       "Aplica-se a Lei Organica, art. 25. [[norma:n#art25 | compete a Camara legislar]]"))

(defn- nota! [ente pid]
  (:id (repo/registrar-nota-tecnica! *repo* ente
         {:proposicao-id pid :agente "conferencia-normativa" :execucao-id (random-uuid) :texto texto-da-nota
          :citacoes [{:fonte-id "materia:x" :trecho "institui o programa de hortas" :status "conferida" :rotulo "PL 1/2026"}]
          :paragrafos-sem-fonte [] :incerteza "normal" :motivos-incerteza [] :modelo-llm-id "fake"})))

(defn- pedidos-da-materia [ente pid]
  (jdbc/execute! *ds* ["SELECT id, origem, pedido_por, estado FROM legislativo.pedido_parecer_juridico
                         WHERE ente_id = ? AND proposicao_id = ?" ente pid]))

(defn- contar [sql & args] (:n (jdbc/execute-one! *ds* (into [sql] args))))

(deftest usar-a-nota-abre-o-pedido-e-o-rascunho-numa-tx
  (let [ente (random-uuid) adv (random-uuid) pid (protocolar! ente) nid (nota! ente pid)
        {:keys [pedido erro]} (nota-jur/usar-nota-como-rascunho! *repo* ente nid adv)
        par (:parecer pedido)]
    (is (nil? erro))
    (testing "o pedido nasce da nota, em nome do advogado que clicou"
      (is (= ["nota_tecnica" adv "pendente" pid] ((juxt :origem :pedido-por :estado :proposicao-id) pedido))))
    (testing "o rascunho: texto da nota SEM as marcas de citacao, relatorio neutro, SEM conclusao"
      (is (= "rascunho" (:estado par)))
      (is (nil? (:conclusao par)) "quem decide a conclusao e' o advogado")
      (is (not (str/includes? (:fundamentacao par) "[[")))
      (is (str/includes? (:fundamentacao par) "institui o programa de hortas"))
      (is (str/includes? (:relatorio par) "nota técnica da IA")))
    (testing "a origem e' registrada no rascunho"
      (is (= "nota_tecnica" (:origem-rascunho par))))
    (testing "a nota fica aproveitada, pelo advogado, com o mesmo texto"
      (let [n (repo/nota-tecnica *repo* ente nid)]
        (is (= ["aproveitada" adv] ((juxt :estado :decidida-por) n)))
        (is (= (:fundamentacao par) (:texto-final n)))))
    (testing "nao e' parecer: nao ha' assinatura nem numero"
      (is (nil? (:numero par)))
      (is (nil? (:assinatura-nome par))))))

(deftest reaproveita-o-pedido-pendente-da-secretaria
  (let [ente (random-uuid) adv (random-uuid) sec (random-uuid) pid (protocolar! ente) nid (nota! ente pid)
        ped (juridico/criar-pedido-juridico! *repo* ente {:proposicao-id pid :assunto "Analise pedida pela Presidencia"
                                                          :origem "secretaria" :pedido-por sec :em-nome-de "Presidência"})
        {:keys [pedido]} (nota-jur/usar-nota-como-rascunho! *repo* ente nid adv)]
    (is (= (:id ped) (:id pedido)) "o mesmo pedido, nao um segundo")
    (is (= ["secretaria" sec "Presidência"] ((juxt :origem :pedido-por :em-nome-de) pedido))
        "o pedido segue sendo da secretaria; so' o rascunho vem da nota")
    (is (= 1 (count (pedidos-da-materia ente pid))))
    (is (= "nota_tecnica" (get-in pedido [:parecer :origem-rascunho])))))

(deftest pedido-com-rascunho-em-curso-da-409-sem-efeito-colateral
  (let [ente (random-uuid) adv (random-uuid) pid (protocolar! ente) nid (nota! ente pid)
        ped (juridico/criar-pedido-juridico! *repo* ente {:proposicao-id pid :assunto "Analise da materia"
                                                          :origem "secretaria" :pedido-por (random-uuid)})]
    (juridico/salvar-parecer-juridico! *repo* ente (:id ped) adv {:relatorio "O que o advogado ja' escreveu"
                                                                   :fundamentacao "" :conclusao nil})
    (is (= {:erro :ja-ha-rascunho} (nota-jur/usar-nota-como-rascunho! *repo* ente nid adv)))
    (testing "nada mudou: a nota segue pendente, o rascunho do advogado intacto, nenhum pedido novo"
      (is (= "pendente" (:estado (repo/nota-tecnica *repo* ente nid))))
      (is (= "O que o advogado ja' escreveu" (get-in (juridico/pedido-juridico *repo* ente (:id ped)) [:parecer :relatorio])))
      (is (= 1 (count (pedidos-da-materia ente pid))))
      (is (= 1 (contar "SELECT count(*) AS n FROM legislativo.parecer_juridico WHERE ente_id = ?" ente))))))

(deftest com-dois-pedidos-pendentes-usa-o-que-nao-tem-rascunho
  (let [ente (random-uuid) adv (random-uuid) pid (protocolar! ente) nid (nota! ente pid)
        a (juridico/criar-pedido-juridico! *repo* ente {:proposicao-id pid :assunto "Primeiro pedido" :origem "secretaria" :pedido-por adv})
        b (juridico/criar-pedido-juridico! *repo* ente {:proposicao-id pid :assunto "Segundo pedido" :origem "secretaria" :pedido-por adv})]
    (juridico/salvar-parecer-juridico! *repo* ente (:id a) adv {:relatorio "R" :fundamentacao "" :conclusao nil})
    (is (= (:id b) (:id (:pedido (nota-jur/usar-nota-como-rascunho! *repo* ente nid adv)))))))

(deftest nota-decidida-ou-inexistente
  (let [ente (random-uuid) outra (random-uuid) adv (random-uuid) pid (protocolar! ente)]
    (testing "nota ja' aproveitada pela secretaria -> nota-decidida, e nao abre pedido"
      (let [nid (nota! ente pid)]
        (repo/decidir-nota-tecnica! *repo* ente nid {:estado "aproveitada" :texto-final "texto da secretaria" :decidida-por (random-uuid)})
        (is (= {:erro :nota-decidida} (nota-jur/usar-nota-como-rascunho! *repo* ente nid adv)))
        (is (empty? (pedidos-da-materia ente pid)))))
    (testing "nota descartada tambem"
      (let [pid2 (protocolar! ente) nid (nota! ente pid2)]
        (repo/decidir-nota-tecnica! *repo* ente nid {:estado "descartada" :decidida-por (random-uuid)})
        (is (= {:erro :nota-decidida} (nota-jur/usar-nota-como-rascunho! *repo* ente nid adv)))))
    (testing "usar duas vezes: a segunda e' nota-decidida"
      (let [pid3 (protocolar! ente) nid (nota! ente pid3)]
        (is (some? (:pedido (nota-jur/usar-nota-como-rascunho! *repo* ente nid adv))))
        (is (= {:erro :nota-decidida} (nota-jur/usar-nota-como-rascunho! *repo* ente nid adv)))))
    (testing "nota inexistente, ou de outra Casa (RLS) -> nao-encontrado"
      (is (= {:erro :nao-encontrado} (nota-jur/usar-nota-como-rascunho! *repo* ente (random-uuid) adv)))
      (let [pid4 (protocolar! ente) nid (nota! ente pid4)]
        (is (= {:erro :nao-encontrado} (nota-jur/usar-nota-como-rascunho! *repo* outra nid adv)))
        (is (= "pendente" (:estado (repo/nota-tecnica *repo* ente nid))) "a outra Casa nao mexeu na nota")))))

(deftest dois-advogados-ao-mesmo-tempo-so-um-leva-a-nota
  (let [ente (random-uuid) pid (protocolar! ente) nid (nota! ente pid)
        rs (mapv deref (mapv (fn [_] (future (nota-jur/usar-nota-como-rascunho! *repo* ente nid (random-uuid)))) (range 4)))]
    (is (= 1 (count (filter :pedido rs))) "so' um rascunho nasce")
    (is (= 3 (count (filter #(= :nota-decidida (:erro %)) rs))))
    (is (= 1 (contar "SELECT count(*) AS n FROM legislativo.parecer_juridico WHERE ente_id = ?" ente)))
    (is (= 1 (count (pedidos-da-materia ente pid))))))

(deftest a-origem-sobrevive-a-assinatura-sem-mexer-na-imutabilidade
  (let [ente (random-uuid) adv (random-uuid) pid (protocolar! ente) nid (nota! ente pid)
        {:keys [pedido]} (nota-jur/usar-nota-como-rascunho! *repo* ente nid adv)
        id (:id pedido)]
    (testing "sem conclusao nao assina: o advogado tem de decidir"
      (is (= {:erro :incompleto} (juridico/assinar-parecer-juridico! *repo* ente id assinante))))
    (testing "o advogado revisa, conclui e assina: quem assina e' ele, e a origem fica registrada"
      (juridico/salvar-parecer-juridico! *repo* ente id adv {:relatorio "Relatorio revisado" :fundamentacao "Fundamentacao revisada"
                                                              :conclusao "com_ressalvas"})
      (let [{:keys [pedido]} (juridico/assinar-parecer-juridico! *repo* ente id assinante)
            par (:parecer pedido)]
        (is (= ["assinado" "Paulo Bezerra" "com_ressalvas" "nota_tecnica"]
               ((juxt :estado :assinatura-nome :conclusao :origem-rascunho) par)))
        (is (= "Relatorio revisado" (:relatorio par)) "editar o rascunho nao apaga a origem")))
    (testing "assinado continua imutavel, origem inclusive"
      (is (thrown-with-msg? Exception #"imutabilidade"
            (jdbc/execute! *ds* ["UPDATE legislativo.parecer_juridico SET origem_rascunho = NULL, nota_tecnica_id = NULL WHERE pedido_id = ?" id]))))
    (testing "a ficha (pareceres assinados da materia) traz a origem"
      (is (= ["nota_tecnica"] (mapv :origem-rascunho (:pareceres (juridico/pareceres-juridicos-da-materia *repo* ente pid))))))
    (testing "a substituicao e' um texto novo do advogado: nasce sem origem de nota"
      (let [{:keys [pedido]} (juridico/substituir-parecer-juridico! *repo* ente id adv)]
        (is (nil? (get-in pedido [:parecer :origem-rascunho])))))))

(deftest o-banco-exige-origem-e-nota-juntas
  (let [ente (random-uuid) adv (random-uuid) pid (protocolar! ente)
        ped (juridico/criar-pedido-juridico! *repo* ente {:proposicao-id pid :assunto "Analise da materia" :origem "secretaria" :pedido-por adv})]
    (is (thrown? Exception
          (jdbc/with-transaction [tx *ds*]
            (jdbc/execute! tx ["SELECT set_config('app.ente_id', ?, true)" (str ente)])
            (jdbc/execute! tx ["INSERT INTO legislativo.parecer_juridico (ente_id, pedido_id, proposicao_id, autor_id, origem_rascunho)
                                VALUES (?, ?, ?, ?, 'nota_tecnica')" ente (:id ped) pid adv])))
        "origem sem a nota que a originou e' recusada")))

;; ---------------- o parametro: antecipar o portal ----------------

(def ^:private texto {:relatorio "Trata-se de projeto de lei." :fundamentacao "Art. 30, I, da CF." :conclusao "favoravel"})

(defn- assinar-parecer-da-materia! [ente pid]
  (let [autor (random-uuid)
        p (juridico/criar-pedido-juridico! *repo* ente {:proposicao-id pid :assunto "Analise da materia" :origem "secretaria" :pedido-por autor})]
    (juridico/salvar-parecer-juridico! *repo* ente (:id p) autor texto)
    (juridico/assinar-parecer-juridico! *repo* ente (:id p) assinante)
    p))

(deftest parametro-padrao-e-por-casa
  (let [a (random-uuid) b (random-uuid)]
    (is (= {:publicar-ao-assinar false} (nota-jur/parametros-parecer-juridico *repo* a)) "sem linha: so' depois da deliberacao")
    (is (= {:publicar-ao-assinar true}
           (nota-jur/salvar-parametros-parecer-juridico! *repo* a {:publicar-ao-assinar true :por (random-uuid)})))
    (is (= {:publicar-ao-assinar true} (nota-jur/parametros-parecer-juridico *repo* a)))
    (is (= {:publicar-ao-assinar false} (nota-jur/parametros-parecer-juridico *repo* b)) "a outra Casa nao mudou (RLS)")
    (is (= {:publicar-ao-assinar false}
           (nota-jur/salvar-parametros-parecer-juridico! *repo* a {:publicar-ao-assinar false :por (random-uuid)}))
        "desligar volta ao padrao (upsert, uma linha por Casa)")
    (is (= 1 (contar "SELECT count(*) AS n FROM legislativo.parametro_parecer_juridico WHERE ente_id = ?" a)))))

(deftest portal-antecipado-mostra-ao-assinar-e-nao-antecipado-espera-a-deliberacao
  (let [ente (random-uuid) pid (protocolar! ente)]
    (assinar-parecer-da-materia! ente pid)
    (testing "padrao: materia em curso, nada no portal"
      (is (empty? (juridico/pareceres-juridicos-publicos *repo* ente pid))))
    (testing "Casa que antecipou: o assinado vigente aparece ao assinar"
      (nota-jur/salvar-parametros-parecer-juridico! *repo* ente {:publicar-ao-assinar true :por (random-uuid)})
      (is (= ["favoravel"] (mapv :conclusao (juridico/pareceres-juridicos-publicos *repo* ente pid)))))
    (testing "antecipado: so' o vigente (o substituido nao)"
      (let [ped (first (juridico/pedidos-juridicos *repo* ente "atendido" 10))
            autor (random-uuid)]
        (juridico/substituir-parecer-juridico! *repo* ente (:id ped) autor)
        (is (= ["favoravel"] (mapv :conclusao (juridico/pareceres-juridicos-publicos *repo* ente pid)))
             "com o substituto ainda em rascunho, o vigente segue no portal")
        (juridico/salvar-parecer-juridico! *repo* ente (:id ped) autor (assoc texto :conclusao "contrario"))
        (juridico/assinar-parecer-juridico! *repo* ente (:id ped) assinante)
        (is (= ["contrario"] (mapv :conclusao (juridico/pareceres-juridicos-publicos *repo* ente pid))))))
    (testing "desligar volta a esperar a deliberacao"
      (nota-jur/salvar-parametros-parecer-juridico! *repo* ente {:publicar-ao-assinar false :por (random-uuid)})
      (is (empty? (juridico/pareceres-juridicos-publicos *repo* ente pid))))
    (testing "o rascunho nunca vai ao portal, antecipado ou nao"
      (nota-jur/salvar-parametros-parecer-juridico! *repo* ente {:publicar-ao-assinar true :por (random-uuid)})
      (let [pid2 (protocolar! ente)
            p (juridico/criar-pedido-juridico! *repo* ente {:proposicao-id pid2 :assunto "Outra materia" :origem "secretaria" :pedido-por (random-uuid)})]
        (juridico/salvar-parecer-juridico! *repo* ente (:id p) (random-uuid) texto)
        (is (empty? (juridico/pareceres-juridicos-publicos *repo* ente pid2)))))
    (testing "a consulta avulsa nunca vai ao portal, mesmo antecipado"
      (let [av (juridico/criar-pedido-juridico! *repo* ente {:assunto "Prazo regimental da leitura" :origem "secretaria" :pedido-por (random-uuid)})]
        (juridico/salvar-parecer-juridico! *repo* ente (:id av) (random-uuid) texto)
        (juridico/assinar-parecer-juridico! *repo* ente (:id av) assinante)
        (is (= 1 (count (juridico/pareceres-juridicos-publicos *repo* ente pid))))
        (is (empty? (juridico/pareceres-juridicos-publicos *repo* ente (random-uuid))))))
    (testing "o parametro de uma Casa nao antecipa o portal de outra"
      (let [outra (random-uuid) pid-o (protocolar! outra)]
        (assinar-parecer-da-materia! outra pid-o)
        (is (empty? (juridico/pareceres-juridicos-publicos *repo* outra pid-o)))))))
