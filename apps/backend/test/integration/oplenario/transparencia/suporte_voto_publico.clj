(ns oplenario.transparencia.suporte-voto-publico
  "SUPORTE DE TESTE (nao e' um ns de teste — o kaocha so' carrega `*-test`): o seam `votacoes-com-voto-publico` que
  os testes de `transparencia` usam quando o assunto NAO e' a regra de sessao publica.

  A leitura publica de voto por vereador e' FAIL-CLOSED: so' sai voto de votacao que o host diz ser de sessao publica
  (`oplenario.votacoes-publicas/ids-de-votacoes-publicas`). Os testes de perfil/dados abertos semeiam o read-model
  pelo `projetar-evento!` com `votacao-id` inventado — sem sessao nenhuma —, e com o seam real nada sairia. Aqui o
  seam diz 'toda votacao projetada na Casa e' publica': os testes continuam medindo o que sempre mediram (escopo por
  vereador, teto, contagem), e a regra de sessao tem o teste proprio (`voto_de_sessao_secreta_test`)."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [oplenario.kernel.tenancy :as tenancy]))

(defn tudo-publico
  "Seam `(fn [ente-id] -> #{votacao-id})` que declara publica toda votacao com voto projetado na Casa."
  [ds]
  (fn [ente-id]
    (into #{}
          (map :votacao_id)
          (tenancy/com-tenant* ds ente-id
            (fn [tx]
              (jdbc/execute! tx ["SELECT DISTINCT votacao_id FROM transparencia.voto_parlamentar WHERE ente_id = ?"
                                 ente-id]
                             {:builder-fn rs/as-unqualified-maps}))))))
