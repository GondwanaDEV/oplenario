-- NAO revoga USAGE ON SCHEMA paineis (review clojure CRÍTICO): essa concessao pertence a mig 0009 (retrofit
-- de tenancy), que ja fez o proprio GRANT/REVOKE simetrico — schema-level GRANT nao e' contado por
-- referencia, entao um REVOKE aqui desfaria a mig 0009 e quebraria `paineis.notificacao_entrega` (mig 0004),
-- que continua aplicada e depende do mesmo USAGE.
DROP TABLE IF EXISTS paineis.pendencia;
