# Graph Report - .  (2026-07-10)

## Corpus Check
- 0 files · ~0 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 942 nodes · 1482 edges · 76 communities (47 shown, 29 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 75 edges (avg confidence: 0.79)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Azulejo de Tramitacao (componentes)
- View-model da Lista de Proposicoes
- Layout & Auth Shell (FE)
- Dashboard da Mesa (FE)
- Mercado, GTM & Concorrencia
- Motor de Compliance · Schema & Eixos
- Proposicoes & Composicao Cross-modulo
- Stack Local & Contrato core-IA
- Track FE · Specs & Fundacao
- Roadmap de Fases (F0-F7)
- Arquetipos de Design & Telas
- Dependencias NPM (frontend)
- Pagina do Plenario ao Vivo (FE)
- Config TypeScript
- Portal do Cidadao & Direitos (e-SIC/LGPD)
- Reducer de Eventos do Plenario
- Contrato de Eventos & Pauta (SSE)
- Arquetipos & Telas de Cadastro/Config
- Linguagem Visual Republica Luminosa
- Placar de Votacao (view-model)
- Runtime de Compliance & Remessa TCE
- Expediente, Pauta & Protocolo (telas)
- Monolito Modular & Bounded Contexts
- Camada de Atencao & Chassi de Componentes
- Motor DSL · Nucleo (Clojure)
- Modelo de Dados Legislativo (§22.4)
- Metodologia & Roadmap (docs)
- READMEs de Modulos & Transparencia
- Proxy SSE (rota FE)
- Tribuna & Cronometro (FE)
- Parser SSE (frames)
- Forma A2 da DSL & Marca
- Console do Operador & Admin (telas)
- Telas de IA (editor/ata/autoria)
- Paineis da Mesa & Saude Institucional
- Estrategia de Produto (apostas/publicos)
- Telas de IA (editor/ata/autoria)
- Portabilidade & Grant de Suporte (LGPD)
- Servicos Docker Compose
- Login & gov.br (telas)
- Config Next.js
- Decisoes de Discovery (ICP/ata)
- Prototipos (motor-dsl/governanca-IA)
- Config ESLint
- Next.js env types
- Config PostCSS
- Central de Notificacoes (tela)
- Assinatura em 2 Toques (tela)
- Julgamento de Contas (tela)
- Legendas ao Vivo / LBI (tela)
- Legislacao Municipal (tela)
- Ouvidoria Lei 13.460 (tela)
- Edicao de Proposicao (backend CAS)
- Servico Keycloak
- Servico MinIO
- Servico Valkey
- Templates do Motor (Eixo C)
- Asset file.svg
- Asset globe.svg
- Asset next.svg
- Asset vercel.svg
- Asset window.svg
- Bottom Tab Bar (PWA)
- Campos de Formulario (receita)
- Cartao .card (receita)
- Faixa de Legenda (receita)
- Reordenacao Acessivel (receita)
- Tela: Comissoes
- Session Log 2026-06-20
- Session Log 2026-06-26
- Session Log 2026-06-27

## God Nodes (most connected - your core abstractions)
1. `compilerOptions` - 16 edges
2. `Design system "República Luminosa" (dual-theme visual language)` - 15 edges
3. `§22.9 Stack técnico e infraestrutura da V1` - 15 edges
4. `useAuth()` - 13 edges
5. `camelizarChaves()` - 12 edges
6. `O Plenário Design System README` - 12 edges
7. `sistema/chassi.css` - 12 edges
8. `documento-mestre-camaras.md (canonical source of truth)` - 11 edges
9. `08 · Plano de Validação` - 11 edges
10. `13 · Decomposição em features da V1` - 11 edges

## Surprising Connections (you probably didn't know these)
- `Dor de busca semântica de projetos antigos` --semantically_similar_to--> `Tela: Proposições (lista/tabela filtrável)`  [INFERRED] [semantically similar]
  respostas-entrevistas.md → produto/design-system/o-plenario/telas/proposicoes.html
- `Desejo de ata automática gerada por IA (dor nº1 do servidor)` --semantically_similar_to--> `Tela: Nova proposição (wizard de protocolo)`  [INFERRED] [semantically similar]
  respostas-entrevistas.md → produto/design-system/o-plenario/telas/protocolo.html
- `oplenario.compliance module README` --semantically_similar_to--> `Disciplina 6 — padrão 'prazo de domínio' (proposicao_prazo_ativo -> prazo_dominio_ativo)`  [INFERRED] [semantically similar]
  apps/backend/src/oplenario/compliance/README.md → arquitetura/22-4-dados-legislativo.md
- `Session log 2026-06-22 (migrations security review, doc extraction, design-do-zero start)` --conceptually_related_to--> `apps/backend/STRUCTURE.md — monolito modular architecture doc`  [INFERRED]
  .remember/today-2026-06-22.done.md → apps/backend/STRUCTURE.md
- `Session log 2026-07-05 (F6c artefato publicação/exibição, F7 painéis, FE-5 dashboard Mesa, FE-6 Portal Cidadão)` --references--> `CLAUDE.md (project brief, auto-read by Claude Code)`  [EXTRACTED]
  .remember/today-2026-07-05.md → README.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **F0–F7 sequential backend execution phases of O Plenário** — oplenario_project, f0_plataforma_base, f1_cadastros_identidade, f2_resolvedor_fatos, f3_legislativo, f4_sessoes, f5_compliance_remessa, f6_participacao, f7_paineis_pendencias [INFERRED 0.85]
- **CI enforces architectural invariants via import-lint, leak-test, and authz gates** — github_workflows_ci_ci_workflow, import_lint_gate, leak_test_3dim, authz_policy_check, ports_adapters_nubank_pattern [EXTRACTED 1.00]
- **Frontend waves (Onda A/B) port the República Luminosa design system into Next.js screens** — fe_onda_a_mfe1, fe_onda_b_proposicoes, design_system_republica_luminosa [INFERRED 0.85]
- **Trio de tenancy do motor: definição (schema motor, sem ente_id) + binding (motor, tenant) + obrigação/avaliação (schema compliance, tenant)** — arquitetura_22_7_eixo_b_schema, arquitetura_22_7_eixo_runtime, apps_backend_src_oplenario_motor_schema_motor, apps_backend_src_oplenario_compliance_prazo_dominio_ativo [EXTRACTED 1.00]
- **Uma DSL/motor declarativo compartilhado por 4 usos: tramitação, autorização, regras de plenário e compliance** — arquitetura_22_4_eixo_c_tramitacao, arquitetura_22_5_eixo_b_autorizacao_hibrida, arquitetura_22_6_eixo_g_realtime_sse, arquitetura_22_7_forma_a2 [EXTRACTED 1.00]
- **Stack local Dockerizada (§22.9 Eixo 5): compose orquestra postgres/valkey/minio/keycloak + migrate + app + frontend** — apps_backend_docker_compose, arquitetura_22_9_eixo5_k8s, apps_frontend_readme_rodar_local [EXTRACTED 0.95]
- **Eixo C stress-test: 4 templates T1-T4 produzem os 4 achados estruturais S1-S4** — concept_template_t1_remessa_mensal_sim, concept_template_t2_transparencia_tempo_real, concept_template_t3_publicacao_ato_legislativo, concept_template_t4_maioria_emenda_lom, concept_s1_obrigacao_temporal, concept_s2_dominio_em_camadas, concept_s3_prazo_multifonte, concept_s4_quorum_e_guard [EXTRACTED 1.00]
- **F2 KEYSTONE: RegistroFatos + assert de costura + split builtin/fato + inversão de dependência compõem o resolvedor de fatos** — concept_registrofatos_component, concept_assert_costura_boot, concept_split_builtin_fato, concept_inversao_dependencia_host, concept_convencao_fn_tx_args [EXTRACTED 1.00]
- **Dashboard da Mesa: composição cross-módulo por inversão de dependência (cadastros+sessoes+participacao+legislativo → paineis)** — concept_membros_da_casa_repocadastros, concept_resumo_presenca_sessoes, concept_esic_cumprimento_participacao, concept_relatores_pendentes_legislativo, concept_mesaout_wire_paineis [EXTRACTED 1.00]
- **Trilho de discovery de mercado — perguntas Q1-Q11 respondidas por 3 rodadas desk + campo** — produto_08_plano_validacao, produto_09_desk_rodada1_achados, produto_11_desk_rodada2_achados, produto_12_campo_q5_q7_achados, concept_icp_camara_20_50k [EXTRACTED 1.00]
- **Série de auditorias de completude (r1→r4) convergindo na lista estável de gates de go-live** — produto_14_revisao_completude_features, produto_16_revisao_completude_rodada2, produto_17_revisao_completude_rodada3, produto_18_revisao_completude_rodada4_convergencia, concept_gates_golive [EXTRACTED 1.00]
- **Pipeline do design system: linguagem visual → inventário de telas → guidelines-checklist → camada de atenção** — produto_design_system_o_plenario_linguagem_visual, produto_design_system_o_plenario_inventario_telas, produto_design_system_o_plenario_guidelines_checklist, produto_19_camada_atencao_por_ator [INFERRED 0.85]
- **Fase A (26/06) — padrões promovidos ao chassi.css simultaneamente** — concept_cartao_de_sinal, concept_faixa_de_azulejo, concept_ilha_palco, concept_ilha_papel, concept_chips_de_status, concept_selo_encadeado_auditoria, concept_passos_do_wizard, concept_botao_govbr_oficial, sistema_chassi_css [EXTRACTED 1.00]
- **Camada de confiança da IA aplicada em múltiplas superfícies (portal, editor, ata, protocolo)** — concept_camada_de_confianca_ia, produto_design_system_o_plenario_telas_editor_proposicao, produto_design_system_o_plenario_telas_ata_revisao [EXTRACTED 1.00]
- **Três direções visuais exploradas e rejeitadas em favor da República Luminosa** — produto_design_system_o_plenario_opcoes_a_modernismo_civico, produto_design_system_o_plenario_opcoes_c_pedra_e_bronze, produto_design_system_o_plenario_opcoes_d_o_registro, produto_design_system_o_plenario_linguagem_visual [EXTRACTED 1.00]
- **Expediente + Protocolo Geral + Pauta/Convocação + Minhas pendências formam o fluxo do servidor no balcão administrativo** — produto_design_system_o_plenario_telas_expediente, concept_protocolo_geral, produto_design_system_o_plenario_telas_pauta_convocacao, produto_design_system_o_plenario_telas_minhas_pendencias [INFERRED 0.80]
- **Portal do Cidadão, e-SIC amplo e Portal do titular LGPD formam os dois balcões de direito do cidadão** — produto_design_system_o_plenario_telas_portal_cidadao, concept_esic_amplo, concept_portal_titular_lgpd [EXTRACTED 0.90]
- **Painéis da Mesa, Invariante 4 e TCE-CE formam a materialização visual de confiança operacional para o público presidente da Mesa** — produto_design_system_o_plenario_telas_paineis_mesa, invariante_4_regras_dado, tce_ce, publico_presidente_mesa [INFERRED 0.85]
- **Faixa de azulejo de Bulcão como assinatura visual recorrente** — design_system_azulejo_bulcao, produto_design_system_o_plenario_telas_pos_aprovacao_tela, produto_design_system_o_plenario_telas_sessao_ao_vivo_tela, produto_design_system_o_plenario_telas_telao_votacao_tela, produto_design_system_o_plenario_telas_tramitacao_board_tela, produto_design_system_o_plenario_telas_proposicoes_tela [INFERRED 0.85]
- **App do vereador — cockpit mobile PWA integrado à sessão ao vivo** — produto_design_system_o_plenario_telas_vereador_app_tela, produto_design_system_o_plenario_telas_vereador_estatisticas_tela, produto_design_system_o_plenario_telas_sessao_ao_vivo_tela [INFERRED 0.80]
- **Idioma compartilhado rules-as-data fail-closed entre motor de compliance e governança de IA** — prototipos_motor_dsl_readme, prototipos_governanca_ia_readme, invariante_4_regras_como_dados [INFERRED 0.85]
- **Fatias verticais do Track FE (Onda A1/A2/B1/B2)** — docs_superpowers_specs_2026_07_04_dashboard_mesa_slice1_design, docs_superpowers_specs_2026_07_04_portal_cidadao_a2_design, docs_superpowers_specs_2026_07_05_onda_b_slice1_proposicoes_lista_design, docs_superpowers_specs_2026_07_05_onda_b_slice2_editor_proposicao_design [EXTRACTED 0.90]
- **Composição por inversão de dependência (host injeta fn do módulo dono)** — docs_superpowers_specs_2026_07_04_dashboard_mesa_slice1_design_inversao_dependencia, docs_superpowers_specs_2026_07_05_onda_b_slice2_editor_proposicao_design_resolver_municipio, docs_superpowers_specs_2026_07_04_dashboard_mesa_slice1_design_mesa_out, documento_mestre_camaras_monolito_modular [EXTRACTED 0.85]
- **Faixa de azulejo da tramitação reusada por múltiplas telas** — docs_superpowers_specs_2026_07_04_portal_cidadao_a2_design_azulejo_faixa, docs_superpowers_specs_2026_07_04_portal_cidadao_a2_design_tramitacao_vista, docs_superpowers_specs_2026_07_05_onda_b_slice1_proposicoes_lista_design_azulejo_mini [EXTRACTED 0.85]

## Communities (76 total, 29 thin omitted)

### Community 0 - "Azulejo de Tramitacao (componentes)"
Cohesion: 0.05
Nodes (49): BalcaoEsic(), EstadoBusca, DestaqueTramitacao(), destaque, classeIcone(), MaisTramitacao(), SecaoEmTramitacao(), materiaFake (+41 more)

### Community 1 - "View-model da Lista de Proposicoes"
Cohesion: 0.06
Nodes (40): BalcaoLgpd(), DIREITOS, iniciais(), Capa(), CARTOES, NavegacaoCivica(), PaginaFichaMateria(), PaginaPortalCidadao() (+32 more)

### Community 2 - "Layout & Auth Shell (FE)"
Cohesion: 0.06
Nodes (35): AUTOR_TIPOS, ESPECIES, FormularioProposicao(), ValoresFormulario, VAZIO, PaginaEditarProposicao(), PaginaCriarProposicao(), FILTROS_INICIAIS (+27 more)

### Community 3 - "Dashboard da Mesa (FE)"
Cohesion: 0.06
Nodes (44): DespachosDaMesa(), LenteJuridico(), diasAte(), OQueVence(), OrgulhoInstitucional(), PaginaDashboardMesa(), CORES_ESTAGIO, PipelineLegislativo() (+36 more)

### Community 4 - "Mercado, GTM & Concorrencia"
Cohesion: 0.07
Nodes (56): Catálogo de 113 features / 12 módulos, Módulo 16.12 Console do Operador SaaS (admin_sistema, supratenant), Ata automática por IA (feature-âncora HERO), Camada de atenção por ator (features ATN-*), CMFor 360 / MaraIA (Fortaleza, construção interna), Dispensa de licitação (Lei 14.133/2021 art. 75 II, teto R$65.492,11), Lista estável de gates de go-live (6/7 classes gateiam), Govsys/Legiflow (LegIA) — especialista líder em IA (+48 more)

### Community 5 - "Motor de Compliance · Schema & Eixos"
Cohesion: 0.05
Nodes (51): Assert de costura no boot — catálogo de assinaturas ⋈ registry de fns (fail-closed), B1 — tabela template_compliance (definição de domínio, sem ente_id), B2 — tabela compliance_regra_tenant (binding por tenant), B3 — registry de funções de relação + tipos (catálogo infra compartilhada), B4 — calendario_feriado + prazo_dominio_vigente (prazo como dado), Convenção de invocação (fn tx & args) — tx injetada, data como arg comum do DSL, Decisão: separar definição da regra (domínio, sem ente_id) do binding por tenant (com ente_id), E1 — tabela jurisdicao_camara (resolução câmara→tribunal, default+override) (+43 more)

### Community 6 - "Proposicoes & Composicao Cross-modulo"
Cohesion: 0.06
Nodes (44): AzulejoFaixa + tramitacao-vista.ts — faixa de azulejo da tramitação (view-model + SVG), AzulejoMini — variante compacta do AzulejoFaixa para coluna Situação da tabela de proposições, Balcão e-SIC + Balcão LGPD/Encarregado — direitos de acompanhamento público, boundary.ts camelizarChaves/paraCamel — boundary kebab→camel obrigatório, portal-api.ts buscarPublico<T> — cliente de leitura pública sem Authorization, card-seguro — degradação por card (fail-safe, nunca derruba a página inteira), wire/in/proposicao.clj CriarProposicao/EditarProposicao — corpos POST/PATCH, db/proposicao.clj editar! — PATCH parcial (CAS por lock-version, guard estado terminal) (+36 more)

### Community 7 - "Stack Local & Contrato core-IA"
Cohesion: 0.06
Nodes (41): apps/backend/docker-compose.yml (stack local), apps/frontend README (Next.js, O Plenário), Fatia FE.1: painel do plenário ao vivo (HERO M4), Ordem de portação das telas (docs/13 Track FE), Rodar local: docker compose (postgres/valkey/minio -> migrate -> app -> frontend), Frontend stack: Next.js 16 self-host + Tailwind 4 + theming + proxy same-origin, apps/mobile README (Flutter, esqueleto de intenção), App do vereador (Aposta 2): votar, ler pauta, assinar 2 toques (+33 more)

### Community 8 - "Track FE · Specs & Fundacao"
Cohesion: 0.07
Nodes (41): CLAUDE.md (project brief, auto-read by Claude Code), Spec — Track FE Slice 1 · Dashboard da Mesa (Onda A1), App Shell interno (AuthContext + layout autenticado), Codegen Malli→TS (oplenario.codegen.malli-ts), Composição por inversão de dependência (host injeta fn do módulo dono), MesaOut (read-model composto do /paineis/mesa), mesa-vista.ts (view-model puro do dashboard), Spec — Onda A2 · Portal do Cidadão (shell público white-label) (+33 more)

### Community 9 - "Roadmap de Fases (F0-F7)"
Cohesion: 0.10
Nodes (32): admin_sistema/ — supratenant SaaS operator module, ADR-0001 — estrutura de pastas + silhueta de módulo, apps/backend/STRUCTURE.md — monolito modular architecture doc, authz / policy.check tests (§22.5), F0 — Plataforma base (kernel, outbox, RLS/tenancy, policy.check, MinIO, CI), F1 — Cadastros + Identidade (relações temporais, split de privilégio CPF), F2 — Resolvedor de fatos (KEYSTONE) for motor/authz/tramitação, F3 — Legislativo (proposições, emendas, apensação, pareceres, votação, pós-aprovação, expediente) (+24 more)

### Community 10 - "Arquetipos de Design & Telas"
Cohesion: 0.11
Nodes (32): Arquétipo de design: cockpit, Arquétipo de design: display (projeção), Arquétipo de design: leitura pública, Arquétipo de design: lista/tabela filtrável, Arquétipo de design: wizard, Azulejo de Bulcão / faixa de tramitação (assinatura visual), Camada de confiança da IA (rótulo + revisão humana), Charts honestos (sem donut/KPI-vaidade) (+24 more)

### Community 11 - "Dependencias NPM (frontend)"
Cohesion: 0.07
Nodes (27): dependencies, next, react, react-dom, devDependencies, eslint, eslint-config-next, jsdom (+19 more)

### Community 12 - "Pagina do Plenario ao Vivo (FE)"
Cohesion: 0.10
Nodes (9): ConteudoPlenario(), FASES, NOME_FASE, NOME_TIPO_ITEM, NOME_VOTO, ORDEM_FASE, Painel(), useAgora() (+1 more)

### Community 13 - "Config TypeScript"
Cohesion: 0.10
Nodes (19): compilerOptions, allowJs, esModuleInterop, incremental, isolatedModules, jsx, lib, module (+11 more)

### Community 14 - "Portal do Cidadao & Direitos (e-SIC/LGPD)"
Cohesion: 0.12
Nodes (17): Aposta de produto 1: IA como copiloto legislativo, e-SIC amplo (balcão de direito, correção da 6.1 ilegal), Moderação de comentários (fila, arquétipo), Observabilidade da IA (IA-ops), Parecer de comissão (relatório/análise/voto), Perfil público do vereador (autoria/votos/agenda), Portal do titular LGPD (balcão Meus dados), Portal do Cidadão white-label (+9 more)

### Community 15 - "Reducer de Eventos do Plenario"
Cohesion: 0.24
Nodes (13): EventoPlenario, SessaoOut, aplicarEvento(), estadoInicial(), EstadoPlenario, Inscrito, OradorAtual, PRESENCA_POSITIVA (+5 more)

### Community 16 - "Contrato de Eventos & Pauta (SSE)"
Cohesion: 0.13
Nodes (14): FalaCronometro, FalaEncerrada, FalaIniciada, InscricaoDesistida, InscricaoRegistrada, PautaItemOut, PautaOut, PresencaRegistrada (+6 more)

### Community 17 - "Arquetipos & Telas de Cadastro/Config"
Cohesion: 0.14
Nodes (15): Arquétipo: Balcão de trabalho, Arquétipo: Cabine ao vivo, Arquétipo: Calendário, Arquétipo: Cockpit de governança, Arquétipo: Config / admin, Arquétipo: Display / projeção, Arquétipo: Ficha/detalhe, Arquétipo: Leitura pública (+7 more)

### Community 18 - "Linguagem Visual Republica Luminosa"
Cohesion: 0.22
Nodes (13): Guidelines-checklist (gate de revisão de tela), "República Luminosa" (Cívico Tropical) — linguagem visual, GUIDELINES-CHECKLIST.md, INVENTARIO-TELAS.md, LINGUAGEM-VISUAL.md, O Plenário — Guidelines-checklist, O Plenário — Linguagem Visual, Direção A — Modernismo Cívico (rejeitada) (+5 more)

### Community 19 - "Placar de Votacao (view-model)"
Cohesion: 0.23
Nodes (10): Placar(), conta(), derivarPlacar(), RESULTADO_PERMITIDO, resultadoSeguro(), VistaNominal, VistaPlacar, VistaSecreta (+2 more)

### Community 20 - "Runtime de Compliance & Remessa TCE"
Cohesion: 0.21
Nodes (12): compliance.compliance_avaliacao (append-only, prova de compliance), compliance.prazo_dominio_ativo (obrigação com prazo, polimórfica), oplenario.compliance module README, relação remessa_enviada (F5.3a, só aceita cumpre obrigação), compliance.remessa_gerada (artefato de envio ao TCE, imutável por versão), Disciplina 6 — padrão 'prazo de domínio' (proposicao_prazo_ativo -> prazo_dominio_ativo), Eixo C — stress-test da DSL com templates reais do TCE-CE (achados S1-S4), Eixo de expansão a outros TCEs (§22.7.9, fecha §22.7) (+4 more)

### Community 21 - "Expediente, Pauta & Protocolo (telas)"
Cohesion: 0.17
Nodes (12): Ilha de papel (arquétipo de documento em papel), Livro de atas (ata em papel + lombada de atas anteriores), Minhas pendências (vence hoje / prazo legal / no seu ritmo), Builder de pauta + convocação (Expediente/Ordem do Dia), Protocolo Geral (numerador único, livro do protocolo), República Luminosa (linguagem visual do design system), Tela: Expediente · Gerar documento, Tela: Livro de atas (+4 more)

### Community 22 - "Monolito Modular & Bounded Contexts"
Cohesion: 0.20
Nodes (11): schema motor: 5 tabelas estáticas (migration …0006-motor-catalogo), Módulo admin_sistema (supratenant, área do operador SaaS), 7 bounded contexts de domínio (identidade, cadastros, legislativo, sessoes, transparencia, participacao, compliance), Comunicação entre módulos só por HTTP ou eventos, nunca import direto, Enforcement: import-lint (clj-kondo) + testes de vazamento cross-tenant/cross-esfera, Kernel: eventos/ids/tempo/malli/autorizacao/db_tipos/components — nunca importa módulo, Módulos de projeção: paineis + tempo_real (read-models sobre o bus, não donos de verdade), §22.10 Organização do monólito modular (+3 more)

### Community 23 - "Camada de Atencao & Chassi de Componentes"
Cohesion: 0.27
Nodes (11): Anel de prazo (donut honesto), Botão gov.br oficial, Camada de atenção (attention layer UI pattern), Cartão de sinal (camada de atenção), Chips de status / prazo / semáforo, Faixa de azulejo (assinatura visual da tramitação), Faixa de azulejo da tramitação (etapas + datas, stepper), Ilha-palco (telão escuro) (+3 more)

### Community 24 - "Motor DSL · Nucleo (Clojure)"
Cohesion: 0.20
Nodes (10): gerador_remessa (renderizador do descritor declarativo de layout), RepoCompliance component (avaliar-obrigacao!, F5.1), motor.api (fachada pública, verificar-fonte real), motor.catalogo (registry/catálogo B3, §22.7.6), motor.nucleo (lexer+parser+AST+loader do envelope), oplenario.motor README (DSL/regras de compliance, biblioteca compartilhada), motor.runtime (avaliador tree-walk + loop materializa->avalia->monitora->audita), motor_test.clj (suíte de aceitação, kaocha unit) (+2 more)

### Community 25 - "Modelo de Dados Legislativo (§22.4)"
Cohesion: 0.22
Nodes (10): motor.db (persistência stub, deferida §22.4.4), §22.4 Modelo de dados do processo legislativo, Disciplina 5 — motor declarativo compartilhado (tramitação e compliance), Eixo A — STI híbrido para proposições, Eixo C — Tramitação: máquina de estados declarativa + DSL pequena, Eixo D — Emendas como entidade própria, Eixo G — Votação: votacoes+votos+votos_secretos, quórum enum, Eixo B — Modelo de autorização híbrido pragmático (RBAC + DSL sobre funções de relação) (+2 more)

### Community 26 - "Metodologia & Roadmap (docs)"
Cohesion: 0.24
Nodes (10): docs/00-estado-e-roadmap.md — Estado atual e roadmap de §22.7, motor-dsl/ avaliador executável da DSL (zero-dep Python, 39 checagens verdes), Roadmap tabela dos eixos de §22.7 (A/C/B/Runtime/Impl/+2/LLM), docs/01-metodologia.md — Metodologia de trabalho, Princípio: documento-mestre é o artefato de handoff entre sessões, Fluxo eixo por eixo: abrir->debater->confirmar->consolidar, Régua das 4 perguntas — filtro de escopo da V1 (§15), Viés por consistência disciplinar — estender padrões existentes (+2 more)

### Community 27 - "READMEs de Modulos & Transparencia"
Cohesion: 0.22
Nodes (9): oplenario.cadastros module README, oplenario.identidade module README, oplenario.participacao module README, oplenario.sessoes module README, oplenario.transparencia module README (portal público §16.5), Slice 1: read-model público materia/norma, Slice 2: acompanhamento (subscrição do cidadão, consent-gated), Slice 4b: artefato_publicacao (DO-lite, rota pública de download binário) (+1 more)

### Community 28 - "Proxy SSE (rota FE)"
Cohesion: 0.31
Nodes (3): GET(), proxiarPlenario(), FetchFn

### Community 29 - "Tribuna & Cronometro (FE)"
Cohesion: 0.36
Nodes (5): Topo(), Tribuna(), formatarTempo(), segundosDecorridos(), MarcoCronometro

### Community 30 - "Parser SSE (frames)"
Cohesion: 0.53
Nodes (4): consumirSse(), extrairFrames(), FrameSse, valorCampo()

### Community 31 - "Forma A2 da DSL & Marca"
Cohesion: 0.33
Nodes (6): Forma A2 — núcleo de expressão + envelopes YAML por contexto (Eixo A dec. 1), docs/02-eixo-A-fechado-rascunho.md — Eixo A vocabulário DSL, rascunho de origem, Rascunho: decisão forma A2 (núcleo de expressão + envelopes YAML), Checks pendentes: domínio oplenario.com.br + busca INPI (NCL 9/42/45), docs/04-nome-e-marca.md — Nome e marca: O Plenário, Nome escolhido: O Plenário — tagline 'Onde a câmara acontece'

### Community 32 - "Console do Operador & Admin (telas)"
Cohesion: 0.33
Nodes (6): Arquétipo: Lista/tabela filtrável, Selo encadeado / auditoria append-only, Switch (toggle on/off), Tela: Usuários e acessos (admin-usuarios.html), Tela: Console do operador (supratenant), Tela: Console do operador — Câmara de Fortaleza (tenant)

### Community 33 - "Telas de IA (editor/ata/autoria)"
Cohesion: 0.53
Nodes (6): Camada de confiança da IA, Ilha-papel (documento), Pino/marca ancorada ↔ painel (IA lê ESTE conteúdo), Player de áudio (a fonte do ASR), Tela: Revisar ata gerada por IA, Tela: Editor de proposição · copiloto legislativo

### Community 34 - "Paineis da Mesa & Saude Institucional"
Cohesion: 0.33
Nodes (6): Charts honestos (sem donut/KPI-card), Ilha-placar da saúde institucional, Invariante 4 — regras de compliance são dados, não código, Tela: Painéis da Mesa · Saúde institucional, Público decisor: presidente da Mesa, TCE-CE (Tribunal de Contas do Estado do Ceará)

### Community 35 - "Estrategia de Produto (apostas/publicos)"
Cohesion: 0.33
Nodes (6): Shell público white-label (superficie-publica, sem auth-guard), Ata automática por IA (feature-âncora, modo produtividade), Camada de confiança da IA (citação, incerteza, log, reportar erro), Rota D — legislativo como wedge com roadmap para suite, Três apostas de produto da V1 (IA copiloto, UX 3 públicos, confiança operacional), Três públicos decisores em licitação (servidor, Mesa, jurídico)

### Community 36 - "Telas de IA (editor/ata/autoria)"
Cohesion: 0.40
Nodes (5): Arquétipo: Wizard / multi-passo, Nota [GAP] / [Regimento], Passos do wizard (indicador 1..n), Tela: Anexar ata externa, Tela: Autoria e apoiamento

### Community 37 - "Portabilidade & Grant de Suporte (LGPD)"
Cohesion: 0.40
Nodes (5): Exportar dados do ente (portabilidade, sem lock-in), Grant de suporte gated pelo DPO (LGPD, console operador), Tela: Exportar dados da Câmara, Tela: Acesso de suporte · Privacidade da Câmara, Público decisor: jurídico/administrativo

### Community 38 - "Servicos Docker Compose"
Cohesion: 0.67
Nodes (4): docker-compose service: app (oplenario_pool role), docker-compose service: frontend (Next.js dev), docker-compose service: migrate, docker-compose service: postgres

### Community 39 - "Login & gov.br (telas)"
Cohesion: 0.50
Nodes (4): Arquétipo auth (login/MFA), Login via gov.br (guarda anti-takeover), Tela: Entrar para participar (gov.br), Tela: Acesso ao sistema (Login/MFA)

### Community 41 - "Decisoes de Discovery (ICP/ata)"
Cohesion: 0.67
Nodes (3): ICP resolvido: câmara 20-50k hab do NE fora do CE, com gatilho TCE, T7 resolvida — ata-por-IA entra na V1 como feature-âncora, produto/00 — Índice de Discovery de Produto/Comercial

### Community 42 - "Prototipos (motor-dsl/governanca-IA)"
Cohesion: 1.00
Nodes (3): Invariante 4 — regras de compliance são dados, não código, governanca-ia-clj — filtro de governança da porta de IA (protótipo), motor-dsl-clj — Motor de regras de compliance (Clojure)

## Knowledge Gaps
- **281 isolated node(s):** `eslintConfig`, `nextConfig`, `RFC-1918`, `name`, `version` (+276 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **29 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `O Plenário Design System README` connect `Linguagem Visual Republica Luminosa` to `Camada de Atencao & Chassi de Componentes`, `Stack Local & Contrato core-IA`?**
  _High betweenness centrality (0.042) - this node is a cross-community bridge._
- **Why does `§22.9 Stack técnico e infraestrutura da V1` connect `Stack Local & Contrato core-IA` to `Linguagem Visual Republica Luminosa`?**
  _High betweenness centrality (0.041) - this node is a cross-community bridge._
- **Why does `O Plenário — Linguagem Visual` connect `Linguagem Visual Republica Luminosa` to `Mercado, GTM & Concorrencia`, `Camada de Atencao & Chassi de Componentes`?**
  _High betweenness centrality (0.027) - this node is a cross-community bridge._
- **What connects `eslintConfig`, `nextConfig`, `RFC-1918` to the rest of the system?**
  _309 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Azulejo de Tramitacao (componentes)` be split into smaller, more focused modules?**
  _Cohesion score 0.051490514905149054 - nodes in this community are weakly interconnected._
- **Should `View-model da Lista de Proposicoes` be split into smaller, more focused modules?**
  _Cohesion score 0.05576923076923077 - nodes in this community are weakly interconnected._
- **Should `Layout & Auth Shell (FE)` be split into smaller, more focused modules?**
  _Cohesion score 0.05803571428571429 - nodes in this community are weakly interconnected._