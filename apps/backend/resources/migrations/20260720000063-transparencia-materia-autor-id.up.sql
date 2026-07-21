-- Onda E fatia 2: elo AUTORIA -> VEREADOR no read-model publico. REVISITA a decisao original da mig 0044
-- ("sem autor_id interno"), que foi tomada antes de existir o requisito de PERFIL PUBLICO DO VEREADOR: sem
-- este elo, "materias de autoria" so' sairia por casamento de autor_texto (fragil). Nao ha vazamento de PII:
-- `autor_id` e' o UUID do vereador, ator PUBLICO da Casa, ja' exposto na rota publica de perfil desta fatia.
-- NULLABLE por necessidade: a projecao NAO tem replay (carry documentado na 0044) — materia protocolada antes
-- deste deploy fica sem elo para sempre, e a UI DIZ isso em vez de fingir acervo completo.
ALTER TABLE transparencia.materia ADD COLUMN IF NOT EXISTS autor_id uuid;
--;;
-- hot-path "materias de autoria deste vereador" (a secao mais pesada do perfil). Parcial: a maioria das
-- linhas legadas e' NULL e nao precisa entrar no indice.
CREATE INDEX IF NOT EXISTS idx_materia_autor
  ON transparencia.materia (ente_id, autor_id, ano DESC, sequencial DESC)
  WHERE autor_id IS NOT NULL;
