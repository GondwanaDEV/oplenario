-- SUBSTITUIR um anexo da resposta (ADR-0022, "Substituir um anexo"): a secretaria troca o arquivo errado pelo certo num so'
-- ato, a qualquer tempo (a retirada tambem nao depende da janela de 10 minutos). O efeito e' de duas linhas na MESMA
-- transacao: o anexo antigo ganha a sua linha em `participacao.anexo_retirada` (com o motivo) e o novo nasce como linha nova
-- em `participacao.anexo` apontando para o antigo — `substitui_anexo_id`. A tabela segue so' SELECT/INSERT: a coluna entra no
-- INSERT do anexo novo, nunca num UPDATE do antigo ("substituido por X" e' lido de volta pela relacao inversa).
--
-- NULL = anexo comum (enviado, nao substituicao). Um anexo so' e' substituido UMA vez (indice unico parcial): o 2o que
-- tentasse apontar para o mesmo antigo e' recusado pelo banco, alem da conferencia da aplicacao (que ja' recusa o antigo
-- retirado). A FK composta amarra a substituicao a mesma Casa.
ALTER TABLE participacao.anexo ADD COLUMN IF NOT EXISTS substitui_anexo_id uuid NULL;
--;;
ALTER TABLE participacao.anexo
  ADD CONSTRAINT anexo_substitui_fk FOREIGN KEY (ente_id, substitui_anexo_id) REFERENCES participacao.anexo (ente_id, id);
--;;
CREATE UNIQUE INDEX IF NOT EXISTS uq_participacao_anexo_substitui
  ON participacao.anexo (ente_id, substitui_anexo_id) WHERE substitui_anexo_id IS NOT NULL;
