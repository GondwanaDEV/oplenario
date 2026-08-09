-- Revisao da Etapa 1 da CHAMADA (§22.6 eixo C): o CHECK `sessao_encerrada_em_obrigatoria` da mig 0026
-- cobria so' 'encerrada' e 'nao_realizada'. 'arquivada' ficou de fora — e o banco aceitava uma sessao
-- ARQUIVADA sem carimbo de encerramento. A maquina de estados da aplicacao nao produz essa linha hoje
-- (arquivada so' se alcanca vindo de encerrada|nao_realizada, que ja carimbam), mas migracao de acervo
-- legado e' feature declarada do produto e escreve por outros caminhos.
--
-- Por que importa: a chamada de uma sessao FECHADA congela no `encerrada_em`. Com ele NULL, a consulta de
-- presenca vira `ocorrido_em <= NULL` -> zero linhas, e a rota devolve 200 OK com a Casa inteira ausente e
-- quorum zero — uma chamada FABRICADA, servida como leitura legitima, que vai para a ata. O codigo passou a
-- falhar fechado (`sessoes/logic/instante-de-avaliacao` lanca); aqui o banco para de aceitar a linha.
ALTER TABLE sessoes.sessao DROP CONSTRAINT IF EXISTS sessao_encerrada_em_obrigatoria;
--;;
ALTER TABLE sessoes.sessao ADD CONSTRAINT sessao_encerrada_em_obrigatoria
  CHECK (estado NOT IN ('encerrada', 'nao_realizada', 'arquivada') OR encerrada_em IS NOT NULL);
