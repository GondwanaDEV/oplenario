-- Simetrico ao que E' reversivel: os COMMENT saem (NULL apaga). O RECONCILIADOR nao tem down — ele so'
-- insere linhas que a projecao viva deveria ter escrito, e apaga-las seria destruir dado bom (nao ha como
-- distinguir "veio deste INSERT" de "veio do consumer"). O par NO FORCE/FORCE do up e' auto-reversivel
-- dentro da propria tx do up e nao aparece aqui.
COMMENT ON COLUMN transparencia.sessao_com_chamada.data IS NULL;
--;;
COMMENT ON TABLE transparencia.sessao_com_chamada IS NULL;
