-- Conserto da A.3 (mig 0085), exposto pela A.4: o FEED core -> IA (`integracao_ia.evento_saida`) nunca recebeu GRANT
-- para o papel da aplicacao. Os testes rodam como dono das tabelas e nao viam; no compose (e em producao) o app roda
-- como `oplenario_app`, e o relay falhava ao promover o primeiro evento — travando o outbox inteiro. Enquanto so'
-- gravacao vinculada ia para o feed isso nao aparecia; com a A.4 toda proposicao vai. Append-only: SELECT + INSERT.
GRANT SELECT, INSERT ON integracao_ia.evento_saida TO oplenario_app;
