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
  urgente primeiro."
  [:map {:closed true}
   [:pendencias [:sequential PendenciaOut]]])
