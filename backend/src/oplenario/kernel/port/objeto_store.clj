(ns oplenario.kernel.port.objeto-store
  "Port de armazenamento de binarios (§22.9: object storage S3-compativel — audio de sessao, anexos,
  artefatos de remessa, PII pre-filtro). A impl (Component) fala S3 com qualquer endpoint compativel
  (MinIO self-host na V1). Cifra em repouso = SSE-S3/KES (§22.9 Eixo 11, infra). Kernel — protocolo.")

(defprotocol ObjetoStore
  (guardar! [this chave bytes content-type] "Guarda o blob (byte-array) sob `chave`; devolve a chave.")
  (obter    [this chave] "Devolve os bytes do blob (byte-array), ou nil se ausente.")
  (remover! [this chave] "Remove o blob da `chave`."))
