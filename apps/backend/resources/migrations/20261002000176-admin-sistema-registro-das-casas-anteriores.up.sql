-- ADR-0016/0018: toda Casa que ja' existe no cadastro entra no REGISTRO DE CASAS (supratenant). As Casas criadas antes do
-- registro (a demo, e qualquer Casa semeada direto no cadastros) ficavam fora dele — e tudo o que o registro sustenta
-- (console, suspensao, exportacao 9.6, encerramento) nao as enxergava. Entram como `ativo` (ja' estao em uso), com o
-- perfil do proprio cadastro. Idempotente: so' insere quem falta. Roda como dono (o migrate), que le o cadastro inteiro.
INSERT INTO admin_sistema.ente (ente_id, nome, uf, estado, nome_curto, municipio_ibge, municipio_nome, ativada_em)
SELECT e.ente_id, e.nome_oficial, m.uf, 'ativo', e.nome_curto, e.municipio_ibge, m.nome, e.criado_em
  FROM cadastros.ente e
  JOIN cadastros.municipios m ON m.codigo_ibge = e.municipio_ibge
 WHERE NOT EXISTS (SELECT 1 FROM admin_sistema.ente r WHERE r.ente_id = e.ente_id);
