(ns oplenario.transparencia.models.materia
  "Representacao INTERNA (dominio) da MATERIA no read-model do portal (§22.10 models/, ADR-0001, F6c Slice 1)
  — Malli. Projecao PUBLICA de uma proposicao de `legislativo`: snapshot no protocolo (`proposicao.protocolada`)
  + `estado` atualizado a cada transicao (`proposicao.transicionou`). SEM PII: o autor e' representado por
  VALOR (`autor-tipo`/`autor-texto` de exibicao), mais o 'autor-id' (UUID do vereador autor — ator publico), o
  elo do perfil publico (Onda E fatia 2). Carimbos via kernel.malli/Instante (timestamptz)."
  (:require [oplenario.kernel.malli :as km]))

(def Materia
  [:map {:closed true}
   [:ente-id :uuid]
   [:proposicao-id :uuid]
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:autor-id {:optional true} [:maybe :uuid]]
   [:estado :string]
   [:projetado-em km/Instante]
   [:atualizado-em km/Instante]])

(def EtapaDoRito
  "Uma etapa do rito da Casa no que o portal guarda: chave (texto livre por Casa, a mesma de `estado`), rotulo (o nome
  que a Casa deu) e terminal. CLOSED de proposito: o portal e' PUBLICO e nada alem disso entra (responsavel, comissao,
  ator, id interno). O contrato do produtor mora em legislativo; esta e' a COPIA que o consumidor exige (§22.10: o
  consumer nao importa o schema do produtor)."
  [:map {:closed true}
   [:chave :string]
   [:rotulo :string]
   [:terminal :boolean]])

(def RitoDaMateria
  "O ultimo rito da materia (`transparencia.materia.rito`): `ordem-unica` true = `etapas` e' a linha do rito em ordem;
  false = so' o entorno (`anteriores` · `atual` · `proximas`). `atual` nil = o rito nao declara o estado da materia."
  [:map {:closed true}
   [:ordem-unica :boolean]
   [:etapas [:sequential EtapaDoRito]]
   [:atual [:maybe EtapaDoRito]]
   [:anteriores [:maybe [:sequential EtapaDoRito]]]
   [:proximas [:sequential EtapaDoRito]]])
