(ns oplenario.kernel.ids
  "Politica central de identidade de linha: PK = UUID (§22.9 Eixo 2). Centralizado para poder
  trocar a versao do UUID (ex.: v7 time-ordered p/ localidade de B-tree) sem tocar call sites.
  A numeracao canonica gapless por linha-contador (SELECT ... FOR UPDATE na tx do ato, nao
  SEQUENCE) e' db-backed e mora na F0.3."
  (:import (java.util UUID)))

(defn novo-id
  "Gera um novo UUID para PK de linha."
  ^UUID []
  (UUID/randomUUID))
