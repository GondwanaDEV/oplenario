(ns oplenario.legislativo.db.meus-votos
  "Os votos NOMINAIS do PROPRIO vereador, lidos da FONTE (`legislativo.votos`) — nao do read-model publico. Existe para
  a tela 'Minha atuacao': o vereador ve tudo o que votou, inclusive em sessao secreta ou fechada ao publico, e so' ele.
  A leitura publica de voto por vereador (o read-model do portal, so' sessao publica) fica intacta: esta e' outra
  porta, de outro modulo, e nao toca esse read-model — por isso o teste estrutural do portal segue sem excecao.

  Sigilo: `legislativo.votos` so' tem voto NOMINAL (atribuido). O voto de votacao SECRETA mora em `votos_secretos`,
  sem `vereador_id` (sigilo no schema) — esta leitura nem a consulta, entao nao ha' como reconstruir quem votou o que
  numa votacao secreta. Funcoes sobre a `tx` do tenant (RLS isola); `ente_id` e `vereador_id` em toda query."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum]
            [oplenario.legislativo.db.votacao :as votacao]))

(set! *warn-on-reflection* true)

(def teto-votos
  "Teto server-side da lista (anti unbounded-read; o mesmo da secao 'como votou' do perfil publico)."
  50)

(defn consulta-votos
  "O HoneySQL da lista (separado de `votos-do-vereador` para o teste de plano conferir EXATAMENTE a query que roda)."
  [ente-id vereador-id]
  {:select [:v.votacao_id :v.voto :v.registrado_em
            [:vt.estado :votacao_estado] [:vt.sessao_id :sessao_id]
            [:p.tipo :materia_tipo] [:p.ano :materia_ano]
            [:p.sequencial :materia_sequencial] [:p.ementa :materia_ementa]]
   :from [[:legislativo.votos :v]]
   :join [[:legislativo.votacoes :vt] [:and [:= :vt.ente_id :v.ente_id] [:= :vt.id :v.votacao_id]]]
   :left-join [[:legislativo.proposicoes :p]
               [:and [:= :p.ente_id :vt.ente_id] [:= :p.id :vt.objeto_id]
                [:in :vt.objeto_tipo votacao/objetos-que-carregam-a-materia-sql]]]
   :where [:and [:= :v.ente_id ente-id] [:= :v.vereador_id vereador-id]]
   :order-by [[:v.registrado_em :desc] [:v.votacao_id :desc]]
   :limit teto-votos})

(defn votos-do-vereador
  "Os votos do vereador, do mais recente para o mais antigo (desempate por votacao_id: `registrado_em` e' o inicio da tx,
  votos proximos podem empatar). Cada linha traz o estado da votacao, a sessao (nil = votacao fora de plenario) e a
  materia votada (nil quando o objeto da votacao nao e' a propria proposicao — parecer, emenda, requerimento).
  A lista para no teto; o universo vem de `contar-por-opcao`. O indice `idx_votos_vereador_registrado` (mig
  20261005000230) serve o filtro e a ordem; o teste de plano o confere."
  [tx ente-id vereador-id]
  {:pre [(some? ente-id) (some? vereador-id)]}
  (comum/linhas->kebab
   (jdbc/execute! tx (sql/format (consulta-votos ente-id vereador-id)))))

(def ^:private anulada [:= :vt.estado [:inline "anulada"]])

(defn consulta-contagem
  "O HoneySQL da contagem (separado de `contar-por-opcao` pelo mesmo motivo de `consulta-votos`)."
  [ente-id vereador-id]
  {:select [:v.voto [anulada :anulada] [[:count :*] :contagem]]
   :from [[:legislativo.votos :v]]
   :join [[:legislativo.votacoes :vt] [:and [:= :vt.ente_id :v.ente_id] [:= :vt.id :v.votacao_id]]]
   :where [:and [:= :v.ente_id ente-id] [:= :v.vereador_id vereador-id]]
   :group-by [:v.voto anulada]})

(defn contar-por-opcao
  "O universo da lista, num unico GROUP BY: {:total n :sim n :nao n :abstencao n}. `:total` e' tudo o que o vereador
  votou (a lista trunca em `teto-votos`); as tres opcoes contam so' os votos de votacao NAO anulada — a anulacao desfaz
  a votacao (correcao = nova votacao), entao o voto dela aparece na lista marcado, mas nao vira numero."
  [tx ente-id vereador-id]
  {:pre [(some? ente-id) (some? vereador-id)]}
  (let [grupos (comum/linhas->kebab
                (jdbc/execute! tx (sql/format (consulta-contagem ente-id vereador-id))))
        validos (remove :anulada grupos)
        de (fn [v] (reduce + 0 (map :contagem (filter #(= v (:voto %)) validos))))]
    {:total     (reduce + 0 (map :contagem grupos))
     :sim       (de "sim")
     :nao       (de "nao")
     :abstencao (de "abstencao")}))
