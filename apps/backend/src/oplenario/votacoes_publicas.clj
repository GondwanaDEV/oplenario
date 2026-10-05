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
  (`limite`/`deslocamento`). Cada votacao ganha `:sessao`. `:total` e' do mesmo predicado, sem pagina."
  [repo-sessoes repo-legislativo ente-id limite deslocamento]
  (let [sessoes (into {} (map (juxt :id identity)) (ses/sessoes-publicas repo-sessoes ente-id))
        {:keys [votacoes total]} (leg/votacoes-encerradas-das-sessoes repo-legislativo ente-id (keys sessoes)
                                                                       limite deslocamento)]
    {:total total
     :votacoes (into [] (keep (fn [v] (when-let [s (get sessoes (:sessao-id v))]
                                        (assoc v :sessao (da-sessao s)))))
                     votacoes)}))

(defn ids-de-votacoes-publicas
  "#{votacao-id} das votacoes da Casa cuja sessao o portal pode mostrar — o conjunto contra o qual o portal confere
  VOTO POR VEREADOR (perfil publico, CSV de votos nominais) antes de publica-lo. O voto projetado em `transparencia`
  nao carrega a sessao; a votacao sim, no legislativo. Fail-closed: votacao sem sessao, de sessao secreta ou fechada
  ao publico, ou de outra Casa nunca entra aqui, e portanto nunca sai. Sem estado de votacao no criterio: o que
  decide e' a sessao (a mesma `sessoes-publicas` das listas acima)."
  [repo-sessoes repo-legislativo ente-id]
  (leg/ids-das-votacoes-das-sessoes repo-legislativo ente-id (map :id (ses/sessoes-publicas repo-sessoes ente-id))))

(defn buscar
  "A votacao encerrada com os votos nominais (se nominal) e a `:sessao`, ou nil quando nao existe, nao encerrou, foi
  anulada, e' de outra Casa ou a sessao dela nao e' publica."
  [repo-sessoes repo-legislativo ente-id votacao-id]
  (when-let [v (leg/votacao-encerrada repo-legislativo ente-id votacao-id)]
    (when-let [s (ses/sessao-publica repo-sessoes ente-id (:sessao-id v))]
      (assoc v :sessao (da-sessao s)))))
