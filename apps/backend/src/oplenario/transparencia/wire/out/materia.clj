(ns oplenario.transparencia.wire.out.materia
  "Representacao EXTERNA de SAIDA da materia (§22.10 wire/out, ADR-0001) — contratos que o `adapters/out`
  produz. Tudo JSON-serializavel (Instant vira string). MateriaOut = item da listagem publica do portal.
  FichaOut = MateriaOut + a norma publicada (se a materia ja' foi promulgada/publicada) — a ligacao
  'proposicao -> lei' que a ficha da materia (16.5) expoe ao cidadao. Sem PII (autor e' so' texto de exibicao)."
  (:require [oplenario.transparencia.wire.out.norma :as wire-norma]))

(def MateriaOut
  [:map {:closed true}
   [:proposicao-id :string]
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:estado :string]
   ;; docs/16 linhas 18 e 30: o ultimo ato depois do plenario (aprovada, rejeitada, autografo_enviado, sancionado,
   ;; sancao_tacita, vetado, veto_mantido, veto_derrubado, promulgada, publicada); nil = ainda nao foi a votos
   [:desfecho [:maybe :string]]])

(def MateriasOut
  "Resposta de GET /portal/casa/:ente/materias (familia 'truncamento-familia', sitio (b)): a listagem
  publica de proposicoes em tramitacao NAO tinha outra rota (esta secao E' a listagem, ver
  materia-vista.ts/escolherDestaque no FE) e cortava em 200 (`teto-listagem`, `db/materia.clj`) sem sinalizar.
  `:materias-total` e' o par obrigatorio (mesmo racional de `transparencia/wire/out/parlamentar` e
  `compliance/wire/out/painel`): o teto em si NUNCA sai neste contrato — e' server-side, decisao de
  seguranca."
  [:map {:closed true}
   [:materias [:sequential MateriaOut]]
   [:materias-total :int]])

(def ResumoPublicoOut
  "Faixa A / A.8b: o resumo em linguagem simples PUBLICADO pela Casa. `gerado-com-ia` = partiu do rascunho da IA (o
  portal mostra o selo 'escrito com ajuda de IA e revisado pela equipe da Camara', §16.8)."
  [:map {:closed true}
   [:texto :string]
   [:versao :int]
   [:gerado-com-ia :boolean]
   [:publicado-em :string]])

(def EtapaDoRitoPublicoOut
  "Uma etapa do rito DA CASA no portal: `chave` (texto livre de template, nunca enum), `rotulo` (o nome que a Casa deu) e
  `terminal` (encerra o processo). Nada mais: o portal e' publico."
  [:map {:closed true}
   [:chave :string]
   [:rotulo :string]
   [:terminal :boolean]])

(def RitoPublicoOut
  "O rito da materia para a faixa 'Onde este projeto esta' da ficha publica (a mesma linha que a ficha interna recebe).
  `ordem-unica` true = `etapas` e' a linha do rito em ordem (fecha na atual quando ela e' terminal); false = o rito nao
  da' ordem unica verificavel e `etapas` vem vazio — a faixa mostra so' o entorno (`anteriores` · `atual` · `proximas`).
  `atual` nil = o rito nao declara o estado da materia. `anteriores` nil = nao se sabe por onde passou."
  [:map {:closed true}
   [:ordem-unica :boolean]
   [:etapas [:sequential EtapaDoRitoPublicoOut]]
   [:atual [:maybe EtapaDoRitoPublicoOut]]
   [:anteriores [:maybe [:sequential EtapaDoRitoPublicoOut]]]
   [:proximas [:sequential EtapaDoRitoPublicoOut]]])

(def FichaOut
  "MateriaOut + a norma publicada, se houver (:norma ausente/nil = a materia ainda nao virou lei) + o resumo cidadao
  publicado, se houver (:resumo ausente/nil = a Casa ainda nao publicou) + o rito da Casa (:rito ausente/nil = a
  materia nao teve evento com rito; a tela usa o mapa fixo)."
  [:map {:closed true}
   [:proposicao-id :string]
   [:tipo :string]
   [:ano :int]
   [:sequencial :int]
   [:urn-lex :string]
   [:ementa :string]
   [:autor-tipo {:optional true} [:maybe :string]]
   [:autor-texto {:optional true} [:maybe :string]]
   [:estado :string]
   ;; docs/16 linhas 18 e 30: o ultimo ato depois do plenario (aprovada, rejeitada, autografo_enviado, sancionado,
   ;; sancao_tacita, vetado, veto_mantido, veto_derrubado, promulgada, publicada); nil = ainda nao foi a votos
   [:desfecho [:maybe :string]]
   [:norma {:optional true} [:maybe wire-norma/NormaOut]]
   [:resumo {:optional true} [:maybe ResumoPublicoOut]]
   [:rito {:optional true} [:maybe RitoPublicoOut]]])

