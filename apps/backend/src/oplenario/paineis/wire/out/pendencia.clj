(ns oplenario.paineis.wire.out.pendencia
  "Representacao EXTERNA de SAIDA das pendencias (§22.10 wire/out, ADR-0001) — contrato que o `adapters/out`
  produz. Tudo JSON-serializavel (uuid/LocalDate viram string). `estado`/`objeto-tipo` ficam :string (nao
  enum fechado): esta e' uma PROJECAO pura de `participacao` (mesmo racional de transparencia/wire/out/
  materia) — o vocabulario e' validado na FONTE (participacao), nao duplicado aqui.")

(def PendenciaOut
  [:map {:closed true}
   [:objeto-tipo :string]
   [:objeto-id :string]
   [:protocolo :string]
   [:vence-em :string]
   [:estado :string]])

(def OQueVenceOut
  "O painel 'o que vence' (resposta de GET /paineis/pendencias): a lista de pendencias abertas, mais
  urgente primeiro.

  TRUNCAMENTO: `pendencias` para no teto server-side (100, `components/repositorio`); `pendencias-total`
  e' o total REAL de pendencias abertas do tenant, sem o teto (mesmo racional de
  `compliance/wire/out/painel/em-aberto-total` e `transparencia/wire/out/parlamentar`: o par lista+total
  e' obrigatorio, nao opcional). AQUI o corte e' pior que uma lista incompleta: as pendencias carregam
  prazo LEGAL de e-SIC/LGPD/ouvidoria (participacao/logic.clj), e a ordenacao (vencidas primeiro) faz a
  Casa mais atrasada — a que MAIS precisa ver o resto — ser exatamente a que perde os prazos futuros de
  vista sem o total denunciando o corte. `:limite` em si NAO sai neste contrato — o teto e' server-side
  por decisao de seguranca, e nenhum wire/out do modulo publica teto ao cliente."
  [:map {:closed true}
   [:pendencias [:sequential PendenciaOut]]
   [:pendencias-total :int]])
