-- NAO revoga USAGE ON SCHEMA paineis (mesma nota da mig 0048): a concessao pertence a mig 0009 e
-- schema-level GRANT nao e' contado por referencia — um REVOKE aqui quebraria as tabelas irmas.
DROP TABLE IF EXISTS paineis.notificacao_caixa;
