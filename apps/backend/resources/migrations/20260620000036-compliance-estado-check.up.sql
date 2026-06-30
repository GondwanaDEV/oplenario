-- F5.3a (review database A1 / security #3): CHECK de dominio nos enums do schema compliance. O enum e' FIXO
-- em codigo (compliance.logic) — este CHECK e' a 2a linha de defesa no banco (as docstrings dos models ja'
-- afirmavam "os CHECK da mig 0005 espelham"; esta migration torna isso verdade). Barra UPDATE/seed/migration
-- futura que grave estado/veredito espurio (que o sweep e a costura nao pegariam, criando prova silenciosa errada).

ALTER TABLE compliance.remessa_gerada
  ADD CONSTRAINT chk_remessa_estado
  CHECK (estado IN ('rascunho', 'validada', 'submetida', 'aceita', 'rejeitada'));
--;;
ALTER TABLE compliance.prazo_dominio_ativo
  ADD CONSTRAINT chk_obrigacao_estado
  CHECK (estado IN ('pendente', 'cumprida', 'vencida', 'dispensada', 'cancelada'));
--;;
ALTER TABLE compliance.compliance_avaliacao
  ADD CONSTRAINT chk_avaliacao_veredito
  CHECK (veredito IN ('conforme', 'nao_conforme', 'inaplicavel'));
--;;
ALTER TABLE compliance.compliance_avaliacao
  ADD CONSTRAINT chk_avaliacao_origem
  CHECK (origem_avaliacao IN ('evento', 'sweep', 'sob_demanda'));
--;;
ALTER TABLE compliance.compliance_avaliacao
  ADD CONSTRAINT chk_avaliacao_severidade
  CHECK (severidade IN ('bloqueante', 'aviso'));
