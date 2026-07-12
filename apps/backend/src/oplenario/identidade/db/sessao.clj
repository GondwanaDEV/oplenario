(ns oplenario.identidade.db.sessao
  "Persistencia SUPRATENANT (sem RLS) da sessao OPACA de login (custodia BFF, Onda D Slice 2; mig
  `identidade-sessao`). Recebe um `conn` connectable (pool/ds OU tx) — supratenant, mesma disciplina de
  `db/identidade.clj`: a tabela `identidade.sessao` so' e' acessivel ao role oplenario_id_resolver (do
  qual o pool herda), NUNCA ao oplenario_app do dominio (split de privilegio, anti-enumeracao). O banco
  guarda SO `sha256(segredo)` — o segredo cru do cookie NUNCA persiste (zero tokens de IdP em repouso).
  Prazos comparados no SQL com `now()` (relogio do banco), nao em Clojure — consistente com o
  DEFAULT now() de `criada_em` e com o deslize atomico de `ocioso_ate` na mesma UPDATE do resolve.
  HoneySQL."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [oplenario.kernel.db-util :as comum])
  (:import (java.security MessageDigest SecureRandom)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(defn- gerar-segredo
  "CSPRNG (SecureRandom) 32 bytes = 256 bits -> base64url SEM padding. E' o segredo CRU do cookie de
  sessao — existe fora do banco (so' o hash persiste); devolvido UMA UNICA vez, na criacao."
  ^String []
  (let [b (byte-array 32)]
    (.nextBytes (SecureRandom.) b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))

(defn- sha256-bytes
  "sha256(s) -> bytes. Deterministico (mesma entrada sempre produz o mesmo hash) — a base da resolucao
  de sessao por hash (o banco nunca compara/guarda o segredo cru). Privado: o caller nunca precisa do
  hash cru, so' das operacoes criar/resolver/apagar."
  ^bytes [^String s]
  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))

(defn inserir!
  "Cria a sessao opaca: gera o segredo (CSPRNG), INSERT com `sha256(segredo)` + os prazos JA COMPUTADOS
  pelo caller (:expira-em = teto absoluto; :ocioso-ate = janela de ociosidade inicial — o caller deriva
  ambos da config, nao esta fn). Devolve o segredo CRU — a UNICA vez que ele existe fora do cookie do
  cliente; se perdido aqui, a sessao fica orfa (sem forma de re-obter o segredo do hash, por design)."
  [conn {:keys [identidade-id ente-id expira-em ocioso-ate]}]
  {:pre [(some? identidade-id) (some? ente-id) (some? expira-em) (some? ocioso-ate)]}
  (let [segredo (gerar-segredo)]
    (jdbc/execute-one! conn
      (sql/format {:insert-into :identidade.sessao
                   :values [{:sessao_hash (sha256-bytes segredo)
                             :identidade_id identidade-id
                             :ente_id ente-id
                             :expira_em expira-em
                             :ocioso_ate ocioso-ate}]}))
    segredo))

(defn resolver!
  "Resolve o segredo cru -> {:identidade-id :ente-id} SE a sessao existe e esta' DENTRO dos dois prazos
  (`now() <= expira_em` E `now() <= ocioso_ate`, comparados no SQL = relogio do banco, nao da JVM). Em
  acerto valido, DESLIZA `ocioso_ate = now() + janela-ociosa-seg` na MESMA instrucao (UPDATE...RETURNING
  e' atomico — sem corrida entre ler e deslizar). nil se o hash e' desconhecido OU se algum dos dois
  prazos ja passou (sessao expirada nao desliza, nem revela se so' o hash bateu)."
  [conn segredo janela-ociosa-seg]
  {:pre [(some? segredo) (some? janela-ociosa-seg)]}
  (some-> (comum/linha->kebab
            (jdbc/execute-one! conn
              (sql/format {:update :identidade.sessao
                           :set {:ocioso_ate [:+ [:now]
                                               [:raw (str "(interval '1 second' * " (long janela-ociosa-seg) ")")]]}
                           :where [:and [:= :sessao_hash (sha256-bytes segredo)]
                                        [:<= [:now] :expira_em]
                                        [:<= [:now] :ocioso_ate]]
                           :returning [:identidade_id :ente_id]})))
          (select-keys [:identidade-id :ente-id])))

(defn apagar!
  "DELETE por hash — idempotente (apagar 2x, ou um segredo desconhecido, e' no-op silencioso; logout
  nao precisa saber se a sessao ja tinha caido por prazo)."
  [conn segredo]
  (jdbc/execute-one! conn
    (sql/format {:delete-from :identidade.sessao :where [:= :sessao_hash (sha256-bytes segredo)]}))
  nil)
