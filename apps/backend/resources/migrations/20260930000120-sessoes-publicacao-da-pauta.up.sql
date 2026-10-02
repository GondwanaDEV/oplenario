-- ADR-0019 fatia 3 (Eixo 7) — PUBLICAR A PAUTA, o ato unico sobre a pauta inteira de uma sessao.
--
-- (1) `sessoes.regra_pauta` — a regra da Casa: QUEM publica e a ANTECEDENCIA minima. Uma linha por Casa; sem linha, vale
--     o padrao (a secretaria publica, sem antecedencia) — a demo e as Casas ja' em uso seguem funcionando sem migrar dado.
--     `quem_publica`: 'secretaria' (padrao), 'presidente' (o Presidente da Mesa vigente), 'primeiro_secretario' (o 1o
--     Secretario da Mesa vigente) ou 'mesa' (qualquer membro com cargo na Mesa vigente). O cargo e' lido de `cadastros`
--     no ATO (a Mesa de hoje), nunca guardado aqui. `antecedencia_minima_horas` NULL = a Casa nao tem regra; com regra,
--     publicar mais tarde que isso e' ACEITO com aviso (os Regimentos deixam a pauta ir a plenario assim).
--     Edita so' o `admin_ente` (controller); a secretaria e a Mesa leem.
CREATE TABLE IF NOT EXISTS sessoes.regra_pauta (
  ente_id                   uuid NOT NULL,
  quem_publica              text NOT NULL DEFAULT 'secretaria'
                            CHECK (quem_publica IN ('secretaria', 'presidente', 'primeiro_secretario', 'mesa')),
  antecedencia_minima_horas integer CHECK (antecedencia_minima_horas IS NULL
                                           OR (antecedencia_minima_horas >= 1 AND antecedencia_minima_horas <= 720)),
  atualizada_por            uuid,
  criado_em                 timestamptz NOT NULL DEFAULT now(),
  atualizado_em             timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ente_id)
);
--;;
ALTER TABLE sessoes.regra_pauta ENABLE ROW LEVEL SECURITY;
--;;
ALTER TABLE sessoes.regra_pauta FORCE ROW LEVEL SECURITY;
--;;
DROP POLICY IF EXISTS tenant_isolation ON sessoes.regra_pauta;
--;;
CREATE POLICY tenant_isolation ON sessoes.regra_pauta
  USING (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid)
  WITH CHECK (ente_id = NULLIF(current_setting('app.ente_id', true), '')::uuid);
--;;
GRANT SELECT, INSERT, UPDATE (quem_publica, antecedencia_minima_horas, atualizada_por, atualizado_em)
  ON sessoes.regra_pauta TO oplenario_app;
--;;
-- (2) a VERSAO publicada guarda A QUE TITULO foi publicada (a regra que autorizou quem publicou, fotografada no ato: a
--     regra da Casa pode mudar depois) e os AVISOS que a tela mostrou (materia sem parecer da comissao, pedido juridico
--     pendente, antecedencia nao cumprida) — a prova do que se sabia ao publicar. Colunas NULAS: as versoes anteriores
--     (e as `execucao_final`) nao tem. A tabela segue append-only (o trigger barra UPDATE/DELETE).
ALTER TABLE sessoes.pauta_sessao_versao
  ADD COLUMN IF NOT EXISTS publicada_a_titulo text
    CHECK (publicada_a_titulo IS NULL
           OR publicada_a_titulo IN ('secretaria', 'presidente', 'primeiro_secretario', 'mesa'));
--;;
ALTER TABLE sessoes.pauta_sessao_versao ADD COLUMN IF NOT EXISTS avisos jsonb;
