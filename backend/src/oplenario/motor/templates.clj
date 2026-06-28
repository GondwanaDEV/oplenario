(ns oplenario.motor.templates
  "Os 4 templates do Eixo C (§22.7.5 §7), VERBATIM de docs/05, como fonte_yaml (B1).
  Mais casos NEGATIVOS deliberados que provam o save-time type-check (dec. 2).")

;; ---- os 4 canônicos do Eixo C ----
(def T1 "
template: remessa_mensal_sim
contexto: compliance
dominio: tribunal_de_contas
parametros: { competencia: Competencia }
aplica_quando: verdadeiro
exige: remessa_enviada(\"SIM\", competencia)
prazo:
  janela: prazo_vigente(\"TCE-CE\", \"SIM_mensal\", competencia)
  a_partir_de: fim_de(competencia)
severidade: bloqueante
referencia_normativa: \"IN TCE-CE 04/2019; Lei 12.160/1993 art.40 par.3\"
")

(def T2 "
template: transparencia_tempo_real_despesa
contexto: compliance
dominio: federal
parametros: { despesa: AtoDespesa }
aplica_quando: populacao() > 10000
exige: publicada_no_portal(despesa)
prazo:
  janela: proximo_dia_util(data_registro_contabil(despesa))
  a_partir_de: data_registro_contabil(despesa)
severidade: bloqueante
referencia_normativa: \"LC 131/2009; LRF art.48-A; Decreto 10.540/2020 art.2 IX\"
")

(def T3 "
template: publicacao_ato_legislativo
contexto: compliance
dominio: regimento_tenant
parametros: { ato: AtoLegislativo }
aplica_quando: ato.tipo in { resolucao, decreto_legislativo, ato_mesa }
exige: publicado(ato)
prazo:
  janela: soma_dias_uteis(data_promulgacao(ato), parametro_tenant(\"prazo_publicacao_ato_dias\"))
  a_partir_de: data_promulgacao(ato)
severidade: aviso
referencia_normativa: \"Regimento Interno (ex.: CMF art.45 I h = 5 dias uteis)\"
")

;; T4: tentativa DELIBERADA de expressar quórum como compliance. O NÚCLEO de expressão
;; tipa (reaproveitável), mas o ENVELOPE de compliance não cabe (S4): dominio indefinido
;; + prazo inerte. Esperado: expressao_ok=True, status=INVALIDA.
(def T4 "
template: maioria_emenda_lom
contexto: compliance
dominio: ???
parametros: { votacao: Votacao }
aplica_quando: votacao.materia == emenda_lom
exige: votos_favoraveis(votacao) >= arredonda_cima( fracao(2,3) * membros_da_casa(hoje()) )
prazo: ???
severidade: bloqueante
")

(def CANONICOS {"T1" T1 "T2" T2 "T3" T3 "T4" T4})

;; ---- negativos: cada um isola um modo de falha do type-checker ----
(def N1 "
template: neg_arg_tipo_errado
contexto: compliance
dominio: tribunal_de_contas
parametros: { competencia: Competencia }
aplica_quando: verdadeiro
exige: remessa_enviada(123, competencia)
severidade: bloqueante
referencia_normativa: \"n/a\"
")

(def N2 "
template: neg_funcao_inexistente
contexto: compliance
dominio: federal
parametros: { despesa: AtoDespesa }
aplica_quando: verdadeiro
exige: funcao_inexistente(despesa)
severidade: aviso
referencia_normativa: \"n/a\"
")

(def N3 "
template: neg_exige_nao_booleano
contexto: compliance
dominio: federal
parametros: { despesa: AtoDespesa }
aplica_quando: verdadeiro
exige: populacao()
severidade: aviso
referencia_normativa: \"n/a\"
")

(def N4 "
template: neg_comparacao_incompativel
contexto: compliance
dominio: federal
parametros: { despesa: AtoDespesa }
aplica_quando: populacao() > \"muito\"
exige: publicada_no_portal(despesa)
severidade: aviso
referencia_normativa: \"n/a\"
")

(def N5 "
template: neg_param_tenant_desconhecido
contexto: compliance
dominio: regimento_tenant
parametros: { ato: AtoLegislativo }
aplica_quando: verdadeiro
exige: publicado(ato)
prazo:
  janela: soma_dias_uteis(data_promulgacao(ato), parametro_tenant(\"chave_inexistente\"))
  a_partir_de: data_promulgacao(ato)
severidade: aviso
referencia_normativa: \"n/a\"
")

(def NEGATIVOS {"N1" N1 "N2" N2 "N3" N3 "N4" N4 "N5" N5})

;; Regra CONTÍNUA (sem bloco prazo) — sabor que não materializa instância (§22.7.7).
(def CONTINUA "
template: cont_transparencia_permanente
contexto: compliance
dominio: federal
parametros: { despesa: AtoDespesa }
aplica_quando: verdadeiro
exige: publicada_no_portal(despesa)
severidade: bloqueante
referencia_normativa: \"LAI art.8 par.1 (dever continuo)\"
")
