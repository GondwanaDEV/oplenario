# 14 · Revisão de completude das features da V1 (gate pré-design)

> **Trilha de produto/UX — gate antes do design.** Auditoria crítica de completude do catálogo de
> `13-decomposicao-features-v1.md` (89 features / 12 módulos), feita por **três lentes independentes**
> (arquitetura/interoperabilidade · jurídico-regulatório BR · paridade de mercado + JTBD de persona),
> deduplicada, **filtrada contra o que o catálogo já flagou** (seções 1 e 6 de `13`) e passada pela
> **régua §15** do documento-mestre. Objetivo: achar o que **passou batido** antes de gastar design.
>
> **Convenção:** `[FATO]` (fonte legal/competidor confirmado) · `[INF]` (inferência derivada) · `[REC]`
> (recomendação) · 🔎 (validar em campo/com especialista). **Severidade:** 🔴 BLOQUEIA-DESIGN ·
> 🟠 DECISÃO-DE-ESCOPO · ⚪ NOTAR/DIFERIR.

---

## 0. Veredito

O catálogo é **maduro e disciplinado** — ele mesmo já flagou remessa TCE, billing, grant LGPD, métricas,
Web Push iOS. A revisão **não** os relitiga. O que ela achou e **não** estava flagado tem um padrão
desconfortável pela hora: **as duas superfícies que vamos desenhar agora são onde o catálogo é mais
fino.**

1. **A §16.4 modelou os *outputs* da sessão** (telão, votação, quórum, presença, tribuna) — mas **não a
   *condução* de uma sessão real**: tipos de sessão, convocação formal, incidentes processuais, mesa de
   condução ao vivo. Os mockups `sessao-ao-vivo.html` e `dashboard-mesa.html` assumem a sessão ordinária feliz.
2. **O trabalho diário do servidor não tem superfície.** Gerar ofício/certidão/requerimento a partir de
   modelo — o pão-com-manteiga que o **SAPL grátis** e o **LegisFácil pago** já entregam — está ausente.
   Não há página de "expediente/documentos" no design-system.
3. **A 6.1 (e-SIC restrito a "proposições e atos legislativos") é juridicamente incorreta** — a LAI não
   permite a um órgão recusar pedido por estar "fora do tema". O produto, como está, **induz a câmara a
   descumprir a lei**.

Conclusão: a revisão chegou na hora certa. **Três blocos bloqueiam o design**; o resto é decisão de
escopo ou diferível. **Meta-achado:** ~metade dos gaps são omissões da própria **§16 (o SSOT)**, não só
da decomposição — corrigi-los **reabre a §16**; e o conjunto **aperta ainda mais o §18** (vira
dimensionamento de time, não corte, porque o que falta quebra o fluxo).

---

## 1. 🔴 Tier 1 — Bloqueiam o design (resolver antes de desenhar)

### A. A sessão plenária real não roda end-to-end · §16.4 *(omissão da própria §16)*

| # | Gap | Por que importa | Decisão V1 |
|---|---|---|---|
| **G1** | **Tipos de sessão como entidade de 1ª classe** — ordinária / extraordinária / solene / especial. Cada um com regra própria: extraordinária só tem Ordem do Dia (sem expediente) e é vinculada à convocação; solene não delibera. `[FATO]` SAPL modela "Tipo de Sessão". | 4.1 assume "expediente + ordem do dia" = **só a ordinária**. Sem isso o sistema não roda extraordinária/solene, que a câmara faz o ano todo. Lacuna **estrutural** — vira refactor se descoberta no design. | **ENTRA** |
| **G2** | **Convocação oficial + edital com prazo regimental + ciência registrada** — gerar edital, notificar oficialmente os vereadores, respeitar prazo mínimo da LOM (tipicamente 24–72h). `[FATO]` ato regimental obrigatório. | Convocação fora do prazo **anula a sessão** (objeção do jurídico). Hoje só há "pauta" (4.1) e "notificação push" (7.4) — nenhum é o **ato jurídico** com prova de ciência. | **ENTRA** |
| **G3** | **Incidentes processuais da sessão** — questão de ordem, pedido de vista, votação em bloco de emendas, verificação de votação, votação de urgência, retirada de pauta, encaminhamento de votação. `[FATO]` institutos regimentais padrão. | Uma sessão real **trava** sem "pedido de vista" e "votação em bloco". Na demo ao vivo — o momento que decide a POC — a falta deles faz o produto parecer brinquedo. | **ENTRA** (comuns; raros podem diferir) |
| **G4** | **Mesa de condução ao vivo** — o painel-de-controle de quem **preside**: abrir/encerrar sessão, conceder/cassar a palavra, abrir/fechar votação, declarar resultado, suspender, registrar deliberação da Mesa. `[INF]` complemento natural do painel de votação. | 4.2 é o **telão** (output), 11.4 é **dashboard** (leitura) — falta a ferramenta de **operação**. A persona presidente/Mesa é quem **assina a compra**; é onde percebe que o produto foi feito para ela. | **ENTRA** |

### B. O servidor não tem onde fazer o trabalho administrativo cotidiano · novo §16.3 / módulo "Expediente"

| # | Gap | Por que importa | Decisão V1 |
|---|---|---|---|
| **G5** | **Geração de documentos a partir de modelos/templates** — ofício, certidão, requerimento administrativo, convite, mala-direta, com merge de dados do domínio. `[FATO]` SAPL tem "Documentos Administrativos/Impressos"; `[FATO]` LegisFácil vende "cria documentos automaticamente: requerimentos, indicações, ofícios, emendas" como manchete. | A IA redige *proposição* (3.11), mas o trabalho **mais frequente** do servidor (dezenas de ofícios/semana) não está. **Maior risco de POC do catálogo inteiro** — o servidor abre, procura "gerar ofício" e não acha; o concorrente grátis e o pago têm. | **ENTRA** (provável nova superfície de design) |
| **G6** | **Protocolo geral / único** — numerador institucional que protocola proposição **e** documento administrativo recebido/expedido (ofício recebido, requerimento de cidadão, processo administrativo). `[FATO]` SAPL tem "Protocolo Geral" distinto de "Recebimento de Proposições". | 3.1 é só "protocolo de proposições". O servidor protocola muito mais que isso; a ausência aparece na demo. | **ENTRA** |

### C. Correção jurídica · §16.6

| # | Gap | Por que importa | Decisão V1 |
|---|---|---|---|
| **G7** | **e-SIC amplo + timer de prazo LAI (20+10 dias) + instância recursal.** A 6.1 restringe e-SIC a "proposições e atos legislativos" — a LAI (12.527/2011) cobre **qualquer** informação pública da câmara (RH, contrato, diárias). `[FATO]` o objeto do SIC **não** pode ser restringido por tema. | A 6.1 **não é "escopo mínimo", é juridicamente errada** — induz a câmara a violar a LAI → recurso → MP. O timer já é modelável pelo `prazo_dominio_ativo` (§22.4.3 disc.6 **nomeia** "prazo LAI" como caso) — gap barato. | **ENTRA** (reescrever 6.1) |

---

## 2. 🟠 Tier 2 — Decisões de escopo (régua §15 aplicada)

Os itens **legais** não são "inflação de escopo": sem eles o produto **induz não-conformidade** ou
fica atrás do concorrente no item que o TCE/MP audita. Última coluna = decisão tomada nesta revisão.

| # | Gap | Lente · âncora | Toca §16? | Decisão V1 |
|---|---|---|---|---|
| **G8** | **Espécies faltantes: Decreto Legislativo, Resolução, Emenda à LOM** (fluxo **sem sanção** do Executivo; Emenda à LOM com quórum 2/3 e dois turnos). `[FATO]` CF art.29. | jurídica | sim (16.3) | **ENTRA** — atos cotidianos com fluxo distinto do PL; detalhe de rito ao especialista de regimento (§22.4.4) |
| **G9** | **Julgamento das contas do Prefeito** (recebe parecer prévio do TCE → julga; rejeição por **2/3**) + contas da Mesa. `[FATO]` CF art.31 §2º; DL 201/67. | jurídica | sim (16.3/16.4) | **ENTRA** — competência-âncora da instituição; reusa votação (16.4) + quórum qualificado (motor S4) + prazo; demo p/ persona presidente. Rito → especialista |
| **G10** | **Audiências públicas obrigatórias** conduzidas/sediadas pela câmara — metas fiscais quadrimestrais (LRF art.9 §4º, perante comissão da casa) e PPA/LDO/LOA (LRF art.48). `[FATO]`. | jurídica | sim (16.4/16.5) | **ENTRA** — "audiência pública" como **tipo de reunião** distinto da sessão plenária; reusa pauta+ata+publicação |
| **G11** | **Coautoria / subscrição / apoiamento** de proposições (múltiplos autores; assinatura de apoio; subscrição de requerimento). `[INF]` SAPL suporta múltiplos autores. | paridade · Aposta 2 | sim (16.3/16.7) | **ENTRA** — requerimentos/moções coletivos são rotina; fura a Aposta 2 sem isso |
| **G12** | **Transparência fiscal/ativa da PRÓPRIA câmara** — execução orçamentária em tempo real (LC 131/2009) + rol mínimo do art.8 da LAI (**remuneração de vereadores e servidores, diárias, licitações e contratos do órgão**). 16.5/§17 jogam "Fora" inteiro delegando ao admin. `[FATO]` a câmara é órgão autônomo (CF art.31) com obrigação **própria e não-delegável de publicar**. | jurídica | **sim — revisa guardrail "Fora" de §16.5/§17** | **DECISÃO TOMADA:** **produzir** o dado fiscal fica Fora (é do contábil/SIAFIC — §17 mantém-se correto); **publicar** a transparência administrativa do **órgão Câmara**, **consumindo** do sistema contábil, **entra** como camada de publicação. Não construímos contábil; cobrimos a vitrine que a lei exige da casa. 🔎 confirmar o conector de consumo com o beachhead |
| **G13** | **Carta de Serviços ao Usuário + ouvidoria com prazo de 30 dias (prorrogável 1x) + relatório anual de gestão + pesquisa de satisfação.** `[FATO]` Lei 13.460/2017. 6.2 entrega só "roteamento básico". | jurídica | sim (16.6) | **ENTRA** — barato; reusa motor de prazo + read-model (16.11); TCE/CGU auditam a existência |
| **G14** | **Portal do titular de dados (LGPD art.18)** — requisição de acesso/correção/eliminação + contato do **Encarregado/DPO** público + base de RIPD. `[FATO]` Lei 13.709/2018 arts.18, 23, 41. 5.5 cita só consent-gate. | jurídica | sim (16.5/16.1) | **ENTRA** — obrigação do controlador; reusa o front do portal cidadão. Distinto do grant-de-suporte do operador (12.7, já flagado) |
| **G15** | **Numeração automática por tipo/ano configurável** (PL 001/2026, reinício anual, reserva/cancelamento de número). `[FATO]` comportamento nuclear do SAPL. | paridade | parcial (explicitar 3.1) | **ENTRA** — implícito em 3.1; numeração errada = nulidade de ato; configurável por câmara |
| **G16** | **Calendário/agenda institucional + recesso legislativo** (calendário de sessões ordinárias, agenda de comissões, reserva de plenário). | paridade | sim (16.2/16.4) | **ENTRA** — **recesso altera a contagem de prazos** (toca o motor §22.7 e os prazos de 3.8); ausência = prazo calculado errado |
| **G17** | **URN/LexML como identidade canônica de norma** (`urn:lex:br;ce;fortaleza:...`) — coordenada **pública e interoperável**, distinta do UUID técnico. `[FATO]` padrão Interlegis/LexML, citado em editais. | arquitetura | **§22.4 eixo H (modelo de dados)** | **ENTRA / decidir já** — base de citação cruzada, consolidação e intercâmbio com SAPL; **retrofitar depois é refactor estrutural** — resolver antes de materializar o modelo de dados |
| **G18** | **Portabilidade / saída do contrato** (off-boarding B2G + LGPD art.18 portabilidade) — dump completo dos dados do ente ao encerrar. 16.9 é só **entrada**; o ciclo `encerrado` (12.1) não tem feature de saída. | arquitetura | sim (16.9/16.10) | **ENTRA** — **objeção jurídica direta em pregão** (princípio do não-aprisionamento); barato no event-driven; desenhar como contrato de saída simétrico ao de entrada |
| **G19** | **Ciclo de vida / observabilidade do modelo de IA** — registro de **qual versão de modelo gerou qual artefato legal**, versionamento, rollback, medição de drift. §22.3.4 cita "eval framework" como substrato; nenhuma feature governa. | arquitetura | sim (16.8 / §22.3.4) | **ENTRA** — a porta vendor-agnóstica (§22.9 Eixo 10) **troca o LLM por config** e o ASR self-host será atualizado → muda a ata (artefato legal `[HERO]`) **sem rastro**. Sem isso não há reprodutibilidade nem auditoria |
| **G20** | **Dados abertos / API de dataset** em formato aberto e legível por máquina. `[FATO]` Decreto 8.777/2016 + LAI art.8 §3 (II–III). O portal (5.x) é HTML p/ humano; API pública está deferida a V2. | jurídica/arquit. | sim (16.5) | **ENTRA mínimo** — publicação em formato aberto é **obrigação de transparência ativa**, não nice-to-have; exportável barato sobre o event-driven |
| **G21** | **Gestão documental: upload validado** (antivírus, allow-list de MIME/tipo, limite de tamanho, hash) p/ anexos de proposição/parecer/protocolo. Há `objeto_store`/MinIO (§22.3.4) e o endpoint de **áudio** (4.8), mas anexo genérico não tem ciclo de vida. | arquitetura | sim (16.3, transversal) | **ENTRA** — a câmara anexa PDF/ofício o tempo todo; sem validação na borda é **vetor de malware** num portal público gov |
| **G22** | **Canal real de integração ao DOM** (formato/protocolo/confirmação de publicação/idempotência). 5.7 cita "feed ao DOM externo (C-2 leve)" como uma linha. | arquit./jurídica | parcial (5.7) | **MANTÉM 🔎** — validar com o beachhead se "publicar oficialmente por nós" é dor de compra; se sim, **mesmo rigor do adapter de remessa TCE** (§22.7.8), não uma menção |

---

## 3. ⚪ Tier 3 — Notar e diferir (com 3 ganhos baratos que recomendo puxar)

| # | Gap | Decisão |
|---|---|---|
| **G23** | **Espelho / ficha da matéria** (ficha-resumo canônica: autoria, ementa, situação, histórico, documentos vinculados, num/ano — imprimível, citável). | **ENTRA barato** — provável read-model sobre o substrato; **nomear** como artefato de 1ª classe (16.11) |
| **G24** | **Livro de atas canônico** (coleção numerada, contínua, imutável das atas da legislatura — o "livro" que o TCE audita). | **ENTRA barato** — projeção sobre atas (4.11/4.13); jurídico procura explicitamente |
| **G27** | **Acessibilidade da transmissão** (legenda/closed caption; janela de Libras). `[FATO]` Lei 13.146/2015 (LBI). | **ENTRA leve** — **a legenda deriva quase de graça da transcrição (4.12)**; é legal + diferenciador de inclusão. Janela Libras = diferível |
| **G25** | Documentos acessórios tipados da matéria (`[FATO]` SAPL tem "Documento Acessório"). | Diferir / nomear como sub-capacidade de 3.x |
| **G26** | Comissão processante / cassação (DL 201/67 — rito garantístico com prazos fatais). | **Diferir** — raro; alto-risco quando ocorre, mas régua §15 p4 (adiar não faz desistir) |
| **G28** | Recepção estruturada do Executivo (mão inversa do autógrafo: mensagem do Prefeito, projeto de iniciativa do Executivo, comunicação de veto). | Diferir — entrada manual na V1 |
| **G29** | Sustação de atos / convocação de secretário / pedidos de informação com prazo de resposta. | Diferir — muitos viram requerimento (3.1); a semântica de controle externo com prazo vem depois |
| **G30** | Política de retenção/expurgo de áudio bruto e transcrições (custo de storage + LGPD). | Diferir — mas **decidir a política** afeta o tripé de backup (§22.3.4); registrar |
| **G31** | Proteção de abuso no portal público (rate-limit/CAPTCHA/moderação como **produto**, não só infra Valkey). | Diferir — endurecer no hardening (M4); comentários (6.3) + e-SIC são alvo de spam |
| **G32** | "Registro de publicação = condição de eficácia" (data/veículo da publicação do ato). Faceta, não o DOe inteiro. | **ENTRA fino** — fecha o elo que torna a lei vigente em 3.15; ≠ ser o DOe de registro (V1.5) |
| **G33** | Outbox/consumo read-only exposto p/ o portal de transparência geral do município consumir nossos dados. | Diferir — a promessa "integramos por consumo" (§16.5) precisa de **um** ponto de extração; mínimo |
| **G34** | Padrão de intercâmbio Interlegis/SAPL (import/export no vocabulário do ecossistema). | Diferir p/ o satélite de migração (16.9); mas **falar o formato do SAPL** é wedge de migração + antídoto de lock-in |

---

## 4. Meta-achados

1. **~Metade dos gaps são omissões da própria §16 (o SSOT)**, não só da decomposição `13`. Tier 1-A/B/C e
   boa parte do Tier 2 exigem **reabrir a §16** — consolidação sob o protocolo *Confirma?*.
2. **Aperta ainda mais o §18.** A sessão completa (G1–G4) + a superfície de Expediente/documentos
   (G5–G6) somam escopo real a um plano de 4 meses já apertado. Como o que falta **quebra o fluxo / perde
   a POC**, o aperto vira **dimensionamento de time**, não corte — exatamente o que o §18 já sinalizava.
3. **Boa parte reusa substrato já fechado** — motor de prazo `prazo_dominio_ativo` (G7, G10, G13, G16),
   votação + quórum S4 (G9), event-driven/read-model (G18, G20, G23, G24), assinatura ICP (G32). O custo
   marginal real concentra-se em **Expediente/documentos (G5–G6)** e na **sessão completa (G1–G4)**.

---

## 5. Decisões consolidadas (entram na V1)

**Tier 1 (inteiro):** G1, G2, G3, G4, G5, G6, G7.
**Tier 2:** G8, G9, G10, G11, G12 *(só camada de publicação)*, G13, G14, G15, G16, G17, G18, G19, G20, G21.
**Tier 2 mantido 🔎:** G22 (validar com beachhead).
**Tier 3 — ganhos baratos puxados:** G23, G24, G27 *(legenda)*, G32 *(registro de publicação)*.
**Tier 3 — diferidos/notados:** G25, G26, G28, G29, G30, G31, G33, G34.

**O que vira delta à §16/`13`:**
- **§16.3** — espécies G8 + numeração G15 + coautoria G11; nova feature **Expediente/Documentos** (G5/G6).
- **§16.4** — tipos de sessão G1, convocação G2, incidentes G3, mesa de condução G4, audiência pública G10,
  legenda G27, livro de atas G24; calendário/recesso G16 (cadastro em 16.2).
- **§16.5** — transparência fiscal do órgão G12 *(publicação)*, portal do titular LGPD G14, dados abertos G20,
  registro de publicação G32.
- **§16.6** — reescrita da 6.1 (e-SIC amplo + prazo) G7, Carta de Serviços/ouvidoria 13.460 G13.
- **§16.8 / §22.3.4** — ciclo de vida/observabilidade do modelo de IA G19.
- **§16.9 / §16.10** — portabilidade/saída do contrato G18.
- **§16.11** — espelho/ficha da matéria G23.
- **§22.4 eixo H** — URN/LexML G17 (decisão de modelo de dados, antes da materialização).
- **§17** — ajustar o guardrail "Portal da transparência completo = Fora": separar **produção** (Fora) de
  **publicação do órgão** (entra, G12).
- **§18** — nota de aperto: Expediente + sessão completa entram no dimensionamento de time.

---

## 6. Fontes (âncoras legais)

- **LAI** — Lei 12.527/2011 (e-SIC amplo, rol art.8, prazo 20+10, recurso): planalto.gov.br/ccivil_03/_ato2011-2014/2011/lei/l12527.htm
- **Transparência fiscal** — LC 131/2009 (tempo real): planalto.gov.br/ccivil_03/leis/lcp/lcp131.htm
- **Usuário de serviço público** — Lei 13.460/2017 (Carta de Serviços, ouvidoria 30d, relatório): planalto.gov.br/ccivil_03/_ato2015-2018/2017/lei/l13460.htm
- **LGPD** — Lei 13.709/2018 arts.18/23/41 (direitos do titular, poder público, Encarregado): planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm
- **LRF** — LC 101/2000 arts.9º §4º e 48 (audiências fiscais)
- **Competência fiscalizatória** — CF art.31; DL 201/1967 (julgamento de contas; rito de cassação)
- **Acessibilidade** — Lei 13.146/2015 (LBI); Lei 10.098/2000
- **Dados abertos** — Decreto 8.777/2016; LAI art.8 §3
- **Interoperabilidade** — LexML Brasil / Interlegis (URN legislativa)
- **Competidores** — SAPL (Interlegis/Senado): módulos "Protocolo Geral", "Documentos Administrativos",
  "Documento Acessório", "Tipo de Sessão", geração de pauta/ata PDF · LegisFácil: geração automática de
  documentos legislativos como manchete de produto. Detalhe em `produto/02-concorrencia.md`.
