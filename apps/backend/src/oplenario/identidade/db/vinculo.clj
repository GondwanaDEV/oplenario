(ns oplenario.identidade.db.vinculo
  "Persistencia TENANT (FORCE RLS) do identidade: vinculo (identidade<->ente), usuario_papel (RBAC
  estatico) e consentimento (LGPD). Funcoes sobre a `tx` do tenant (com-tenant* -> RLS isola). HoneySQL."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.identidade.models.identidade :as mod]
            [oplenario.kernel.db-util :as comum]))

(set! *warn-on-reflection* true)

;; ---- vinculo ----
(defn criar!
  "Cria o vinculo. Idempotente por (ente_id, identidade_id, tipo) — RETORNA o id CANONICO (o existente, em
  caso de conflito); o caller DEVE usar este id, nao o que passou (mesma disciplina de db/identidade/inserir!).
  DO UPDATE (no-op sobre `tipo`) em vez de DO NOTHING: DO NOTHING nao devolveria RETURNING na colisao."
  [tx {:keys [id ente-id identidade-id tipo estado]}]
  (:vinculo/id
   (jdbc/execute-one! tx
     (sql/format {:insert-into :identidade.vinculo
                  :values [{:id id :ente_id ente-id :identidade_id identidade-id
                            :tipo tipo :estado (or estado "ativo")}]
                  :on-conflict [:ente_id :identidade_id :tipo]
                  :do-update-set {:tipo :excluded.tipo}
                  :returning [:id]}))))

(defn vinculos-de
  "Os vinculos da identidade NESTE ente (RLS ja restringe ao tenant). Base do escopo ativo (§22.5 eixo D)."
  [tx ente-id identidade-id]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:id :ente_id :identidade_id :tipo :estado]
                   :from [:identidade.vinculo]
                   :where [:and [:= :ente_id ente-id] [:= :identidade_id identidade-id]]
                   ;; ORDER BY determinístico: multi-vínculo ativo NÃO pode escolher vínculo ao acaso
                   ;; (o :vinculo-ativo-id vai p/ o audit; o mais antigo = âncora estável).
                   :order-by [[:criado_em :asc] [:id :asc]]}))))

(defn ja-entrou?
  "A identidade ja' entrou nesta Casa por algum vinculo (`primeiro_acesso_em` preenchido)? E' o que impede o `admin_ente`
  de trocar o e-mail de quem ja' tem conta em uso: com o link do convite ele assumiria a conta da pessoa."
  [tx ente-id identidade-id]
  (some? (jdbc/execute-one! tx
           (sql/format {:select [1] :from [:identidade.vinculo]
                        :where [:and [:= :ente_id ente-id] [:= :identidade_id identidade-id]
                                [:<> :primeiro_acesso_em nil]]
                        :limit 1}))))

(defn estado-de
  "Leitura ESTREITA (so' :estado) do vinculo pelo id. Usada por `conceder-acesso!` (repositorio component,
  Task 12 achado seguranca) pra checar, LOGO apos o UPSERT de `criar!`, se o vinculo canonico segue ativo
  antes de conceder papeis e prosseguir pro Keycloak — `criar!` e' idempotente por (ente,identidade,tipo)
  e o `:do-update-set` de proposito NUNCA toca `:estado` (Task 7), entao re-conceder a um vinculo suspenso
  nao reativa (fail-closed, correto); o que faltava era o CALLER perceber isso antes de mandar convite."
  [tx id]
  (:vinculo/estado
   (jdbc/execute-one! tx
     (sql/format {:select [:estado] :from [:identidade.vinculo] :where [:= :id id]}))))

(defn mudar-estado! [tx id estado]
  {:pre [(contains? mod/estados-vinculo estado)]}   ; erro de dominio antes do CHECK do banco virar PSQLException
  (jdbc/execute-one! tx
    (sql/format {:update :identidade.vinculo :set {:estado estado} :where [:= :id id]})))

(defn marcar-primeiro-acesso!
  "Grava o 1o acesso do vinculo SO' se ainda nao havia (UPDATE condicional atomico). true = foi agora (o chamador
  emite o evento); false = ja' tinha entrado antes."
  [tx vinculo-id]
  (some? (jdbc/execute-one! tx
           (sql/format {:update :identidade.vinculo :set {:primeiro_acesso_em [:now]}
                        :where [:and [:= :id vinculo-id] [:= :primeiro_acesso_em nil]]
                        :returning [:id]}))))

;; ---- usuario_papel (RBAC estatico) ----
;; ADR-0005 (adendo "Revogar acesso"): a linha do papel pode estar REVOGADA (`revogado_em`); so' a ATIVA vale. A unicidade
;; (Casa, pessoa, papel) e' do indice parcial dos ativos — por isso o conflito abaixo cita o predicado.
(defn adicionar-papel!
  "Concede o papel. Idempotente para o ATIVO (conceder de novo o que ja' vale nao faz nada); se o anterior foi revogado,
  abre OUTRA linha — o historico da revogacao fica."
  [tx {:keys [id ente-id identidade-id papel]}]
  (jdbc/execute-one! tx
    ["INSERT INTO identidade.usuario_papel (id, ente_id, identidade_id, papel) VALUES (?, ?, ?, ?)
      ON CONFLICT (ente_id, identidade_id, papel) WHERE revogado_em IS NULL DO NOTHING"
     id ente-id identidade-id papel]))

(defn papeis-de
  "Conjunto de papeis ATIVOS da identidade neste ente (o snapshot do token, §22.5.2 eixo D). Papel revogado nao conta."
  [tx ente-id identidade-id]
  (set (map :usuario_papel/papel
            (jdbc/execute! tx
              (sql/format {:select [:papel] :from [:identidade.usuario_papel]
                           :where [:and [:= :ente_id ente-id] [:= :identidade_id identidade-id]
                                   [:= :revogado_em nil]]})))))

(defn revogar-papel!
  "Fecha o papel ATIVO da identidade neste ente: quem (`por`), quando (agora) e por que (`motivo`). A linha fica
  (historico); conceder de novo abre outra. true = revogou agora; false = nao havia papel ativo."
  [tx ente-id identidade-id papel por motivo]
  (pos? (:next.jdbc/update-count
         (jdbc/execute-one! tx
           (sql/format {:update :identidade.usuario_papel
                        :set {:revogado_em [:now] :revogado_por por :motivo_revogacao motivo}
                        :where [:and [:= :ente_id ente-id] [:= :identidade_id identidade-id] [:= :papel papel]
                                [:= :revogado_em nil]]})))))

(defn encerrar-vinculos-da-casa!
  "Encerra os vinculos ATIVOS da identidade neste ente que NAO sao de cidadao (quem so' participa como cidadao nao
  perde nada). Devolve quantos encerrou. Sem vinculo ativo `resolver-sessao` nao resolve ator: a sessao cai na proxima
  chamada."
  [tx ente-id identidade-id]
  (:next.jdbc/update-count
   (jdbc/execute-one! tx
     (sql/format {:update :identidade.vinculo :set {:estado "encerrado"}
                  :where [:and [:= :ente_id ente-id] [:= :identidade_id identidade-id]
                          [:= :estado "ativo"] [:<> :tipo "cidadao"]]}))))

(defn acessos
  "Os acessos concedidos da Casa (ADR-0005, adendo): para cada (pessoa, papel) dos `papeis` dados, a linha MAIS RECENTE —
  ativa, ou a ultima revogada. [{:identidade-id :papel :concedido-em :revogado-em :revogado-por :motivo}], sem ordem
  de exibicao (quem chama ordena). Os nomes ficam com o chamador (identidade e' supratenant)."
  [tx ente-id papeis]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select-distinct-on [[:identidade_id :papel]
                                        :identidade_id :papel [:criado_em :concedido_em] :revogado_em :revogado_por
                                        [:motivo_revogacao :motivo]]
                   :from [:identidade.usuario_papel]
                   :where [:and [:= :ente_id ente-id] [:in :papel (vec papeis)]]
                   :order-by [:identidade_id :papel [:criado_em :desc] [:id :desc]]}))))

(defn casa-tem-papel-ativo?
  "Existe alguem na Casa com o `papel` E um vinculo ATIVO de pessoa da Casa (nao o de cidadao)? E' a leitura de 'a Casa
  tem juridico ativo' (ADR-0019 Eixo 5): papel concedido a um vinculo suspenso ou encerrado nao conta."
  [tx ente-id papel]
  (some? (jdbc/execute-one! tx
           (sql/format {:select [1]
                        :from [[:identidade.usuario_papel :up]]
                        :join [[:identidade.vinculo :v] [:and [:= :v.ente_id :up.ente_id]
                                                          [:= :v.identidade_id :up.identidade_id]]]
                        :where [:and [:= :up.ente_id ente-id] [:= :up.papel papel] [:= :up.revogado_em nil]
                                [:= :v.estado "ativo"] [:<> :v.tipo "cidadao"]]
                        :limit 1}))))

;; ---- ADR-0020: as pessoas da Casa (destinatarios de comunicado e de aviso automatico) ----
(def teto-de-pessoas
  "Teto da leitura das pessoas da Casa (servidores, vereadores, administradores — nunca cidadaos). Uma Camara tem
  dezenas a poucas centenas; o teto e' a rede contra uma Casa que importou cidadaos como servidores por engano."
  5000)

(defn pessoas-ativas
  "As pessoas com vinculo ATIVO de quem trabalha na Casa (nao o de cidadao), cada uma com os tipos de vinculo e os
  papeis: [{:identidade-id :tipos #{..} :papeis #{..}}]. Vinculo suspenso/encerrado nao conta (a pessoa nao entra no
  sistema, entao nao recebe)."
  [tx ente-id]
  (let [vs (jdbc/execute! tx
             (sql/format {:select [:identidade_id :tipo] :from [:identidade.vinculo]
                          :where [:and [:= :ente_id ente-id] [:= :estado "ativo"] [:<> :tipo "cidadao"]]
                          :order-by [[:identidade_id :asc]]
                          :limit teto-de-pessoas}))
        ids (vec (distinct (map :vinculo/identidade_id vs)))
        ps (when (seq ids)
             (jdbc/execute! tx
               (sql/format {:select [:identidade_id :papel] :from [:identidade.usuario_papel]
                            :where [:and [:= :ente_id ente-id] [:in :identidade_id ids] [:= :revogado_em nil]]})))
        papeis (reduce (fn [m r] (update m (:usuario_papel/identidade_id r) (fnil conj #{}) (:usuario_papel/papel r)))
                       {} ps)
        tipos (reduce (fn [m r] (update m (:vinculo/identidade_id r) (fnil conj #{}) (:vinculo/tipo r))) {} vs)]
    (mapv (fn [i] {:identidade-id i :tipos (get tipos i #{}) :papeis (get papeis i #{})}) ids)))

;; ---- consentimento (LGPD, §22.5.2 eixo G) ----
(defn registrar-consentimento! [tx {:keys [id ente-id identidade-id finalidade base-legal versao-termo]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :identidade.consentimento
                 :values [{:id id :ente_id ente-id :identidade_id identidade-id
                           :finalidade finalidade :base_legal base-legal :versao_termo versao-termo}]})))

(defn revogar-consentimento!
  "Revogacao e' ato auditado (set revogado_em); tratamentos com base 'consentimento' cessam, 'obrigacao
  legal' nao. Retorna true se REVOGOU de fato (LGPD: o titular tem direito a confirmacao); false se o
  consentimento nao existe ou ja estava revogado (caller decide se isso e' erro)."
  [tx id]
  (pos? (:next.jdbc/update-count
         (jdbc/execute-one! tx
           (sql/format {:update :identidade.consentimento :set {:revogado_em [:now]}
                        :where [:and [:= :id id] [:is :revogado_em nil]]})))))

(defn consentimentos-ativos [tx ente-id identidade-id]
  (comum/linhas->kebab
    (jdbc/execute! tx
      (sql/format {:select [:id :ente_id :identidade_id :finalidade :base_legal :versao_termo]
                   :from [:identidade.consentimento]
                   :where [:and [:= :ente_id ente-id] [:= :identidade_id identidade-id] [:is :revogado_em nil]]}))))
