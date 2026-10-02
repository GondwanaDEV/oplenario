(ns oplenario.demo.comunicados-test
  "INTEGRACAO (PG real): `comunicados/semear!` — os setores e os comunicados internos da Casa da demo (ADR-0020).

  O que importa aqui: (1) os comunicados sairam pelo caminho de PRODUCAO (lista resolvida e congelada pelos seams do
  host, protocolo do contador), nao por INSERT a mao — por isso o teste confere QUEM ficou na lista e o protocolo; (2) o
  painel de leitura do comunicado com ciencia mostra os tres estados que a demo promete; (3) rodar de novo nao duplica
  setor, comunicado nem marca.

  `with-sistema` reusada de `oplenario.demo.casa-test`, como os outros testes de `demo/`."
  (:require [acervo]
            [casa]
            [clojure.test :refer [deftest is testing]]
            [comunicados :as comunicados-demo]
            [next.jdbc :as jdbc]
            [oplenario.comunicacao.components.repositorio :as repo-com]
            [oplenario.demo.casa-test :refer [with-sistema]]))

(defn- contar [ds sql ente] (-> (jdbc/execute-one! ds [sql ente]) vals first long))

(deftest setores-e-comunicados-da-demo
  (with-sistema [s]
    (let [ds (:ds (:datasource s))
          {:keys [ente identidades]} (casa/semear! s)
          _ (acervo/semear! s ente (:vereador identidades))
          r1 (comunicados-demo/semear! s ente identidades)
          repo (:repo-comunicacao s)
          {:keys [sessao-extraordinaria pareceres expediente]} (:comunicados r1)]
      (testing "os tres setores, lotados com as pessoas da demo"
        (is (= #{"Secretaria Legislativa" "Jurídico" "Protocolo"} (set (keys (:setores r1)))))
        (is (= #{(:secretaria identidades) (:apresentacao identidades)}
               (set (get-in r1 [:setores "Secretaria Legislativa" :membros]))))
        (is (= [(:apresentacao identidades)] (get-in r1 [:setores "Jurídico" :membros]))))

      (testing "o comunicado com ciencia: lista congelada pelos seams, protocolo do contador, prazo"
        (let [c (repo-com/comunicado repo ente sessao-extraordinaria)
              linhas (into {} (map (juxt :identidade-id identity)) (repo-com/leitura repo ente sessao-extraordinaria))]
          (is (re-matches #"COM-\d{4}-\d{6}" (:protocolo c)))
          (is (true? (:exige-ciencia c)))
          (is (= (:ciencia-ate r1) (:ciencia-ate c)))
          (is (= #{(:presidente identidades) (:vereador identidades) (:apresentacao identidades)} (set (keys linhas)))
              "a Mesa (so' a presidente entra no sistema), a vereadora e o Juridico; nunca quem enviou")
          (testing "o painel mostra os tres estados"
            (is (some? (:ciente-em (linhas (:vereador identidades)))) "a vereadora deu ciencia")
            (is (some? (:recebido-em (linhas (:presidente identidades)))) "a presidente abriu a caixa")
            (is (nil? (:lido-em (linhas (:presidente identidades)))) "...mas nao abriu o comunicado")
            (is (nil? (:recebido-em (linhas (:apresentacao identidades)))) "a apresentacao ainda nao viu nada"))))

      (testing "o comunicado ao Juridico aponta para uma proposicao do acervo"
        (let [c (repo-com/comunicado repo ente pareceres)]
          (is (= "proposicao" (:objeto-tipo c)))
          (is (some? (:objeto-id c)))))

      (testing "a presidente (admin da Casa) enviou a um setor"
        (let [c (repo-com/comunicado repo ente expediente)]
          (is (= (:presidente identidades) (:remetente-identidade-id c)))))

      (testing "idempotente: rodar de novo rele em vez de duplicar"
        (let [antes {:c (contar ds "select count(*) from comunicacao.comunicado where ente_id = ?" ente)
                     :m (contar ds "select count(*) from comunicacao.marca where ente_id = ?" ente)
                     :s (contar ds "select count(*) from cadastros.setor where ente_id = ?" ente)}
              r2 (comunicados-demo/semear! s ente identidades)]
          (is (= (:comunicados r1) (:comunicados r2)))
          (is (= antes {:c (contar ds "select count(*) from comunicacao.comunicado where ente_id = ?" ente)
                        :m (contar ds "select count(*) from comunicacao.marca where ente_id = ?" ente)
                        :s (contar ds "select count(*) from cadastros.setor where ente_id = ?" ente)})))))))
