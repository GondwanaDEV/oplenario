(ns oplenario.kernel.segredo
  "Segredo opaco de sessao/credencial: gerado por CSPRNG e guardado so' como sha256. Mesma custodia de
  `identidade.db.sessao` (a sessao da Casa), aqui no kernel para a sessao do operador (ADR-0016) nao importar o
  modulo de identidade (§22.10)."
  (:import (java.security MessageDigest SecureRandom)
           (java.util Base64 HexFormat)))

(set! *warn-on-reflection* true)

(defn gerar
  "32 bytes (256 bits) de SecureRandom -> base64url sem padding. E' o valor cru do cookie; so' existe fora do banco."
  ^String []
  (let [b (byte-array 32)]
    (.nextBytes (SecureRandom.) b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))

(defn sha256-bytes ^bytes [^String s]
  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))

(defn sha256-hex ^String [^String s]
  (.formatHex (HexFormat/of) (sha256-bytes s)))
