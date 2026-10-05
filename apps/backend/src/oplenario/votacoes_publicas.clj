(ns oplenario.votacoes-publicas
  "O HOST compoe as VOTACOES PUBLICAS do portal (frente 'portal-votacoes-publicas'). Raiz de composicao (§22.10):
  so' ele cruza modulos. `sessoes` diz QUAIS sessoes o portal pode mostrar — a regra do livro de atas, transmissao
  publica E nao secreta —, `legislativo` guarda as votacoes e os votos; `transparencia` so' recebe estas funcoes
  prontas e nao importa nenhum dos dois.

  A regra que nao pode falhar: votacao de sessao SECRETA (ou fechada ao publico) nunca sai. Ela vale pela UNIAO
  dos dois lados — a lista so' pergunta ao legislativo pelas sessoes publicas, e o detalhe confere a sessao da
  votacao ANTES de entrega-la. Falta de sessao (id sem sessao publica) = a votacao nao existe para o portal."
  (:require [oplenario.legislativo.components.repositorio-votacao-publica :as leg]
            [oplenario.sessoes.components.repositorio-sessao-publica :as ses]))

(defn- da-sessao [s]
  {:sessao-id (:id s) :tipo-sessao (:tipo-sessao s) :numero-sequencial (:numero-sequencial s)
   :data (or (:aberta-em s) (:agendada-para s))})

(defn listar
  "{:votacoes :total} das votacoes encerradas das sessoes publicas da Casa, a mais recente primeiro, paginadas
  (`limite`/`deslocamento`). Cada votacao ganha `:sessao`. `:total` e' do mesmo predicado, sem pagina. `materia-id`
  (nil = todas) restringe as votacoes dessa materia: o filtro SO' RESTRINGE, as sessoes publicas continuam sendo o
  teto do que sai."
  [repo-sessoes repo-legislativo ente-id limite deslocamento materia-id]
  (let [sessoes (into {} (map (juxt :id identity)) (ses/sessoes-publicas repo-sessoes ente-id))
        {:keys [votacoes total]} (leg/votacoes-encerradas-das-sessoes repo-legislativo ente-id (keys sessoes)
                                                                       limite deslocamento materia-id)]
    {:total total
     :votacoes (into [] (keep (fn [v] (when-let [s (get sessoes (:sessao-id v))]
                                        (assoc v :sessao (da-sessao s)))))
                     votacoes)}))

(defn buscar
  "A votacao encerrada com os votos nominais (se nominal) e a `:sessao`, ou nil quando nao existe, nao encerrou, foi
  anulada, e' de outra Casa ou a sessao dela nao e' publica."
  [repo-sessoes repo-legislativo ente-id votacao-id]
  (when-let [v (leg/votacao-encerrada repo-legislativo ente-id votacao-id)]
    (when-let [s (ses/sessao-publica repo-sessoes ente-id (:sessao-id v))]
      (assoc v :sessao (da-sessao s)))))
