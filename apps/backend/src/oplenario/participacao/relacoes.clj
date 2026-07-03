(ns oplenario.participacao.relacoes
  "Funcoes de RELACAO que o `participacao` e' dono (§22.5.3). O motor de DSL as alcanca POR NOME via o
  registry/injecao da F2 — nunca por import (§22.10). O host (sistema.clj) funde este mapa no registry.

  V1 (Slice 1): mapa VAZIO — nenhum fato do participacao precisa ser alcancavel pelo motor por-nome ainda (a
  metrica institucional §16.11 'a Casa responde e-SIC no prazo?' e' DIFERIDA e sera read-model do proprio
  participacao, nao relacao do motor). O arquivo EXISTE e e' fundivel (fundir-relacoes) — o seam fica pronto
  p/ as fatias seguintes sem tocar o host.")

(def relacoes
  "Registro das relacoes deste contexto (nome canonico -> fn). Vazio em V1."
  {})
