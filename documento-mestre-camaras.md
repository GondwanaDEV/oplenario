# Documento-Mestre — SaaS para Câmaras Municipais

**Versão:** 1.27
**Data de consolidação:** 20 de junho de 2026
**Status:** documento vivo — atualizar a cada decisão relevante

---

## 1. Identificação do projeto

**O que estamos construindo:** plataforma SaaS de gestão pública para câmaras municipais brasileiras, tratando a câmara como instituição (não sistemas de gabinete de vereador).

**Nome do produto:** **O Plenário** — tagline de trabalho *"Onde a câmara acontece."* Decisão de naming tomada, tratada como **provisória** até fecharem dois checks: disponibilidade de domínio (`oplenario.com.br` e `oplenario.com`) e busca de marca no INPI (classes NCL 9 e 42; considerar 45), com preferência por depósito como marca mista. Padrão de marca "O" + substantivo do domínio (cf. O Globo, O Antagonista). Se um check bloquear, o nome reabre.

**Base geográfica inicial:** Fortaleza / Nordeste.

**Ambição declarada:** plataforma ampla. Alvo de IPO ou exit de R$ 1B+ em 8-12 anos.

**Rota estratégica escolhida:** Rota D — legislativo puro como wedge, com roadmap explícito de expansão para suite completa e, posteriormente, prefeituras.

---

## 2. Racional da Rota D

Entre quatro rotas consideradas (A — legislativo puro sem ambição de expansão; B — suite completa desde o início; C — legislativo + parceria administrativa; D — legislativo puro com roadmap declarado para suite), a Rota D foi escolhida porque:

- É compatível com time enxuto e captação em fases (seed → Series A → Series B).
- Permite time-to-market rápido no ano 1, com foco claro em diferenciação real (IA + UX no legislativo).
- Mantém opções abertas: permite pivotar para C se parcerias se tornarem viáveis, acelerar para B se captação permitir.
- Converte clientes iniciais em base para expansão de conta (NRR) nos anos seguintes, aproveitando NPS alto de servidores que permanecem através de transições políticas.
- A ordem invertida (legislativo antes do administrativo) oferece vantagem estrutural: os incumbentes construíram o administrativo primeiro e enfiaram o legislativo como adjacência — por isso o legislativo deles é ruim.

**O que a Rota D exige aceitar conscientemente:** no ano 1, vamos perder editais de suite completa. Isso é esperado, faz parte da tese. O subconjunto atendível no ano 1 é (a) câmaras que licitam sistema legislativo apartado, (b) câmaras insatisfeitas o bastante com o incumbente legislativo para comprar só o módulo, (c) câmaras novas/reformadas com apetite para inovação.

---

## 3. Três tensões permanentes da Rota D

Decisões ao longo dos próximos 8-12 anos se renegociam em torno de três tensões. Explicitá-las evita que cada decisão vire debate do zero.

**Tensão 1 — Foco de hoje vs. ambição de amanhã.** Regra de arbitragem adotada: decisões arquiteturais podem e devem antecipar a plataforma ampla; decisões de escopo de produto não devem. A arquitetura já é pensada para suite completa e múltiplos entes; os módulos seguem o roadmap apertado.

**Tensão 2 — Cliente atual vs. cliente futuro.** Regra: o cliente inicial define o produto V1, mas o cliente futuro define a arquitetura. O modelo de dados precisa comportar câmara de capital e, eventualmente, prefeitura sem refactor estrutural.

**Tensão 3 — Velocidade vs. solidez institucional.** Regra: o produto pode ser rápido, a operação institucional não pode ser improvisada. Investimento cedo em compliance formal (LGPD), relações jurídicas específicas de contratos públicos e presença nos fóruns setoriais (UVB, IRB, ABRACAM, associações estaduais).

---

## 4. Sequenciamento estratégico de 8-12 anos

**Anos 1-2 — Wedge legislativo.** Meta: 40-80 câmaras no Nordeste. Produto: núcleo legislativo + público com IA e UX superiores. Captação: seed de R$ 5-15M. KPI central: NPS de servidor e vereador, tempo economizado mensurado, retenção >95%.

**Anos 2-4 — Densidade regional + primeiros módulos administrativos.** Meta: 150-300 câmaras, expansão Nordeste → Norte e Centro-Oeste. Construção dos módulos administrativos na ordem da dor do cliente existente, não na ordem lógica de um ERP. Captação: Series A de R$ 30-60M. KPI central: NRR acima de 110%.

**Anos 4-6 — Suite completa para câmaras + preparação para prefeituras.** Meta: 500-1000 câmaras, cobertura nacional. Primeira prefeitura pequena como piloto no ano 5-6. Captação: Series B de R$ 80-200M. KPI central: % de receita vindo de câmaras que usam 4+ módulos.

**Anos 6-10 — Expansão para prefeituras e adjacências.** Meta: share de 15-25% do mercado nacional de câmaras, 50-200 prefeituras pequenas e médias. Captação: Series C ou pre-IPO.

**Anos 10-12 — Consolidação ou saída.** IPO ou aquisição estratégica. Cotadores prováveis: TOTVS, Linx/Stone, grupos estrangeiros de GovTech (Tyler Technologies, Granicus).

---

## 5. Apostas de produto — V1 de lançamento

Três apostas estruturais para a V1, a partir da análise competitiva que identificou cinco fraquezas do mercado (UX datada, portais de fachada, IA superficial, sistemas fechados, migração dolorosa).

**Aposta 1 — IA como copiloto legislativo.** Espaço mapeado em 4 momentos: antes da sessão, durante, depois, transversal. Critérios de priorização das features: demo power + defensibilidade estrutural + ROI demonstrável. Ideal pontuar nos três; só demo power é armadilha.

**Aposta 2 — Experiência de produto moderna para 3 públicos.**
- *Servidor:* interface limpa, atalhos, busca universal, tempo real.
- *Vereador:* app mobile como entrada principal, dashboard pessoal, assinatura em 2 toques, linguagem humana.
- *Cidadão:* portal/app como produto real, acompanhamento personalizado, participação genuína.
- Exige designer de produto sênior dedicado, pesquisa qualitativa contínua, design system próprio desde a V1.

**Aposta 3 — Confiança operacional como diferencial comercial.**
- Migração tratada como feature de produto (ferramenta que ingere dados dos sistemas concorrentes automaticamente, meta de 30 dias).
- SLA específico de uptime para horário de sessão (noites de terça/quarta/quinta).
- Suporte de plantão noturno dedicado a sessões.
- Compliance automático com TCEs estaduais via motor de regras configurável por estado.

**Explicitamente descartado para a V1 (reservado para V2):** plataforma aberta (APIs públicas, conectores nativos com ERPs municipais, marketplace de extensões). Arquitetura interna da V1 já deve ser pensada com separação clara entre dados e apresentação para baratear essa V2 depois.

---

## 6. Funcionalidades de paridade (não diferenciação)

Precisam existir e funcionar bem na V1, mas não são onde a batalha é vencida:

- Assinatura digital ICP-Brasil
- Painel eletrônico de votação em telão
- Portal da transparência legislativa básico
- Transmissão ao vivo via YouTube Live
- Cadastros de vereadores, comissões, mesa diretora

---

## 7. Roadmap em ondas — Aposta 1 (IA)

**Onda 0 — Infra:** pipeline áudio com diarização, corpus legal indexado, pipeline vídeo, modelo de dados do processo.

**Onda 1 — POC / V1 de lançamento (meses 0-4):** busca intra-câmara, resumo cidadão, copiloto de redação de projetos, **e geração automática de ata pós-sessão por IA (decisão 20/06/2026 — feature-âncora de compra; reverte a v1.7).** A ata-IA entra em modo **"produtividade"** (economia de horas do servidor), não "registro oficial", com **revisão humana obrigatória** antes de publicar (§16.8); a anexação de ata redigida externamente pelo servidor segue suportada como caminho coexistente. O pipeline de transcrição da Onda 0 a alimenta; a captação de áudio passa a ser parte da oferta (§16.4, §22.6). O dataset golden (transcrição, ata humana anexada) continua se formando e retroalimenta a qualidade.

**Onda 2 — V1.5 retenção (meses 4-6/8):** shorts automáticos, busca cross-câmara.

**Onda 3 — V2 (meses 6-10):** pauta inteligente, similaridade entre proposições, camada de confiança (citação, incerteza, log, reportar erro — construída como infra reutilizável).

**Onda 4 — V2.5+ (meses 10-18):** chatbot cidadão, briefing personalizado por vereador, pré-análise de constitucionalidade.

**Decisão relevante:** pauta inteligente e análise de similaridade saíram da V1 e foram para V2, porque ambas ficam significativamente melhores com dados que só existem depois de vender.

---

## 8. Três forças que ordenam o roadmap

Decisões de ordem de construção devem balancear três tipos de dependência:

**Dependência técnica** — objetiva, mas a menos estratégica das três.

**Dependência comercial** — o que precisa existir para vender o próximo. Exemplo: ata automática precisa vir antes de shorts porque shorts dependem de transcrição confiável + demo da ata ajuda a fechar a venda inicial.

**Dependência de confiança** — features de alto risco reputacional (constitucionalidade, chatbot público, detecção de conflito de interesse) precisam vir depois de reputação estabelecida. Politicamente não podem ser dia 1, mesmo sendo tecnicamente possíveis.

---

## 9. Dependências cruzadas não-óbvias (mapeadas)

- **Cadeia ata → shorts → resumo cidadão amplifica erros:** bug na ata vira bug triplicado. Qualidade da ata é pré-requisito crítico.
- **Busca semântica é infra triplicada:** usada por copiloto de redação, similaridade, chatbot cidadão. É o pior lugar para economizar.
- **Pauta inteligente tem gargalo de pessoa, não de código:** requer especialista em regimento.
- **Chatbot cidadão e constitucionalidade compartilham camada de confiança** (citação, incerteza, log, reportar erro). Construir como infra reutilizável na onda 2.

---

## 10. Decisões de contratação/arquitetura tomadas cedo

**Especialista em regimento legislativo** (provavelmente ex-servidor de câmara) entra no time desde cedo, mesmo que pauta inteligente só apareça na V2. Justificativa: ata precisa formatar no padrão regimental e copiloto de redação precisa de técnica legislativa adequada.

**Pipeline de áudio + diarização** deve ser arquitetado para processar sessões passadas em bulk, não só ao vivo. Justificativa: primeiro cliente vai querer subir ~200h de arquivo histórico.

---

## 11. Três públicos decisores em licitação de câmara

Cada um com argumento próprio; o pitch precisa ter uma porta de entrada específica para cada um.

**Servidor** — avalia produto na POC. Ganha com IA e UX. É o cliente que *fica* através de transições políticas; peso maior em retenção de longo prazo.

**Presidente / Mesa Diretora** — aprova politicamente. Mandato de 2 anos. Ganha com métricas de engajamento cidadão.

**Departamento jurídico/administrativo** — avalia risco. Ganha com confiança operacional (migração, SLA, compliance).

**Nota — Argumento "zero-friction" sobre a cadeia de captação existente (descoberta v1.6).** Pesquisa qualitativa confirma que câmaras-alvo já operam com cadeias de captação heterogêneas mas estabelecidas — câmaras pequenas tipicamente com OBS Studio + YouTube Live, câmaras médias com appliance integrado (Promic/Riole, Softcam, ESCAL), câmaras grandes com estúdio próprio. Como decidido em §16.4, nossa V1 absorve qualquer cadeia razoável em vez de substituí-la. O argumento decorrente, válido para todos os três públicos da §11 mas especialmente para o jurídico/administrativo, é: "vocês não precisam trocar nada do que já têm — mantenham a cadeia de captação atual (OBS + YouTube, appliance Promic/Riole/ESCAL/Softcam, ou outro setup), mantenham mesa de som, mantenham as câmeras; nós entramos como camada de software por cima". Esse argumento é diferenciador real contra incumbentes que vendem "solução completa de plenário" exigindo substituição de hardware/cadeia (cujo CapEx típico é R$ 50–200k em câmaras pequenas/médias). Nossa proposta entra um nível abaixo desse CapEx, agnóstica à cadeia atual.

**Nota — Posicionamento universal "complementamos, não substituímos".** Para câmaras que já investiram em qualquer hardware ou cadeia de captação (appliance Promic/Riole/ESCAL, OBS profissionalmente montado, estúdio próprio), o pitch é o mesmo: "complementamos, não substituímos seu hardware/cadeia" — somamos camada de software (ata automática, busca, portal moderno) consumindo as gravações que a cadeia atual já produz, via endpoint de ingestão agnóstico (§16.4, §22.3.4). Detalhamento de pitch específico por perfil de câmara é trabalho do time comercial quando o cenário aparecer; aqui fica registrado o posicionamento.

---

## 12. Análise das 5 features originais da Aposta 1

| Feature | Demo power | Moat | Status V1 |
|---|---|---|---|
| Ata automática | Máximo | Fine-tuning local + workflow de revisão | **DENTRO da V1 (decisão 20/06/2026)** — feature-âncora em modo "produtividade", revisão humana obrigatória (§16.8); anexação de ata redigida externamente coexiste |
| Pauta inteligente | Fraco | Modelagem de regimento por câmara | Fora (V2) |
| Busca semântica | Médio | Corpus cross-câmara | V1 |
| Constitucionalidade | Cuidado | Posicionar como pré-análise de alertas, nunca parecer jurídico (risco de oráculo) | Fora (V2.5+) |
| Resumo cidadão | Fraco sozinho | Diferencia em multi-formato | V1 |

---

## 13. Premissas-chave

### Validadas

- **Mercado tem UX fraca.** Verificação direta de Legisoft, Legiflow, Nuvem Legislativa confirmou — é oportunidade competitiva validada, não só inferência.
- **Nordeste tem baixa penetração dos incumbentes Sul/Sudeste** (wedge geográfico real).
- **OBS Studio + YouTube Live é padrão de fato de captação/transmissão em câmaras pequenas e médias** (descoberta v1.6 via pesquisa qualitativa). Câmaras grandes têm estúdio próprio e equipe dedicada; câmaras médias frequentemente usam appliance integrado (Promic/Riole, Softcam, ESCAL); câmaras pequenas — maioria do mercado — operam OBS em PC simples conectado à mesa de som existente, com transmissão direta para YouTube/Facebook e gravação local em paralelo. Em todos os três cenários, YouTube Live é destino dominante por ser gratuito, robusto e já incorporado ao fluxo. Isso reforça a decisão da §16.4 de não construir transmissão própria. **Nuance arquitetural importante:** essa prevalência informa onde concentrar primeiro o esforço de documentação e onde priorizar adapters de captação sincronizada quando o satélite for construído; **não** privilegia OBS como camada arquitetural. O endpoint de ingestão e o modelo de dados de gravação (§22.3.4, §22.6) são agnósticos à fonte — qualquer cadeia razoável (OBS, appliance proprietário, estúdio próprio, captura ad-hoc) entra pela mesma porta. OBS é prevalente, não privilegiado.

### Não validadas — investigações pendentes

- **TAM da Rota A/D:** qual o percentual real de câmaras no Nordeste que licita sistema legislativo apartado do administrativo? Levantamento via editais publicados nos últimos 24 meses nos TCEs estaduais.
- **Viabilidade de parceria futura (Rota C como plano B):** mapear 10-15 ERPs municipais menores/regionais que atendem câmaras no Nordeste e poderiam ser parceiros viáveis.
- **Apetite real por troca isolada:** entrevistar 5-10 câmaras sobre disposição para trocar *só* o sistema legislativo mantendo o administrativo. Se resposta for "não troco nada separado", a Rota D precisa ser recalibrada.

### Premissas arriscadas a monitorar

- Contratos com câmaras seguem lógica de pregão (12-60 meses com reajuste), o que cria descompasso com modelo SaaS de MRR previsível.
- Softcam é incumbente cearense forte; ganhar em Fortaleza é diferente de ganhar em Recife ou São Luís. Possível recalibragem do beachhead: talvez o wedge real seja câmaras de municípios médios (50-200 mil habitantes) no MA, PI, RN, PB.
- **Tendência regulatória — gravação como registro oficial da sessão (descoberta v1.6).** Movimento crescente em câmaras Brasil afora de alterar regimento interno para tornar a gravação em áudio/vídeo o registro oficial da sessão, eliminando a obrigatoriedade da ata escrita (caso documentado: Câmara de Ponta Grossa/PR, há ~2 anos). Não é universal, mas está se espalhando. Implicação: onde a alteração já aconteceu, ata automática deixa de ser obrigação legal e vira ferramenta de produtividade interna + insumo para resumo cidadão, busca semântica, shorts. Onde não aconteceu, ata escrita continua obrigatória — IA economiza horas de servidor. Em ambos os mundos o pitch da ata automática continua forte, mas o tom muda; time comercial precisa ler em qual dos dois a câmara está antes de pisar no pitch.
- **Cuidado com inflação de TAM por descoberta de OBS-first.** A descoberta de que câmaras pequenas usam OBS+YouTube como padrão **não amplia o TAM** — câmaras pequenas com sistema legislativo precário ou ausente já estavam dentro do TAM "câmaras com sistema legislativo a substituir/adicionar". O que melhora com a descoberta é o **CAC esperado** (custo de aquisição cai porque a fricção operacional de adoção é menor — o cliente não precisa trocar hardware nem fluxos operacionais existentes). Premissa explícita: tratar a descoberta como melhoria de CAC, não de TAM. Recalibragem real de TAM continua dependendo das investigações já listadas em "Não validadas".

---

## 14. Riscos monitorados

**Risco 1 — Consolidação do mercado antes da expansão.** TOTVS, Stone ou private equity podem consolidar Betha + Fiorilli criando dominador de 60% antes de sairmos do Nordeste. *Mitigação:* velocidade no ano 1-2 e manter perfil atraente para aquisição.

**Risco 2 — Mudança regulatória que derrube diferenciação.** Se TCU/TCEs padronizarem layouts de integração, parte do motor de regras vira commodity. *Mitigação:* não construir diferenciação só em compliance; manter IA e UX como diferenciações reais.

**Risco 3 — Ciclo político.** Presidentes de mesa trocam a cada 2 anos; contratos podem não renovar por razões políticas. *Mitigação:* priorizar NPS de servidor (que fica) sobre NPS de presidente (que sai). Servidores viram advogados internos do produto.

**Risco 4 — Escalada de custos em compliance cross-estadual.** Cada novo estado é um TCE novo, layout novo, lei nova. Custos crescem não-linearmente. *Mitigação:* motor de regras genuinamente configurável; estratégia de entrada em estados priorizada por tamanho de mercado.

**Risco 5 — Dependência operacional da cadeia OBS + YouTube na captação (descoberta v1.6).** A V1 absorve qualquer cadeia de captação razoável de modo agnóstico (§16.4, §22.3.4), mas o caso mais comum no mercado-alvo é OBS + YouTube; logo, herdamos pelo volume os modos de falha dela: copyright strike acidental no YouTube (música de fundo em cerimônia, hino tocado) que derruba o canal da câmara durante a transmissão; atualização do OBS que quebra integração com o utilitário de upload em câmaras que usam OBS; áudio do mix do OBS com qualidade ruim (microfonia, ambiente, captação por microfone não-dedicado) que prejudica diarização; aparte e fala simultânea típicas de plenário (cultura regimental brasileira) que quebram diarização ingênua. Cadeias com appliance proprietário (Promic/Riole/ESCAL) têm modos de falha diferentes (formato de saída exótico, lock-in de pasta, atualizações de firmware) — menos volume, mas também presente. *Mitigação arquitetural (transversal):* gravação local é fonte autoritativa, YouTube/transmissão é só destino — perda de transmissão não compromete ata, busca, transparência, independente da cadeia. *Mitigação operacional:* setup de captação recomendado documentado por categoria de fonte (saída direta da mesa de som para placa dedicada, não captura via microfone ambiente — vale para OBS e para qualquer outra fonte); pipeline de áudio da Onda 0 contempla aparte/fala simultânea explicitamente; futura feature opcional "diagnóstico de áudio" no satélite Plugin de Captura Sincronizada. *Mitigação de produto:* o satélite Plugin de Captura Sincronizada, quando construído, terá CI testando contra última versão estável do OBS (primeiro adaptador) e fallback para watch folder genérico se plugin falhar — disciplina extensível para outros adaptadores quando entrarem.

---

## 15. Régua de escopo da V1

**Regra adotada:** entra na V1 apenas o que é condição necessária para o fluxo legislativo acontecer de ponta a ponta (da proposição à publicação transparente) e que não pode ser razoavelmente delegado a um sistema terceiro via integração.

**Teste prático para cada "pedacinho" que alguém queira adicionar à V1:**
1. Sem X, o fluxo legislativo central quebra? Se não, fora.
2. X pode ser resolvido por integração com sistema que a câmara já tem? Se sim, fora (constrói conector, não módulo).
3. Construir X mínimo custa menos de 2 semanas de engenharia? Se sim, entra com escopo mínimo explícito.
4. Adiar X por 12 meses faz a câmara desistir de comprar? Se não, vira roadmap futuro.

Essa régua é o filtro permanente contra inflação de escopo. A aplicação consolidada está na seção 16.

---

## 16. Escopo da V1 consolidado

A V1 é composta de **10 módulos**. Cada um tem escopo explícito do que entra e do que não entra — a segunda lista é tão importante quanto a primeira, porque é o que trava o escopo de inflar nos próximos 4 meses.

### 16.1 Identidade, Perfis e Auditoria (infra core)

**Entra:** autenticação com senha + MFA, SSO gov.br para cidadão, controle de acesso por perfil (servidor, vereador, presidente, secretário de mesa, cidadão, admin do sistema), trilha de auditoria completa (quem fez, o quê, quando, de onde), sessão e tokens.

**Não entra:** SSO com Active Directory/LDAP do município (delegável a integração futura se o cliente pedir), federação com outros IdPs, gestão granular de grupos/funções dinâmicas.

### 16.2 Cadastros Estruturais Legislativos

**Entra:** vereadores (com mandato, filiação partidária, suplência, licença, afastamento), mesa diretora (com cargos e rotatividade bienal), comissões permanentes e temporárias, CPIs, legislaturas, sessões legislativas, blocos/frentes parlamentares.

**Não entra:** servidores administrativos da câmara como entidade (delegado ao RH de onde já está), fornecedores, cadastros contábeis.

### 16.3 Processo Legislativo (coração do produto)

**Entra:** protocolo de todas as espécies de proposição usuais em câmara (PL, PLC, PLP, PDL, PR, PRC, indicações, requerimentos, moções, emendas de todos os tipos), tramitação configurável pelo regimento interno da câmara, pareceres de comissões, fluxo de aprovação, controle de prazos, apensação/desapensação, distribuição a comissões, arquivamento.

**Assinatura digital ICP-Brasil** integrada ao fluxo (paridade competitiva obrigatória).

**Copiloto de redação de projetos** — IA, Aposta 1, Onda 1.

**Busca intra-câmara no acervo legislativo** — IA, Aposta 1, Onda 1.

**Fluxo pós-aprovação — autógrafo → sanção/veto → promulgação → publicação (decisão v1.14).** Aprovado o projeto, o sistema gera o **autógrafo** e registra o envio ao Executivo; controla o **prazo de sanção/veto** do Prefeito (sanção tácita por silêncio); registra **veto** (total/parcial) e sua **apreciação** pela câmara (votação por maioria absoluta, reusa a infra de votação de §16.4 e a DSL de §22.7.5 S4); na sanção ou derrubada de veto, **promulgação** com numeração canônica da lei e **publicação**. Completa a fronteira da §15 ("da proposição à publicação"); o modelo de dados já antecipa (`origem_versao = redacao_final | promulgacao`, §22.4 eixo B). Prazos e rito exato do veto a confirmar com o especialista em regimento (variam por LOM).

**Não entra:** integração com processo legislativo federal ou estadual (cross-ente, V2), mineração cross-câmara (V1.5 com busca cross), similaridade entre proposições (V2).

### 16.4 Sessões Plenárias

**Entra:** pauta eletrônica (expediente + ordem do dia), painel eletrônico de votação em telão, votação nominal/simbólica/secreta com registro auditável, controle de quórum em tempo real, registro de presença por vereador, inscrição de oradores, cronômetro de tribuna, captação e gravação de áudio/vídeo da sessão (com diarização — pipeline da Onda 0).

**Transmissão ao vivo via YouTube Live** integrada (paridade — integração, não reimplementação).

**Anexação de ata redigida externamente (decisão v1.7).** V1 entrega: servidor redige ata em ferramenta de sua escolha (Word, Google Docs, etc.) e sobe arquivo final ao sistema (PDF searchable ou DOCX). Sistema trata como artefato legal, indexa para busca semântica, vincula à sessão, aplica regime de imutabilidade pós-publicação (§22.4.3 disciplina 4). Assinatura digital ICP-Brasil aplicada à ata anexada conforme §22.5 eixo F (assinatura por presidente da Mesa e/ou secretário, conforme regimento da câmara). Arquivos de imagem escaneada não aceitos — ata precisa ser texto extraível para indexação.

**Geração automática de ata pós-sessão por IA entra na V1 (decisão 20/06/2026 — reverte a v1.7/v1.8).** É a **feature-âncora de compra** identificada no discovery de campo (`produto/12`, `produto/05`§2): o nº 1 motivo declarado de troca e a arma contra o SAPL grátis. Entra em modo **"produtividade"** (economia de horas do servidor), **não** "registro oficial" — a barra regulatória do segundo é maior e fica como configuração futura. **Revisão humana obrigatória antes de publicar** (§16.8): a ata gerada é sempre rascunho que o servidor revisa e assina. **Cauda da decisão (qualifica, não anula):** a captação de áudio vira **parte da oferta** (áudio ruim degrada a transcrição → conecta §22.6, §22.3.4); o pipeline áudio→transcrição→sumarização **aperta o cronograma** de §18 (flag de dimensionamento de time). O dataset golden (transcrição, ata humana anexada) segue se formando das câmaras que usam a anexação, agora retroalimentando a qualidade da geração.

**Transcrição automática continua sendo gerada na V1** porque busca semântica intra-câmara (§16.3) depende dela. Pré-atribuição de fala a vereador via Caminho C (inferência por contexto de domínio: tribuna, votação, mesa) acontece automaticamente. Revisão manual de transcrição pelo servidor é opcional — câmara que revisa tem busca semântica de qualidade superior; câmara que não revisa tem busca funcional mas com atribuição menos precisa. Identificação automática de voz (Caminho A) é evolução futura quando voiceprints maturarem.

**Captação e ingestão de áudio/vídeo na V1 (decisão revisada v1.6).** Premissa de mercado: câmaras-alvo já operam cadeias de captação heterogêneas mas estabelecidas — câmaras pequenas tipicamente OBS Studio + YouTube Live (caso mais prevalente, premissa validada em §13); câmaras médias frequentemente appliance integrado (Promic/Riole, Softcam, ESCAL); câmaras grandes estúdio próprio. A V1 absorve qualquer cadeia razoável **de modo agnóstico à fonte** em vez de substituí-la. **Não construímos software de captação local.** A V1 entrega:

1. **Endpoint de ingestão padronizado e agnóstico à fonte.** Aceita upload pós-sessão de arquivo de gravação (mp4/mkv/wav e formatos comuns produzidos pelo OBS, por appliances Promic/Riole/ESCAL/Softcam, ou por qualquer outra cadeia que produza arquivo) com metadados estruturados (sessao_id, intervalos, fonte). Formato de protocolo concreto definido em §22.3.4. Schema de gravação não tem nada de fornecedor-específico.
2. **Utilitário CLI / watch folder genérico** (estimativa: ~2 semanas de engenharia). Servidor configura uma pasta no PC de captura — ao final da gravação, o utilitário detecta o arquivo, calcula hash, faz upload com retomada, deduplicação. **Funciona como fallback genérico para qualquer fonte de gravação que produza arquivo em pasta** — OBS, appliance proprietário, estúdio próprio, ad-hoc.
3. **Documentação de configuração de captação por categoria.** Guia prioritário para OBS pela prevalência no mercado-alvo (setup mínimo: saída direta da mesa de som para placa dedicada, configuração de gravação simultânea à transmissão, pasta destino do utilitário); guia genérico de watch folder para appliances proprietários e outras fontes; nota específica para estúdios profissionais. Documentação é deliverable por categoria, não por fornecedor único.

**Não construímos plugin de captação rico na V1.** O briefing de pesquisa identificou potencial real de um plugin nosso para OBS (sincronizar gravação com domínio via WebSocket API do OBS, marcar timestamps de eventos legislativos no arquivo, browser sources prontos para painel/lower thirds, auto-config) — e plugins análogos podem fazer sentido para outros pontos da cadeia futuramente. Mas plugin completo é produto técnico de outra natureza (C++/Qt no caso de OBS, com plugin registry e ciclo de release independente) com estimativa de 3-4 meses de engenharia para o primeiro adaptador. Aplicando a régua da §15: sem plugin, o fluxo legislativo não quebra (utilitário CLI cobre); ele não cabe em menos de 2 semanas; adiá-lo não faz a câmara desistir de comprar. Encaixe natural é **satélite separado**, conforme padrão estabelecido pela §16.9 (Migração).

**Plugin de Captura Sincronizada como satélite (futuro).** Categoria, não fornecedor: o satélite é a categoria "adaptadores de captura sincronizada com o domínio"; OBS é o primeiro adaptador concreto a ser construído quando primeiro cliente justificar (pela prevalência de mercado), mas a categoria é extensível para outros pontos da cadeia (Promic/Riole, ESCAL, Softcam, soluções ad-hoc) conforme demanda. Para o primeiro adaptador (OBS): nível mínimo viável (watch folder + upload, sem markers, sem sources): ~2 semanas — esse já está na V1 como utilitário genérico, não como plugin OBS. Nível médio (sync de eventos via WebSocket OBS + markers de eventos legislativos no arquivo): ~6-8 semanas. Nível completo (acima + browser sources prontos + auto-config + diagnóstico de áudio): ~3-4 meses. Plugin é também **canal de aquisição lateral** potencial — câmara baixa para resolver problema imediato (sincronizar gravação), descobre o resto do produto. Ordem provável de fontes de áudio à Plataforma de IA, materializada em §22.3.4: gravação local pós-sessão como primária (sem reencoding, autoritativa, vinda de qualquer cadeia); RTMP duplicado para transcrição quase ao vivo (V2+, depende do satélite Plugin de Captura Sincronizada — primeiro adaptador concreto provavelmente OBS); YouTube Live API como fallback de contingência.

**Tensão registrada — régua de escopo (§15) vs. demo power da Aposta 1.** Há tensão real entre disciplina de escopo (que reprovaria o Plugin de Captura Sincronizada na V1) e demo power do produto (que se beneficia de plugin rico para impressionar na demo da ata automática). A resolução adotada — endpoint de ingestão padronizado + utilitário CLI mínimo na V1, adaptadores ricos como satélite sob demanda — preserva ambos. Tensão fica nomeada para evitar deriva: alguém lendo este documento daqui a 6 meses pode ser tentado a "puxar o plugin para a V1"; a resposta é não, salvo cliente real exigindo.

**Não entra:** shorts automáticos (V1.5), plataforma própria de transmissão (delegada ao YouTube e similares), transmissão simultânea para múltiplas plataformas (nice-to-have), software de captação local proprietário, Plugin de Captura Sincronizada e seus adaptadores específicos por fornecedor (satélite separado), **ata-IA em modo "registro oficial"** substituto da ata escrita (a V1 entrega a ata-IA em modo *produtividade*; o valor de registro oficial fica como configuração futura, ver acima).

### 16.5 Transparência Legislativa e Portal Público

**Entra:** portal público white-label, configurável pela câmara em identidade visual (não em estrutura), publicação automática de proposições, atas, votações nominais, presenças, ordem do dia, vereadores e suas proposições, comissões, regimento interno, legislação municipal consolidada. Acessibilidade eMAG/WCAG AA. Responsivo.

**Resumo em linguagem simples de proposições** — IA, Aposta 1, Onda 1.

**Acompanhamento de proposição por cidadão** com notificações (e-mail na V1; push app vem com o app mobile).

**Legislação consolidada — repositório + consolidação manual assistida (decisão v1.14, refina "legislação municipal consolidada").** V1 entrega o **repositório as-enacted** (cada lei/ato como promulgado) **e** a **consolidação viva mantida manualmente** pelo servidor num editor estruturado, versionada sobre o append-only de §22.4 eixo B. **Fora da V1:** consolidação automática por IA (evolução de Onda 2, sobre o copiloto de §16.3) e a consolidação em massa do acervo histórico (problema de migração, §16.9).

**Artefato de publicação oficial — versão leve do Diário Oficial (decisão v1.14).** V1 gera o **artefato oficial** do ato (assinado ICP-Brasil §22.5 eixo F, numerado, imutável §22.4.3 disciplina 4) e o publica no canal da câmara **ou** o entrega ao DOM externo (mesmo padrão do artefato de remessa, §22.7). **Fora da V1:** ser o **Diário Oficial eletrônico de registro** da câmara (adoção legal formal + SLA elevado + risco jurídico) — fast-follow V1.5 se o cliente exigir; validar com o beachhead se publicação oficial através de nós é dor de compra.

**Não entra:** portal da transparência geral da câmara (despesas, folha, contratos, licitações da própria câmara) — isso é responsabilidade do sistema administrativo existente; integramos via consumo quando for o caso, nunca produzimos.

### 16.6 Participação Cidadã (versão mínima)

**Entra:** e-SIC restrito a pedidos sobre proposições e atos legislativos, ouvidoria com roteamento básico por assunto/comissão, comentários públicos em proposições com moderação.

**Não entra:** consulta pública estruturada (V2), chatbot cidadão (V2.5+), audiência pública virtual/e-democracia (V2+), ranking de engajamento por vereador (V2).

### 16.7 Experiência para Vereador (Aposta 2)

**Entra:** app mobile (iOS + Android) como entrada principal do vereador, dashboard pessoal com próximas sessões, pautas, proposições próprias em tramitação, assinatura eletrônica em 2 toques, notificações push.

**Não entra:** gestão de gabinete do vereador (agenda, demandas de bairro, mala direta para eleitores, CRM de base eleitoral) — escopo explicitamente excluído desde o início do projeto, não confundir com reabertura disfarçada.

### 16.8 Camada de Confiança (versão mínima reutilizável)

**Entra:** em todo output de IA — citação de fontes, indicação de incerteza, log auditável, botão "reportar erro", workflow de revisão humana obrigatório antes de publicação (ata revisada antes de publicada, resumo cidadão revisado antes de publicado, texto de projeto é sempre sugestão para o vereador editar).

**Não entra:** a versão robusta da camada (com sampling de auditoria, métricas de qualidade por câmara, painel de governança de IA) vem na Onda 2/V2 junto com constitucionalidade e chatbot cidadão.

**Racional:** construímos o mínimo agora porque é o que as 4 features de IA da V1 exigem. A infra "completa" vem quando chegarem os usos de alto risco.

### 16.9 Migração (Aposta 3)

**Decisão revisada (v1.2):** os conectores automatizados para Legisoft, Legiflow, Nuvem Legislativa e Softcam saíram da V1 inicial — sem cliente fechado no mês 1, construir conectores é especulação. A primeira câmara recebe migração artesanal (humano + scripts ad-hoc) usando os endpoints de ingestão dos módulos do core. O satélite de Migração (com conectores automatizados) entra como projeto separado quando o primeiro cliente estiver na mesa de negociação.

**Entra na V1 — a infraestrutura que torna Migração viável depois sem refactor:** endpoints de ingestão explícitos por módulo (cada módulo do core aceita "dado legado" via API interna com regras de validação adequadas ao contexto de ingestão); marcador `origem`/`origem_ref`/`origem_importado_em` em todas as tabelas relevantes desde o dia 1; pipeline de áudio arquitetado para processar histórico em bulk além de ao vivo (decisão já tomada na seção 10). Detalhes na seção 22.

**Meta de 30 dias do contrato ao go-live mantida** como compromisso comercial; viabilizada na V1 por migração artesanal apoiada pelos endpoints de ingestão.

**Não entra:** conectores automatizados para sistemas concorrentes (deferido para satélite separado conforme decisão da North Star); migração de dados administrativos (folha, contábil, licitações) dos ERPs municipais — não há o que migrar porque não construímos esses módulos.

### 16.10 Operação, SLA e Compliance

**Entra:** SLA de uptime específico para janelas de sessão (noites de terça/quarta/quinta conforme decidido), suporte de plantão noturno dedicado a sessões, status page pública, monitoramento sintético das rotas críticas do fluxo legislativo. Motor de regras de compliance configurável por estado, com **TCE-CE totalmente coberto na V1** (beachhead Fortaleza); arquitetura preparada para outros estados, conteúdo não.

**Não entra:** suporte a TCEs de outros estados além do Ceará na V1 (vem com expansão geográfica), certificação ISO 27001 (ano 2), SOC 2 (ano 2-3).

### 16.11 Painéis, Pendências e Notificações (read-model — decisão v1.14)

**Racional:** as personas decisoras esperam métricas (presidente "ganha com métricas de engajamento cidadão", §11; servidor com "horas poupadas") mas nenhuma feature as entregava; o motor de compliance (§16.10) monitora prazo mas o estado ficava invisível. Esta camada **torna visível o que já capturamos** — é read-model/projeção sobre o substrato event-driven + audit log + motor de prazo (§22.7.7), alinhada ao Invariante 9 (SLIs de negócio). Custo baixo, valor alto; dois itens são **entrega de aposta/persona já construída-mas-invisível**, não analytics opcional.

**Entra:** painel de prazos "o que vence" (compliance TCE + tramitação — entrega visível da Aposta 3); caixa de pendências / "minhas tarefas hoje" (assinar, dar parecer, revisar ata-IA/transcrição); painel de tramitação (funil/kanban sobre a máquina de estados de §22.4 eixo C); dashboard institucional da Mesa (proposições por status, sessões, presença, engajamento cidadão — cumpre a proposta de valor da persona presidente); busca global simples (não-IA, distinta da semântica de §16.3); central de notificações/alertas unificada; exportação PDF/CSV de listas. Tudo read-model, **sem novo modelo de dados**.

**Não entra:** BI de verdade — report-builder, exportação custom configurável, benchmarking cross-câmara, ranking de engajamento por vereador (§16.6) — vem na V2 (mesmo substrato, camada robusta).

---

## 17. Aplicação explícita da régua aos casos borderline

Casos onde a tentação de incluir era real e as 4 perguntas da seção 15 foram decisivas:

**Folha de pagamento de vereadores.** Pergunta 1: sem ela, o fluxo legislativo quebra? Não. Pergunta 2: delegável? Sim — câmara já paga vereadores hoje, sem nós. **Fora.** Sem exceção, mesmo sendo "pequeno".

**Módulo contábil mínimo para envio ao TCE-CE.** Pergunta 1: sem ele, quebra? Não — o TCE-CE recebe envios do sistema contábil atual da câmara. Pergunta 2: delegável? Sim. **Fora.** Produzimos e disponibilizamos os dados legislativos que o TCE-CE exige da parte legislativa (atos, portarias, resoluções legislativas, pagamento de diárias de vereadores via dado consumido pela folha existente).

**Gestão de contratos de fornecedores da câmara.** Pergunta 1: quebra? Não. Pergunta 2: delegável? Sim. **Fora.**

**Portal da transparência completo (despesas, folha, contratos).** Pergunta 1: quebra? Não — é obrigação da câmara, mas não do sistema legislativo. Pergunta 2: delegável? Sim, e é exatamente isso que acontece hoje. **Fora.** Produzimos o portal legislativo; o portal geral pode consumir nossos dados via integração quando a câmara quiser unificar.

**Protocolo administrativo (processos SEI-like da câmara).** Pergunta 1: quebra? Não — nosso protocolo cobre proposições, não ofícios administrativos. Pergunta 2: delegável? Sim. **Fora.** Tentação real porque é "parecido" com protocolo legislativo; é diferente e tem seu próprio universo de complexidade.

**Agenda de vereador / CRM de base eleitoral.** Pergunta 1: quebra? Não. Pergunta 4: adiar faz desistir? Não — não é isso que estamos vendendo, e câmara como instituição não compra isso (isso é domínio de gabinete, explicitamente excluído do projeto desde a origem). **Fora, e fora para sempre na nossa tese atual.**

**Módulo de mídias sociais / comunicação (press releases automáticos).** Pergunta 3: menos de 2 semanas? Não realisticamente. Pergunta 4: adiar faz desistir? Não. **Fora.** Vai natural como desdobramento dos shorts na V1.5.

**Plugin de Captura Sincronizada rico (com WebSocket sync, markers de eventos legislativos no arquivo de gravação, browser sources prontos, auto-config) — caso borderline v1.6.** Categoria de adaptadores nossos para pontos da cadeia de captação (primeiro adaptador concreto provavelmente OBS pela prevalência no mercado, mas categoria é extensível para outros). Pergunta 1: sem ele, o fluxo legislativo central quebra? Não — utilitário CLI/watch folder genérico cobre ingestão para qualquer fonte. Pergunta 2: delegável a sistema terceiro? Parcialmente — a ferramenta de captura é o terceiro, mas a ponte sincronizada com nosso domínio é nossa. Pergunta 3: menos de 2 semanas? Não — estimativa do briefing aponta 6-8 semanas para nível médio do primeiro adaptador, 3-4 meses para completo. Pergunta 4: adiar 12 meses faz desistir? Não — câmaras já se viram com suas cadeias atuais hoje sem nós. **Fora da V1, como satélite separado** (decisão registrada em §16.4). Tentação real porque adaptadores ricos têm alto demo power para a Aposta 1 (ata automática como feature principal); resolução é endpoint de ingestão padronizado + utilitário CLI genérico mínimo na V1, adaptadores ricos construídos sob demanda quando primeiro cliente justificar.

---

## 18. Ordem de construção dentro dos 4 meses da V1

Esboço de sequência para testar com o time técnico — não é roadmap detalhado, é ordenação lógica a partir das dependências mapeadas:

**Mês 0-1:** Onda 0 da IA em paralelo (pipeline áudio + diarização, corpus legal indexado, modelo de dados do processo legislativo); Identidade/Perfis/Auditoria; Cadastros Estruturais. Sem isso nada mais roda.

**Mês 1-2:** Processo Legislativo núcleo (protocolo + tramitação + assinatura ICP-Brasil); primeira versão da Camada de Confiança mínima; ingestão de migração começando com 1 conector (provavelmente Softcam, pela relevância regional).

**Mês 2-3:** Sessões Plenárias (painel, votação, gravação); fluxo de anexação de ata pelo servidor (upload, assinatura digital, vinculação à sessão); pipeline de transcrição automática com pré-atribuição via Caminho C (tribuna, votação, mesa); Portal Público esqueleto; app mobile de Vereador em versão inicial. **Endpoint de ingestão de áudio padronizado (§16.4, §22.3.4) e utilitário CLI/watch folder mínimo precisam estar funcionais até o final do mês 3** para viabilizar a captura de áudio das primeiras sessões reais (insumo da transcrição automática para busca semântica e dataset para geração futura de ata).

**Mês 3-4:** Busca intra-câmara em transcrições e proposições; Resumo cidadão para portal; Copiloto de redação de projetos; **geração automática de ata por IA (feature-âncora, decisão 20/06/2026)**; Participação Cidadã mínima; SLA/plantão operacional ativado; demais conectores de migração; fechamento e hardening. **A ata-IA agora É escopo entregável** (modo produtividade, revisão humana obrigatória §16.8) — depende da transcrição (mês 2-3) e da captação funcional; o dataset golden (transcrição, ata humana anexada) segue se formando das câmaras e retroalimenta a qualidade. ⚠️ Somar este pipeline ao escopo de 4 meses **aperta** o cronograma (ver §16.4, `produto/05`§2).

**Notas importantes:** o mês 0 depende de decisões de stack que ainda não tomamos (chat de North Star Architecture); o mês 4 é piso, não teto — lançar em 4 meses exige time mínimo de ≥8 engenheiros dedicados, designer sênior, product manager e o especialista em regimento já contratado. Dimensionamento real de time também é conversa do chat de arquitetura.

---

## 19. Decisões em aberto (priorizadas)

1. **North Star Architecture** — em construção no chat dedicado. Estado consolidado até aqui na seção 22 (10 invariantes arquiteturais + decisões de alto nível sobre tenancy, modelo de serviços, ingestão de legado, contrato core ↔ Plataforma de IA, modelo de dados do processo legislativo, modelo de autenticação e autorização, e modelo de sessão plenária + áudio + real-time). Pendentes da North Star listados na seção 22.7.
2. **Stack técnico da V1** — decisões de linguagem, framework, banco, infra de IA, cloud, compatíveis com a North Star. *Próxima fase do mesmo chat, depois de fechar a North Star.*
3. **Dimensionamento de time para V1** — quantas pessoas em cada função para entregar os módulos da V1 em 4 meses. *Derivado de #1 e #2.*
4. **Ordem de lançamento dos módulos administrativos pós-V1** — a partir de entrevistas com 15-20 servidores de câmaras.
5. **Mapa competitivo em cenários de 3, 5 e 7 anos** — reação de IPM, Betha, Fiorilli, Elotech; cenários de consolidação; movimentos esperados de entrante estrangeiro.
6. **Recalibragem do beachhead** — Fortaleza capital vs. municípios médios em MA/PI/RN/PB.
7. **Modelo comercial adaptado ao ciclo de pregão** — tensão entre MRR SaaS e contratos plurianuais com reajuste.

---

## 20. Princípios norteadores (para resolver dúvidas do dia-a-dia)

- **O diferencial não é IA, é workflow.** IA é meio. Pitch é tempo economizado, erro reduzido, transparência aumentada.
- **Compliance é pré-requisito, não diferencial.** TCE, LRF, LGPD são table stakes.
- **Arquitetura antecipa a plataforma; módulos seguem o roadmap apertado.**
- **Cliente inicial define o produto V1; cliente futuro define a arquitetura.**
- **Produto pode ser rápido; operação institucional não pode ser improvisada.**
- **Disciplina de escopo na V1 vale mais do que qualquer funcionalidade extra.**
- **"Pedacinho" de outra camada é armadilha de escopo.** Aplicar as 4 perguntas da régua.

---

## 21. Glossário estratégico rápido

- **Câmara como instituição** — unidade de análise correta, excluindo sistemas de gabinete de vereador.
- **Rota D** — legislativo puro + roadmap explícito para suite completa.
- **Wedge** — ponto de entrada estreito que permite expansão posterior. No nosso caso: Nordeste geograficamente, legislativo como produto.
- **NRR (Net Revenue Retention)** — KPI de expansão de conta. Meta de 110%+ nos anos 2-4.
- **Especialistas legislativos puros** — Legisoft, Legiflow, Nuvem Legislativa, Legislarr, aLegislativo, Softcam.
- **Generalistas de gestão pública** — IPM, Betha, Fiorilli, Elotech.
- **Camada de confiança** — infra reutilizável para features de alto risco: citação de fontes, indicação de incerteza, log auditável, botão de reportar erro.

---

## 22. Decisões da North Star Architecture

Esta seção consolida as decisões da North Star Architecture tomadas no chat dedicado ao tema. A North Star em formato completo (documento de 15-25 páginas mencionado em revisões anteriores) está em construção; esta seção é o estado intermediário consolidado, válido como referência canônica para qualquer decisão técnica subsequente.

### 22.1 Os 10 invariantes arquiteturais

Princípios estruturais que não podem ser violados em 5 anos. Cada invariante foi debatido individualmente e fechado:

**1. Multi-ente no domínio desde o dia 1.** Tenant = Ente polimórfico (câmara na V1; prefeitura, consórcio intermunicipal, TCE em momentos posteriores). Município existe como entidade de referência compartilhada — câmara e (futura) prefeitura do mesmo município são entes distintos com dados isolados; cidadão (autenticado via gov.br) vincula-se ao Município, não ao Ente, permitindo app cidadão unificado quando ambos forem clientes. Custo no dia 1: praticamente zero (uma coluna `tipo` em `entes` com valor `'camara'`, tabela `municipios` populada do IBGE). Custo de retrofit: ano de refactor no ano 5+.

**2. Domain events como cidadão de primeira classe, sem event sourcing completo.** Todo estado relevante emite evento explicitamente modelado (`ProposicaoProtocolada`, `VotacaoRegistrada`, `SessaoAberta`, etc.) para um bus interno. Eventos são persistidos como auditoria e propagação; estado autoritativo continua no banco relacional (CRUD). Evita ginástica de event sourcing (replays, projeções complexas) mas captura quase tudo o que ele entrega: auditoria limpa, webhooks/APIs V2 naturais, integrações V2 baratas, real-time como projeção do bus.

**3. IA como plataforma, não como feature.** Camada horizontal (pipelines de áudio/vídeo, transcrição, geração, busca semântica, embeddings, confidence layer, prompt/eval management) sobre a qual as features de IA da V1, V1.5, V2 e V2.5 se apoiam. Não cada feature carregando seu próprio pedaço de infra de IA.

**4. Regras de compliance são dados, não código.** Separação rígida entre motor (código) e regras (estrutura de dados). TCE-CE na V1 é configuração, não branch de código. Permite expansão para 27 estados sem refactor estrutural; permite mudanças regulatórias serem deploy de configuração, não release de engenharia. Materialização concreta (DSL, tabelas, formato de templates) fica para chat dedicado.

**5. Separação rígida core ↔ presentation.** API interna explícita entre domínio e UI desde o dia 1, mesmo dentro do monolito. Razão: V2 tem APIs públicas e marketplace de extensões; sem fronteira clara desde o dia 1, V2 vira refactor de 18 meses.

**6. Soberania de dados BR.** Tudo em região brasileira (sa-east-1 ou equivalente). Processamento de IA sensível (ata com dados pessoais mencionados em plenário, e-SIC, constitucionalidade futura) pode exigir self-host. Decisão concreta sobre provedores/modelos de LLM e implicações LGPD fica para chat dedicado.

**7. Observabilidade é quatro coisas distintas, cada uma com ferramenta, retenção e público próprios.** Logs de aplicação (engenharia, 30-90 dias), métricas e alertas (SRE, ~13 meses), tracing distribuído (engenharia de performance, 7-30 dias), audit log (produto + cliente + legal, retenção regulatória permanente). Nunca colapsar em "um log system só". Substitui e absorve o princípio anteriormente formulado de "auditoria/observabilidade como feature de produto".

**8. Todo sinal de observabilidade carrega `ente_id` quando aplicável.** Não é convenção, é requisito. Enforcement via biblioteca interna que força propagação de contexto. Alerta, dashboard, trace — tudo filtrável e agrupável por ente desde o dia 1.

**9. SLIs de negócio são cidadãos de primeira classe, ao lado de SLIs técnicos.** "Sessão de quarta-feira está funcionando?" tem mesmo peso que "p99 abaixo de 500ms?". Exige modelagem de janelas de sessão e fluxos críticos no sistema, não só métricas de infraestrutura.

**10. Audit log é domínio de produto, não de infraestrutura.** Eventos auditáveis modelados explicitamente (não subproduto de logging), expostos ao cliente via interface do produto, imutáveis (append-only), retidos por prazo regulatório (atos legislativos: permanente; logs de acesso: prazo LAI/LGPD). Alimentado pelo bus de domain events do Invariante 2.

### 22.2 Decisões arquiteturais de alto nível

**Tenancy.** Shared DB + shared schema + RLS + `ente_id` em toda tabela na V1 e anos 1-3. Migração planejada para pool-per-UF nos anos 3-5, gatilhada por: primeiro ente em UF nova, OU dataset agregado cruzando ~500 GB no banco relacional, OU primeira prefeitura. Tier premium com DB dedicado fica como oferta comercial futura, não arquitetura base.

Sharding por município ou por ente foi explicitamente avaliado e descartado: granularidade errada para o domínio, queries cross-ente (busca cross-câmara da V1.5) ficariam fan-out caro, ops explode com 500+ shards, e nenhum problema técnico real (volume de dados, throughput) justifica.

Disciplinas não-negociáveis: nome da coluna é `ente_id` (nunca `tenant_id` ou `camara_id`); todo índice composto começa com `ente_id`; particionamento por hash de `ente_id` em tabelas de alto volume (proposições, transcrições, domain_events, audit_log) já na V1; RLS ativo + guards em repositório como defesa em profundidade; testes de integração com dois entes sintéticos verificando vazamento no CI; toda métrica de observabilidade taggeada por `ente_id`.

**Modelo de serviços.** Core monolítico modular como espinha dorsal — bounded contexts explícitos (Identidade, Cadastros, Processo Legislativo, Sessões, Transparência, Participação, Compliance), API interna pública entre módulos, comunicação via chamada síncrona para leitura e domain events para efeitos colaterais.

Plataforma de IA como satélite separado desde o dia 1: pipelines de áudio/vídeo, transcrição, geração, embeddings, busca semântica, confidence layer. Justificativa: Python-nativo, GPU-aware, dependências pesadas que não devem misturar no container do core; escala independente; alinhado com o Invariante 3 (IA como plataforma exige fronteira técnica real, não só conceitual).

Microserviços para tudo foi explicitamente descartado: 30-40% do esforço da V1 viraria infra distribuída (service discovery, gateway, observabilidade distribuída, ambientes multi-serviço), transações distribuídas em fluxos naturalmente transacionais, debugging vira arqueologia. Não cabe em time pequeno entregando V1 em 4 meses.

Disciplinas para o monolito não virar bola de pelo: estrutura de pastas por bounded context com enforcement de import no CI; cada módulo expõe API pública interna explícita e mantém internals privados; testes de integração por módulo + cross-module só via API pública; migrations agrupadas por módulo (schema ou prefixo de tabela por módulo, mesmo no mesmo banco — facilita extração futura); code review trava PR que fura fronteira.

**Ingestão de dados de legado.** Cada módulo do core expõe dois contratos: fluxo normal (uso por servidor/vereador) e ingestão de legado explícita, ambos como cidadãos de primeira classe. Migração consome o segundo contrato — não escreve direto no banco. Razão: regras de domínio vivem em um lugar só, domain events disparam normalmente (busca indexa, audit log registra, IA gera embeddings) com origem carimbada, contrato testado em CI evita drift silencioso.

Marcador `origem` (`'nativo'` | `'migracao'` | `'importacao_legado'`), `origem_ref` (referência ao sistema de origem) e `origem_importado_em` em todas as tabelas relevantes desde o dia 1. Custo: duas-três colunas extras. Benefício: debugging direcionado, re-processamento cirúrgico, rollback de migração viáveis.

Exceção pragmática: dados imutáveis sem invariantes de domínio (ex.: arquivos de áudio brutos, transcrições já feitas em sistemas antigos) podem ser inseridos via bulk SQL direto, com marcador de origem sempre presente.

### 22.3 Contrato entre core e Plataforma de IA

A fronteira entre o core monolítico modular e o satélite Plataforma de IA é definida por cinco decisões interligadas: topologia de comunicação, protocolo concreto, fluxo de domain events, propriedade de dados, e modelo de erros e retry.

#### 22.3.1 Topologia de comunicação

**Híbrida por classe de operação.** A topologia é escolhida por *operação*, não por *feature*:

- **Síncrono request-response** para operações interativas com latência alvo < 2s e resposta única — copiloto de redação, busca semântica, resumo de proposição curta.
- **Assíncrono via fila + evento de retorno** para operações > 10s ou em background — transcrição, geração de ata, embeddings em lote, bulk de áudio histórico.
- **Streaming** para operações interativas que produzem output incremental — copiloto que escreve token a token, transcrição ao vivo durante sessão.

Disciplinas derivadas: fila persistente como infra obrigatória desde o dia 1; idempotência ou chave de deduplicação obrigatórias em toda operação assíncrona; tracing distribuído com `ente_id` + `correlation_id` atravessando a fronteira.

#### 22.3.2 Protocolo concreto

| Modo | Protocolo | Uso primário |
|---|---|---|
| Síncrono | HTTP/JSON com OpenAPI | Copiloto, busca semântica, resumo curto |
| Assíncrono | Bus de eventos + filas de comando | Transcrição, ata, embeddings em lote, bulk |
| Streaming | SSE | Copiloto incremental, transcrição ao vivo |

Disciplinas derivadas: schema versionado em todo lugar (OpenAPI no síncrono, schema explícito por evento e por comando no assíncrono); idempotency keys obrigatórias em endpoints síncronos com efeito colateral; distinção semântica preservada entre evento de domínio (passado: `SessaoEncerrada`) e comando (imperativo: `TranscreverSessao`) — confundir os dois é o caminho mais rápido pra fronteira virar bagunça.

gRPC explicitamente avaliado e descartado para a V1: ganho marginal dado o volume de chamadas, ferramental e codegen viram custo desnecessário, ergonomia HTTP/JSON com OpenAPI casa melhor com a separação core ↔ presentation (Invariante 5) e com a futura exposição como API pública na V2.

#### 22.3.3 Fluxo de domain events — buses separados com eventos de integração

**Buses lógicos separados.** Core tem seu bus interno (eventos de domínio para coordenação entre bounded contexts internos, schema interno, evolui livremente). Plataforma de IA tem o dela. A fronteira passa por **eventos de integração** — conjunto explícito, pequeno, versionado, com contrato deliberado.

Eventos de domínio interno **não atravessam** a fronteira. Eventos de integração são candidatos naturais a virar webhooks públicos na V2.

Eventos de integração iniciais (V1):

*Do core para a Plataforma de IA:* `ProposicaoProtocolada`, `ProposicaoAtualizada`, `SessaoEncerrada`, `AtaRevisadaEPublicada`, `AudioHistoricoIngerido`.

*Da Plataforma de IA para o core:* `TranscricaoConcluida(sessao_id, transcricao_uri, ...)`, `AtaRascunhoPronta(sessao_id, ata_rascunho_uri, ...)`, `ResumoCidadaoPronto(proposicao_id, resumo_uri, ...)`, `EmbeddingsGerados(entidade_id, ...)`, eventos de erro (`TranscricaoFalhou`, `AtaFalhou`, `ResumoFalhou`).

Disciplinas derivadas: promoção de evento interno para evento de integração é decisão arquitetural com review (compromisso de longo prazo, vira webhook na V2); schema versionado com rigor (versão no nome ou no payload, breaking changes coexistem em transição); cada evento de integração entra no audit log do lado emissor; separação lógica não exige separação física — pode ser uma única infra de mensageria com streams/topics dedicados.

#### 22.3.4 Propriedade de dados — dividida por natureza do artefato

| Artefato | Vive em | Por quê |
|---|---|---|
| Áudio bruto | Object storage compartilhado (BR, S3-compatible) | Grande, imutável, ambos os lados acessam |
| Transcrição diarizada | Plataforma de IA | Artefato técnico, versionado por modelo, reprocessável |
| Ata em rascunho | Plataforma de IA (transitório) | Output bruto antes de revisão humana |
| Ata revisada e publicada | Core | Artefato legal, sob mesmo regime de auditoria/RLS/retenção |
| Resumo cidadão (rascunho) | Plataforma de IA | Output bruto |
| Resumo cidadão (publicado) | Core | Vai para o portal público |
| Embeddings vetoriais | Plataforma de IA | Modelo + embeddings ficam acoplados; busca local |
| Logs de inferência | Plataforma de IA | Insumo de eval framework, não audit log de produto |

**Promoção rascunho → publicado é fluxo cross-side explícito:** servidor revisa via UI do core (que lê rascunho da IA via API); aprovação dispara cópia do conteúdo final para tabela do core; core emite `AtaRevisadaEPublicada`; o ato de copiar é o que transfere ownership.

**Busca semântica via API síncrona à IA** (sem replicação de embeddings no core): core recebe query → IA computa embedding e faz similarity search → retorna IDs com score → core busca dados completos nas suas tabelas.

**Caminho de ingestão de áudio bruto (decisão v1.6, casa com §16.4).** Áudio bruto vive em object storage compartilhado em região BR; o caminho até o storage tem três fontes possíveis em cascata, escolhidas conforme premissa de cadeia operacional da câmara:

1. **Fonte primária — gravação local pós-sessão (V1).** Arquivo produzido pelo OBS (ou por appliance proprietário) sobe para o storage via endpoint de ingestão padronizado. Sem reencoding, autoritativo, sem dependência de plataforma externa para artefato legal. Suficiente para V1 (ata pós-sessão, transcrição em batch). Caminho operacionalizado pelo utilitário CLI/watch folder mínimo da §16.4.
2. **Fonte secundária — RTMP duplicado durante a sessão (V2+, depende de satélite).** Quando o satélite Plugin de Captura Sincronizada for construído (§16.4), a ferramenta de captação pode streamar simultaneamente para o destino de transmissão (YouTube Live, tipicamente) e para um endpoint nosso. No caso de OBS — primeiro adaptador concreto provável pela prevalência de mercado — isso é viabilizado pelo plugin "Multiple RTMP Outputs" (estável e oficial no ecossistema OBS). Outras ferramentas de captação têm capacidades equivalentes próprias. Permite transcrição quase ao vivo durante a sessão (latência 2-5s). Necessário para features V2+ (alertas regimentais ao vivo, legendas em tempo real, painel de votação reativo).
3. **Fonte terciária — YouTube Live API (fallback de contingência).** Existe e funciona, mas tem latência maior, qualidade reencoded, e cria dependência em sistema externo. Útil só como contingência (gravação local corrompeu, RTMP duplicado fora). Não é candidato a fonte primária.

A taxonomia de propriedade de dados (tabela acima) **não depende da fonte de ingestão** — uma vez no object storage, "áudio bruto" é áudio bruto. A fonte fica registrada como metadata do objeto (`fonte_ingestao` ∈ `{gravacao_local_pos_sessao, rtmp_duplicado_ao_vivo, youtube_api_fallback, importacao_legado}`) para fins de auditoria, debugging de qualidade e métricas operacionais. Disciplina derivada: contratos de eventos `SessaoEncerrada` e `AudioHistoricoIngerido` não mudam por fonte; quem consome o áudio (Plataforma de IA) tem comportamento uniforme; diferenças de latência e qualidade são contornadas por políticas operacionais, não por fluxos diferentes na fronteira.

Disciplinas derivadas: object storage entra como infra arquitetural na North Star (não é mera escolha de stack); URIs como cidadãos de primeira classe nos contratos (conteúdo grande é fetched on demand, não trafega inline); LGPD direito ao apagamento orquestrado via evento `ApagamentoSolicitado(sujeito_id)` consumido pelos dois lados; backup/restore é tripé (core + IA + storage), DR coordena os três.

#### 22.3.5 Modelo de erros e retry

Falhas em fronteira IA são qualitativamente diferentes de falhas em endpoint CRUD comum — têm gradiente. Taxonomia de seis categorias com tratamento específico em cada uma:

1. **Falha de infraestrutura** (IA fora, rede, fila): síncrono → timeout do cliente, UI mostra erro, sem retry server-side; assíncrono → backoff exponencial 3-5 tentativas em ~30min, depois dead-letter queue.
2. **Falha por sobrecarga** (saturação, rate limit do provedor de LLM): circuit breaker no chamador + backpressure na fila + rate limit por ente.
3. **Falha de input** (input inválido, áudio corrompido): sem retry; síncrono → 422 com mensagem clara; assíncrono → evento de falha imediato.
4. **Falha de modelo** (saída inválida estruturalmente): retry curto 2-3 vezes (LLM é não-determinístico); se persistir, log completo e operação falha pra inspeção humana.
5. **Saída plausível mas errada** (alucinação): **NÃO** é problema técnico — é resolvido pelo workflow de revisão humana (Camada de Confiança §16.8). Toda saída de IA é proposta; humano revisa antes de virar artefato legal.
6. **Saída com baixa confiança**: score/sinal de confiança propagado como metadata; UI de revisão sinaliza ("revisar com atenção").

Disciplinas derivadas:

- Toda fronteira síncrona retorna erros com schema estruturado (categoria + detalhes), versionado.
- Toda operação assíncrona é idempotente; retry com backoff é responsabilidade da fila/worker, com limite explícito; DLQ com ferramenta operacional para inspecionar, re-enfileirar, descartar.
- Falha tipo 5 não é tratável tecnicamente — Camada de Confiança da §16.8 é a defesa.
- Métricas por categoria de erro, latência, custo de inferência, e score de confiança são obrigatórias; dashboard dedicado à fronteira IA é requisito operacional.
- Três defesas contra sobrecarga: circuit breaker no chamador, backpressure na fila, rate limit por ente.
- Fallback de modelo é política operacional interna da Plataforma de IA, transparente para o core.
- SLO da fronteira IA é separado do SLA do core; latência por classe de operação tem alvo próprio (busca < 2s p95, ata em < X min pós-sessão).

#### 22.3.6 Decisões arrastadas que viram parte da North Star

Algumas decisões fechadas neste contrato têm escopo maior que apenas a fronteira core ↔ IA — viram premissas para o resto da arquitetura:

- **Object storage compartilhado em região BR** entra como infra arquitetural (não só stack).
- **Fila persistente** entra como infra obrigatória desde o dia 1.
- **SSE como protocolo de streaming** já está adotado entre core e IA — facilitou a decisão sobre real-time para o cliente final (modelo fechado em §22.6 eixo G).
- **Distinção evento de domínio vs. evento de integração** vira disciplina de design para qualquer fronteira futura (Migração quando virar satélite, módulos extraídos do monolito no futuro, APIs públicas da V2).

### 22.4 Modelo de dados do processo legislativo

Esta subseção consolida as decisões fechadas sobre o modelo de dados do coração do produto: proposições, tramitação, espécies legislativas, emendas, apensação, pareceres, votações, e identidade canônica. As decisões foram tomadas em oito eixos discutidos individualmente; aqui estão sintetizadas como referência canônica.

#### 22.4.1 Visão geral e taxonomia de entidades

O processo legislativo se materializa em entidades agrupadas por papel:

**Entidades centrais.** `proposicoes` (com STI híbrido por tipo), `proposicao_texto_versao` (versionamento append-only), `emendas` (entidade própria), `proposicao_apensacao` (relação histórica), `parecer_comissao` + `parecer_texto_versao` + `parecer_voto_divergente`, `votacoes` + `votos` + `votos_secretos`.

**Entidades de motor declarativo.** `template_tramitacao`, `template_estado`, `template_transicao`, `proposicao_transicao_historico` (append-only), `proposicao_prazo_ativo` (operacional).

**Entidades de referência.** `municipios` (populado do IBGE no bootstrap, fonte canônica de UF e código IBGE), `entes` (com `municipio_id` NOT NULL para câmaras na V1).

#### 22.4.2 Decisões por eixo

**Eixo A — Estrutura das proposições.** STI híbrido. Tronco comum em colunas tipadas (~15 atributos), atributos quentes específicos por tipo em colunas tipadas opcionais (`objeto_indicacao`, `destinatario_id`+`destinatario_texto`, `tipo_requerimento`, `categoria_mocao`), JSONB sidecar (`atributos_especificos`) só para o que é genuinamente heterogêneo (PDL e seus subtipos hoje). UF acessada via JOIN `entes → municipios`, **não** denormalizada na proposição — denormalização só com gatilho medido (query crítica passou de N ms p95). CHECK constraints por tipo enforçam atributos obrigatórios no banco.

**Eixo B — Texto da proposição.** Versionamento append-only em `proposicao_texto_versao` com proveniência explícita via `origem_versao` (`protocolo` | `substitutivo` | `aplicacao_emenda` | `redacao_final` | `promulgacao` | `importacao_legado`) e `origem_ref` polimórfico (referência ao gatilho que criou a versão: emenda_id, sessao_id, etc.). Granularidade: blob markdown com convenção semântica leve (`## Art. 3º`), promovível para relacional fino quando feature de produto justificar. Estratégia híbrida inline/URI com threshold de 32KB (calibrável). `estado_versao` (`rascunho` | `vigente` | `superada` | `arquivada`) suporta fluxo de promoção via revisão humana. `proposicoes.texto_vigente_versao_id` aponta para o row vigente; promoção é UPDATE em uma coluna FK.

**Eixo C — Tramitação.** Máquina de estados declarativa simples (descartadas: estados hardcoded e workflow engine genérico tipo BPMN). Vocabulário mínimo: template, estado, transição, guard, ação. DSL pequena de expressões booleanas — sem loops, sem variáveis mutáveis, sem efeitos colaterais; ações são identificadores que o motor mapeia para handlers em código. Schema: `template_tramitacao`, `template_estado`, `template_transicao`, `proposicao_transicao_historico` (append-only). Cada transição emite `ProposicaoTransicionou(de, para, gatilho, contexto)` no bus interno. Versionamento de template por **cópia integral**; `template_pai_*` é proveniência, não governança ativa. Drift mitigado por ferramental operacional (diff de template, rebase auditado, métricas de versão em uso). Editor de template é ferramenta interna na V1, não de cliente.

**Paralelismo de estados (eixo C continuação).** Modelagem por subprocessos com handles explícitos (descartadas: state machine pura com estado opaco e hierarchical state machine ortodoxa). `parecer_comissao` é entidade própria com state machine pequena própria, governada pelo mesmo motor. Estado da proposição-mãe permanece coarse (`em_comissoes`). DSL do template principal expõe agregadores sobre subprocessos como funções: `pareceres.todos_concluidos`, `pareceres.algum_rejeitou`, `pareceres.contagem_terminal >= N`, `pareceres.prazo_vencido_em_alguma`. Eventos de `parecer_comissao` são consumidos pelo motor da mãe para reavaliar transições.

**Prazos de domínio (eixo C continuação).** Tabela `proposicao_prazo_ativo` (operacional, não legal) com `vence_em`, `acao_no_vencimento`, `transicao_alvo_id`, ciclo `ativo` → `cumprido` | `vencido_processado` | `cancelado`. Configuração de prazo padrão vive no template (`template_estado.prazo_padrao_dias`). Override por câmara funciona naturalmente via cópia integral. Processamento (worker varrendo, job scheduling persistente, ou híbrido) é decisão de infra deferida para chat de stack — schema sobrevive a qualquer escolha. Padrão é candidato a generalização para `prazo_dominio_ativo` polimórfico quando aparecer o segundo caso (LAI, sanção/veto, envio TCE).

**Eixo D — Emendas.** Entidade própria (descartado: emenda como tipo de proposição). Tabela `emendas` com numeração local dentro da proposição-mãe (UNIQUE `proposicao_mae_id, numero_local`), `tipo_emenda` (modificativa | supressiva | aditiva | substitutiva_total | substitutiva_parcial | aglutinativa | redacao), `momento_apresentacao` (no_prazo | plenario | redacao_final), `escopo_textual` descritivo. Texto da emenda com mesma estratégia híbrida do eixo B, sem versionamento. Estado em enum simples na própria tabela — **não usa motor de templates** porque ciclo é universal entre câmaras. Aplicação ao texto-mãe via rascunho humano: emenda aprovada cria nova `proposicao_texto_versao` em estado `rascunho`; redator humano consolida (com IA opcional como apoio sob Camada de Confiança); promoção a `vigente` é ato explícito. `versao_texto_resultante_id` na emenda fecha ciclo bidirecional.

**Eixo E — Apensação.** Tabela de associação com histórico (descartadas: coluna direta na proposição e conceito de "grupo de tramitação"). `proposicao_apensacao(principal_id, apensada_id, apensada_em, desapensada_em, motivos, atos_ref)`. Desapensação é UPDATE em `desapensada_em`, não DELETE — linha persiste como fato histórico. Mudança de principal é dois atos auditados (desapensar + apensar). Cadeia genuína suportada via traversal recursivo (CTE); colapso vs. cadeia é configurável por câmara, não constraint hardcoded. Mutação restrita: além de `desapensada_em` e motivos correlatos (preenchidos uma vez), nada é mutável — disciplina de imutabilidade parcial.

**Eixo F — Pareceres.** Entidade `parecer_comissao` governada pelo motor do eixo C. Voto do relator (`favoravel` | `contrario` | `favoravel_com_emendas` | `pela_constitucionalidade` | etc.) na entity principal; votos divergentes em tabela auxiliar `parecer_voto_divergente`. Texto em `parecer_texto_versao` com mesma estratégia do eixo B. Referência ao objeto via polimorfismo `(objeto_tipo, objeto_id)` para suportar parecer sobre proposição **ou** emenda. Eventos: `ParecerComissaoIniciado`, `ParecerComissaoRelatorDesignado`, `ParecerComissaoApresentado`, `ParecerComissaoAprovado` | `ParecerComissaoRejeitado` | `ParecerComissaoPrejudicado` | `ParecerComissaoPrazoVencido` — consumidos pelo motor da proposição-mãe via DSL agregadora.

**Eixo G — Votação.** Schema `votacoes` + `votos` + `votos_secretos` (tabela separada para preservar sigilo no schema, sem `created_by` deliberadamente). Modalidades: nominal, simbólica, secreta. Quórum como enum (`maioria_simples` | `maioria_absoluta` | `maioria_qualificada_2_3` | `maioria_qualificada_3_5`); verificação no motor de regras. Polimorfismo do objeto via `(objeto_tipo, objeto_id)` — suporta votação sobre proposição, emenda, parecer, requerimento de urgência, redação final, etc. Votos puramente append-only; correção de voto é nova votação inteira com auditoria, **nunca** UPDATE silencioso. Eventos: `VotacaoAberta`, `VotoRegistrado` (real-time durante sessão nominal), `VotacaoEncerrada` — eventos de domínio interno por enquanto; promoção a evento de integração com Plataforma de IA quando feature V2 justificar.

**Eixo H — Identidade canônica e imutabilidade.** PK interna UUID gerada pelo sistema, usada em todas as FKs internas, eventos e IA. Numeração canônica `(ente_id, tipo, ano, sequencial)` com UNIQUE constraint, gerada na transação de protocolo via SEQUENCE atômica por (ente_id, tipo, ano) — decisão concreta de implementação (Postgres SEQUENCE, counter row, etc.) deferida para chat de stack, modelagem fixa que sequencial é gerado atomicamente, não pelo cliente. Formato de exibição (`PL 042/2026`) é template configurável por ente, não engessado no schema. Imutabilidade pós-publicação enforçada em duas camadas: guard no domínio (service layer) + constraint/trigger no banco que bloqueia UPDATE em rows com estado terminal, exceto em fluxo auditado de correção (transação especial com flag explícita).

#### 22.4.3 Disciplinas arquiteturais derivadas

Padrões que aparecem repetidamente nos eixos e viram regra geral, não decisão por feature:

**1. Convenção de campos transversais.** Toda tabela de domínio do core tem, no mínimo: `id`, `ente_id` (quando aplicável), `created_at`, `updated_at`, `created_by` (nullable), `updated_by` (nullable), `lock_version`, `origem`, `origem_ref`, `origem_importado_em`. Tabelas de domínio legal **não** têm `deleted_at` — cancelamento e arquivamento são estados explícitos no modelo. Exceções: tabelas de eventos (append-only, sem `updated_*`/`lock_version`, têm `occurred_at`/`created_at`); tabelas de junção pura (só `created_at` + chaves); tabelas operacionais não-legais (regras próprias, soft delete admissível como exceção justificada).

**2. Padrão de referência polimórfica `(objeto_tipo, objeto_id)`.** Disciplina recorrente em `parecer_comissao`, `votacoes`, e provavelmente em entidades futuras (e-SIC sobre proposição, comentário cidadão sobre proposição/emenda). Trade-off aceito: perde integridade referencial declarativa em troca de flexibilidade. Disciplinas de mitigação: índice composto começando por `objeto_tipo`; validação no service layer; evento de domínio carrega tipo explícito; testes de integração exercitam todos os tipos suportados. Decisão revisitável no chat de stack se Postgres oferecer padrão melhor.

**3. Padrão de versionamento de texto.** Tabela append-only separada (`proposicao_texto_versao`, `parecer_texto_versao`, futura `ata_texto_versao`), estratégia híbrida inline/URI com threshold (32KB inicial, calibrável por observabilidade), `estado_versao` (`rascunho` | `vigente` | `superada` | `arquivada`), promoção rascunho → vigente como ato auditado. API do core abstrai a diferença inline vs. URI — quem chama recebe `{ texto: string }` e não sabe o caminho de origem.

**4. Taxonomia de imutabilidade em três níveis.** Tabelas de domínio legal têm padrão de imutabilidade declarado, com três níveis: (a) **append-only puro** — versões de texto, votos, transições históricas, audit log; sem UPDATE/DELETE jamais; constraint/trigger no banco. (b) **Mutação controlada com travamento por estado** — proposições, pareceres, votações, emendas; mutação livre durante tramitação, travada em estado terminal; fluxo de correção auditada como exceção formal. (c) **Mutação parcial em campos específicos** — apensação (só `desapensada_em` e motivos correlatos mutáveis, uma vez); modelagem manual com constraint adequada caso a caso.

**5. Motor declarativo compartilhado.** Tramitação (eixo C) e regras de compliance (Invariante 4, item 4 dos parqueados) compartilham DSL e mecânica de avaliação. Não construir duas DSLs distintas — quando abrirmos compliance, partimos do que decidimos no eixo C. Investimento concentrado, ferramental de simulação/debug compartilhado, evolução de DSL feita uma vez. Custo: pensar a DSL com os dois usos em mente desde o início.

**6. Padrão de "prazo de domínio".** `proposicao_prazo_ativo` é especialização de algo mais geral. Quando aparecer segundo caso (prazo LAI para resposta de e-SIC, prazo de sanção/veto pelo Executivo, prazo de envio ao TCE, etc.), generalizar para `prazo_dominio_ativo` polimórfico. Premature abstraction agora não compensa; deixar nota explícita "isto é candidato a generalização" é suficiente.

#### 22.4.4 Decisões deferidas e pontos a confirmar

**Resolvido — linguagem de backend = Clojure (decisão Emilio, 20/06/2026).** Materializa a parte de *linguagem de backend* do stack que esta seção deferia; o restante (persistência concreta, IdP, infra de worker/fila, frontend/mobile) **segue deferido** — agora aberto e consolidado eixo a eixo em **§22.9** (chat de stack, 20/06). Racional: o coração do sistema é um motor de **DSL/regras** de compliance (§22.7), e um Lisp homoicônico torna regra/AST/catálogo **dados nativos** (Invariante 4 "regra é dado" + Disciplina 5 motor declarativo único); imutabilidade por default casa com a auditoria **append-only** (Invariante 10); `ratio` nativo dá a aritmética exata da armadilha do quórum. O protótipo `motor-dsl/` (Python, §7) foi **portado para Clojure** em `motor-dsl-clj/` com paridade de comportamento (**8 testes / 44 asserções verdes**); o Python fica como referência validada.

Decisões concretas de implementação deferidas para outros chats:

**Para o chat de stack/infra:** geração atômica do `sequencial` (SEQUENCE Postgres ou alternativa); processamento de prazos vencidos (worker varrendo banco vs. job scheduling persistente vs. híbrido); idempotência de handlers (idempotency key, lock pessimista, CAS — disciplina obrigatória); validação de polimórfico vs. FKs separadas com CHECK XOR no contexto Postgres específico.

**Para o item 4 dos parqueados (motor de regras de compliance):** materialização concreta da DSL compartilhada com tramitação, formato de templates de regra TCE, versionamento de regras, partindo da disciplina arrastada do eixo C.

**Para confirmar com especialista em regimento (após contratação):** PDL é mesmo o único caso heterogêneo de espécie? Ciclo de `parecer_comissao` é universal o bastante para enum direto, ou há variações regimentais que justificam template configurável? "Comissões obrigatórias por matéria" é configuração estática por (ente, tipo) ou depende da matéria via expressão DSL? Cadeia de apensação é colapso automático universal ou variável por câmara? Reformulação de parecer rejeitado é segunda versão ou novo parecer? Aplicação automática de emenda supressiva trivial vale trilho rápido?

### 22.5 Modelo de autenticação e autorização

Esta subseção consolida as decisões fechadas sobre autenticação (quem é a pessoa) e autorização (o que ela pode fazer) para todos os atores do sistema: cidadão, servidor, vereador, presidente da Mesa, secretário, e admin interno (nosso). As decisões foram tomadas em sete eixos discutidos individualmente; aqui estão sintetizadas como referência canônica.

#### 22.5.1 Visão geral e taxonomia de sujeitos e mecanismos

O sistema reconhece quatro categorias de ator com mecanismos de autenticação distintos:

**Cidadão.** Autenticação exclusiva via gov.br. Bronze, prata e ouro aceitos uniformemente na V1 — sem distinção de nível por fluxo. Sem cadastro próprio com e-mail/senha. A capacidade de exigir nível mínimo por fluxo existe na arquitetura mas não é exposta na V1; configuração futura possível sem refactor.

**Servidor.** Autenticação interna do produto, **passwordless-first**: WebAuthn/passkey é o **fator primário recomendado** (phishing-resistant por amarração ao origin, multifator num único gesto, menos atrito que senha+TOTP); senha + TOTP permanece como **piso obrigatoriamente disponível** para quem não usa passkey. SMS não é aceito por default (SIM swap é vetor real). **E-mail código de uso único** serve só ao bootstrap de primeiro acesso (enrollment) e à recuperação — nunca como fator standing (§22.5.2 eixo F). Recuperação de senha autoatendida para perfis comuns; com aprovação de admin do ente para perfis de poder elevado (secretário-geral e equivalentes).

**Vereador.** Mesmo padrão passwordless-first do servidor: WebAuthn/passkey primário (o app mobile do vereador, §16.7, é o caso ideal — Face ID/digital, alinhado à "assinatura em 2 toques"), senha + TOTP como piso. Sem gov.br vinculado. Sem ICP-Brasil no login. Identidade institucional, cadastrada pelo admin do ente no início da legislatura. ICP-Brasil é reservada exclusivamente para step-up de assinatura digital (§22.5.2 eixo F).

**Presidente / Secretário da Mesa.** Não é tipo de usuário separado. É papel (cargo) acumulado por um vereador durante o mandato bienal da Mesa. Mecânica vive nos eixos C (papéis temporais) e D (escopo ativo).

**Admin interno (nosso, da SaaS).** IdP fisicamente separado do IdP dos clientes desde a V1. WebAuthn com hardware key física obrigatório (sem passkey sincronizado). TOTP como backup com justificativa registrada. Política de senha mais dura, lifecycle (onboarding/offboarding) separado, audit log com retenção máxima.

**Posição estrita da ICP-Brasil.** ICP-Brasil é mecanismo de **assinatura digital com valor jurídico de não-repúdio**, exclusivamente. Não é fator de autenticação. Não é fator de step-up genérico. É invocada apenas no ato de assinar artefato legal (proposição, parecer, ata, resolução). Distinção autenticação ↔ assinatura é arquitetural, não cosmética.

#### 22.5.2 Decisões por eixo

**Eixo A — Sujeitos e fluxos de autenticação por ator.** Detalhado em §22.5.1. Pontos não-óbvios consolidados: cidadão sem conta gov.br cria conta no fluxo do próprio gov.br (externo a nós), sem fricção adicional na V1. Comentário/manifestação anônima é decisão de produto separada da decisão de identidade — pode ser oferecida como configuração por ente (formulário público sem login com moderação manual assumida pelo ente), e não confundir com cadastro próprio. Provedor de IdP concreto (Cognito vs. Keycloak vs. Auth0 vs. próprio) é decisão de stack, deferida para chat dedicado.

**Eixo B — Modelo de autorização: híbrido pragmático.** RBAC clássico para papéis estáticos centrados em pessoa + regras dinâmicas via DSL pequena compartilhada com tramitação (§22.4 eixo C) e compliance (Invariante 4). Decisões dinâmicas avaliadas como expressões sobre **funções de relação** expostas pelos bounded contexts donos dos recursos. Sem peça de infra dedicada de auth (sem OPA, sem OpenFGA) na V1. Migração futura para ReBAC dedicado é viável se complexidade explodir, porque as relações já estão modeladas explicitamente no domínio.

Inventário de funções de relação (consolidado em validação do eixo B):

*Expostas por Cadastros Estruturais:* `tem_mandato_vigente(usuario, ente, instante)`, `é_membro_de_comissao(usuario, comissao, instante)`, `é_presidente_de_comissao(usuario, comissao, instante)`, `é_presidente_da_mesa(usuario, ente, instante)`, `é_secretario_da_mesa(usuario, ente, instante)`, `quem_exerce_presidencia(ente, instante, sessao_opcional)`.

*Expostas por Processo Legislativo:* `é_autor_de(usuario, proposicao)`, `é_coautor_de(usuario, proposicao)`, `é_relator_de(usuario, parecer)`.

*Expostas por Sessões Plenárias:* `está_presente_em(usuario, sessao)`.

*Transversal:* `é_o_próprio(usuario, sujeito)` — utilitário simples para self-action ("editar próprio comentário", "ver próprio audit log", "justificar própria ausência").

Total: ~10 funções de relação + 2 consultas ao motor de tramitação (estado do recurso permite ação? recurso dentro do prazo?). Cabe confortavelmente no híbrido pragmático.

**Eixo C — Papéis: granularidade, composição, temporalidade.**

*Granularidade.* Papéis estáticos centrados em pessoa em tabela `usuario_papel`. Papéis contextuais a recurso (relator de parecer, autor de proposição, membro de comissão) modelados no próprio recurso — colunas FK ou tabelas de junção próprias — e expostos como funções de relação pelo bounded context dono. Razões: coerência com modelagem já feita em §22.4 (relator é atributo de parecer); preserva integridade referencial declarativa; cardinalidade fala alto (relator é coluna, membro é tabela de junção); funções de relação do eixo B mapeiam diretamente.

*Mandato.* Entidade explícita com estados (`vigente`, `licenciado`, `cassado`, `renunciado`, `falecido`, `concluido`). Tabela `mandato_licenca` com vínculo ao mandato do suplente em exercício durante a licença. Tabela `suplencia` cadastra ordem de suplentes por (legislatura, ente, partido). Suplente que assume recebe mandato próprio de natureza "exercício de suplência" — não vira "vereador titular". Cassação, renúncia e falecimento preenchem `fim_efetivo` e mudam `estado`; ato é audit-logged.

*Mesa Diretora.* Tipo especial de comissão (`tipo='mesa'`) com cargos nomeados em tabela `comissao_cargo`. Mandato bienal via `vigencia_inicio`/`vigencia_fim`. Eleição da Mesa via template de tramitação dedicado (motor declarativo do eixo C de §22.4). Reuso máximo: mesma mecânica de comissão para deliberações da Mesa (resoluções, decisões de pauta), votação, ata.

*Titularidade vs. exercício.* Função `quem_exerce_presidencia(ente, instante, sessao_opcional)` resolve em camadas: (a) fora de sessão, titular do cargo `presidente` na Mesa vigente, com fallback pela ordem regimental se titular licenciado; (b) durante sessão, consulta `sessao.presidencia_em_exercicio_id` (atualizável durante a sessão via evento `PresidenciaPassada` append-only). Ordem regimental de substituição mora no template de regimento configurável por ente.

*Composição de papéis.* Aditiva, sem precedência geral. Cascata via `tem_mandato_vigente`: licença derruba todos os papéis temporais sem necessidade de desativar individualmente — mas a *atribuição* dos papéis permanece registrada (vereador continua sendo o presidente eleito da Mesa, só não está em exercício enquanto licenciado). Suplente que assume *não* herda automaticamente cargos na Mesa nem membership em comissões do titular licenciado.

*Temporalidade e auditoria.* Toda atribuição de papel/cargo/membership tem `vigencia_inicio` e `vigencia_fim` (nullable). Append-only com mutação controlada (taxonomia de imutabilidade §22.4.3 disciplina 4 nível b). Eventos de domínio em todas as transições. Funções de relação aceitam `instante` como parâmetro com default `now()`; consulta histórica usa `instante` específico.

**Eixo D — Sessão, escopo ativo e multi-perfil.**

*Identidade ↔ Vínculo separados.* Identidade ancorada em CPF (entidade `identidade`). Vínculo é a relação da identidade com um Ente (servidor, vereador, admin interno) ou Município (cidadão, conforme Invariante 1). Mesmo CPF pode ter múltiplos vínculos: vereador na Câmara X + cidadão no Município Y + cidadão no Município Z. Provedor de identidade externa (gov.br para cidadão, IdP de admin para admin interno) vincula via `identidade_externa(identidade_id, provedor, sub)`.

*Escopo ativo: um vínculo por sessão.* Token carrega `(identidade_id, vinculo_ativo_id, tipo_vinculo, ente_id, papeis_estaticos_snapshot, expira_em)`. Não há sessão multi-vínculo simultânea. Trocar de vínculo é nova sessão (UI suave: botão "trocar perfil"; backend explícito: novo token).

*Multi-perfil resolvido pela camada Identidade.* UI oferece troca de perfil quando identidade tem múltiplos vínculos. Permissões não vazam entre perfis — vereador acessando como cidadão no mesmo CPF não traz consigo poderes de vereador. Auditoria registra explicitamente "identidade X agindo como vínculo Y no ente Z". Para moderação por servidor de outro ente: UI por padrão mostra só o vínculo ativo; admin/audit interno tem acesso à identidade subjacente quando justificado.

*Token = snapshot de papéis estáticos com TTL curto + revogação imediata.* Papéis estáticos do RBAC (`vereador`, `servidor_protocolo`, `presidente_mesa`) entram no token como snapshot no momento de emissão. Relações dinâmicas (relator, presente, membership) são **sempre** consultadas em runtime via funções de relação — nunca cabem no token. TTLs configuráveis com defaults (§22.5.3): access token de 30min para cidadão, 15min para servidor/vereador, 15min para admin interno; refresh token de 7d/24h/1h respectivamente. Revogação imediata via lista de tokens revogados em eventos críticos (`MandatoCassado`, `ServidorDesligado`, etc.) — quem dispara o evento de domínio também publica `TokensRevogadosPorIdentidade(identidade_id)`.

*Refresh, expiração e logout.* Padrão clássico access + refresh, com rotation a cada uso. Logout invalida refresh; access continua válido até TTL expirar (aceitável dado TTL curto). Logout urgente dispara revogação imediata. Sessões simultâneas em múltiplos dispositivos permitidas (vereador no app mobile + web), sem limite duro de sessões na V1. Admin interno: TTL especialmente curto, reautenticação com hardware key obrigatória para qualquer ação que atravesse `ente_id`.

**Eixo E — Avaliação dinâmica de permissões.**

*Topologia: defesa em profundidade.* Camada externa (middleware) faz checagens grossas que não dependem do recurso: token válido criptograficamente, `ente_id` da request bate com `ente_id` do token, vínculo ativo vigente, papel snapshot autoriza categoria da ação. Camada interna (in-domain) faz checagem fina por operação: cada operação de domínio chama `policy.check(ator, ação, recurso)` com o recurso já carregado, avaliando papéis estáticos do snapshot + funções de relação contra o recurso. Engine externa centralizada (PDP/OPA) descartada explicitamente — peso desnecessário na V1.

Mecanismos para garantir disciplina interna: convenção de naming (`dominio.acao(args, ator)` com `ator` no último parâmetro); lint/CI checa que toda função pública com `ator` chama `policy.check`; operações sem ator são proibidas em código de produto (jobs e workers usam `ator_sistema` explícito).

*Cache.* Memoization por request (mesma transação, mesma resposta) — zero invalidação, custo zero. Cache cross-request só se profiling justificar, não na V1. Funções de relação são consultas SQL simples sobre tabelas indexadas; otimização é responsabilidade do dono do bounded context.

*Modo de falha.* Fail closed por default (erro inesperado → 403 + log de severidade alta). Fail explicit para classe de erros transientes detectáveis (timeout, connection pool exausto, banco indisponível → 503 com header de retry). Erro de input (recurso não existe, parâmetro inválido) → 422 antes da auth. Taxonomia de erros com métricas separadas por categoria, mesma família da taxonomia de erros da fronteira IA (§22.3.5).

*Auditoria de decisões.* Toda operação de write registra (permit e deny). Reads sensíveis (audit log próprio, dados de outro vereador) registram. Reads de dado público não registram. Granularidade: ator (identidade + vínculo), ação, recurso (tipo + id), decisão, razão (qual cláusula da política decidiu), `ente_id`, timestamp. Audit log de auth segue retenção do audit log de produto (Invariante 10), particionado por `ente_id` + `created_at` desde V1.

*Testabilidade.* Cobertura obrigatória por operação autorizada: caminho feliz, denial por papel, denial por relação dinâmica, denial por estado do recurso, denial cross-tenant. CI bloqueia merge se cobertura cair abaixo do threshold. Mudança em política de autorização exige PR explícito com diff revisado — política como dado central torna mudanças visíveis. Ambiente de teste com identidades sintéticas em entes sintéticos (já decidido em §22.2).

**Eixo F — MFA e step-up para operações sensíveis.**

*MFA por tipo de vínculo.* **WebAuthn/passkey é o fator primário recomendado para servidor/vereador** (phishing-resistant por amarração ao origin, multifator num gesto); senha + TOTP permanece como piso obrigatoriamente disponível para quem não usa passkey. WebAuthn com hardware key física obrigatório para admin interno (sem passkey sincronizado), TOTP como backup com justificativa. SMS não aceito por default (SIM swap). **E-mail código de uso único** é canal de **bootstrap de primeiro acesso (enrollment) e de recuperação** — **nunca fator standing de login** (posse de inbox é fator único e phishável; substitui a antiga "senha temporária one-time"). Enrollment imediato no primeiro acesso: código de uso único enviado ao e-mail institucional inicia a sessão e **obriga** a configurar passkey (ou TOTP como piso) antes de qualquer outra ação; admin do ente não pode desabilitar MFA — política é global.

*Step-up para operações sensíveis.* Reautenticação imediata antes de ação específica. Operações com step-up: assinar artefato legal (ICP-Brasil), aprovar e publicar ata revisada (TOTP/WebAuthn), encerrar votação (TOTP/WebAuthn), editar proposição já protocolada em fluxo de correção auditada, cassar/encerrar mandato manualmente, mudar configuração do ente, ler audit log de outro usuário, ações de admin interno cross-ente (sempre, com hardware key). Operações comuns de tramitação (votar, comentar internamente, redigir parecer) **não** exigem step-up — fluxo de sessão precisa ser fluido.

Janela de step-up: default 5 minutos para servidor/vereador, configurável até 15 minutos por ente; admin interno 1 minuto, não configurável. Step-up é evento explícito (`StepUpRealizado`) que entra no audit log e pode ser checado por `policy.check` via cláusula `requer_step_up_recente_em: <categoria>, janela_max_min: N`.

*Posição estrita da ICP-Brasil.* Reservada exclusivamente para assinatura digital de artefato legal com valor de não-repúdio. Validação em duas camadas: (a) certificado válido na cadeia ICP-Brasil contra raiz oficial; (b) **CPF do certificado bate com CPF da identidade da sessão atual**. Sem essa segunda checagem, ICP-Brasil de outra pessoa "logada como" o vereador certo passaria. Assinatura armazenada como artefato persistente (tabela `assinatura_digital`) vinculado ao recurso, com timestamp confiável. Distinção autenticação ↔ assinatura é arquitetural — modelagem separada de sessão de auth.

Razões para não usar ICP-Brasil em autenticação cotidiana: atrito mata adoção (token USB no celular não funciona; app mobile não suporta A3); gerenciamento de certificado é responsabilidade da pessoa, vira incidente operacional nosso; MFA com TOTP+senha já é forte para autenticação. Razões para não usar como step-up genérico: TOTP/WebAuthn fazem step-up bem, baratos e ergonômicos; ICP-Brasil só faz sentido quando a saída é assinatura juridicamente válida; reservar para assinatura mantém o ato deliberado e excepcional.

Caso especial — ata da sessão: ata revisada e publicada exige assinatura ICP-Brasil do secretário e/ou presidente. **Aprovar e publicar** exige step-up TOTP/WebAuthn. **Assinar** é o ato adicional com ICP-Brasil. Dois passos distintos, registrados separadamente.

*Recuperação de fator e bypass.* Reset de fator é sempre ato auditado, nunca autoatendido para servidor/vereador/admin. Servidor perde TOTP → admin do ente reseta (audit). Vereador perde TOTP → admin do ente reseta com notificação a 2º responsável quando configurado. Servidor/vereador com poder elevado → reset exige aprovação de outro humano (modelado via política de autorização sobre operação `resetar_mfa(sujeito)` no padrão dual). Admin interno perde hardware key → outro admin faz revogação + emissão de nova key sob two-person rule. Reset de emergência em véspera de sessão crítica é fluxo explícito, com auditoria pós-fato. Cidadão recupera via gov.br (fora do nosso fluxo).

*Detecção de risco e step-up adaptativo.* Fora da V1 (modelo de risco real exige baseline de comportamento que só temos depois de meses operando; falsos positivos altos sem baseline). Adicionado em V1.5 ou V2. O que entra na V1 mesmo sem detecção adaptativa: notificação por e-mail a cada novo login de dispositivo desconhecido (default ligado para perfis de poder elevado, ativável por usuário); histórico de sessões visível para o próprio usuário ("ver minhas sessões ativas" e "encerrar sessões").

**Eixo G — Eventos de autenticação e autorização.**

*Taxonomia em quatro classes* (refinamento do Invariante 7 para o domínio de auth):

*Classe 1 — Domain events no bus interno* (Invariante 2): mudanças de estado de mandato/vínculo/papel. `MandatoIniciado`, `MandatoLicenciado`, `MandatoEncerrado`, `MandatoCassado`, `CargoMesaAssumido`, `CargoMesaEncerrado`, `MembroComissaoAdicionado`, `MembroComissaoRemovido`, `VinculoCriado`, `VinculoEncerrado`, `VinculoSuspenso`, `IdentidadeExternaVinculada`, `IdentidadeExternaDesvinculada`, `TokensRevogadosPorIdentidade`. Persistidos como auditoria, propagam para outros bounded contexts, candidatos naturais a webhook na V2.

*Classe 2 — Audit log de produto* (Invariante 10): atos auditáveis expostos ao cliente via UI. `LoginRealizado`, `LoginFalhou` (agregado em janela), `LogoutRealizado`, `SessaoExpirada`, `StepUpRealizado`, `StepUpFalhou`, `MFAEnrollmentRealizado`, `MFAFatorAdicionado`, `MFAFatorRemovido`, `MFAResetadoPorAdmin` (com justificativa), `MFAResetEmergenciaAprovado` (com aprovador), `SenhaResetada`, `SenhaTrocadaPeloUsuario`, `PoliticaAuthAlteradaNoEnte`, `AssinaturaDigitalRealizada` (vinculada ao recurso), `AssinaturaDigitalFalhou`, `AcessoNegadoPorPolitica` (denials são sinal forte), `AcessoSensivelRealizado`. Append-only, retenção regulatória.

*Classe 3 — Logs de aplicação:* erros, exceções, traces. Não são domain events nem audit. Retenção curta (30-90 dias). Sistema de logs, não audit log.

*Classe 4 — Métricas:* contadores agregados, ~13 meses (Invariante 7). Métrica nunca duplica audit (contar logins é métrica; registrar cada login é audit).

Disciplina anti-confusão: audit log nunca recebe log de aplicação; log de aplicação nunca recebe domain event; métrica nunca duplica audit. Reforço explícito porque auth é o lugar onde mais se erra essa separação.

*Retenção por classe* (defaults sugeridos com pisos legais; tunables conforme §22.5.3):

| Classe | Default | Configurável |
|---|---|---|
| Domain events de mandato/vínculo/papel | Permanente | Não — fato histórico |
| `LoginRealizado`/`LogoutRealizado`/`SessaoExpirada` | 18 meses | Por ente, mín. 12, máx. 36 meses |
| `LoginFalhou` (agregado) | 6 meses | Tunable global |
| `StepUpRealizado`/`StepUpFalhou` | 5 anos | Não configurável |
| `MFAResetado*`/`SenhaResetada` | 5 anos | Não configurável |
| `AssinaturaDigitalRealizada` | Permanente | Não — vinculada ao recurso |
| `AcessoNegadoPorPolitica` | 2 anos | Por ente, dentro de limites |
| `AcessoSensivelRealizado` | 5 anos | Não configurável (LGPD) |
| `PoliticaAuthAlteradaNoEnte` | Permanente | Não |

Para `LoginFalhou`: modelo de agregação por janela — registro individual nas primeiras N falhas em janela curta, depois agregação por (identidade_tentada, motivo, hora). Evita explosão de volume sem perder sinal de detecção.

Pisos legais refletem entendimento prático LAI/LGPD/auditoria, não parecer jurídico. Revisão pelo jurídico especializado em direito digital + administrativo é deferida; estrutura permite ajuste sem refactor.

*Direito ao apagamento (LGPD).* Política diferenciada por classe:

- *Não-apagáveis:* atos legais assinados (proposições, pareceres, atas, votações), eventos de mandato/cargo/Mesa, audit log de operações de servidor/vereador no exercício da função pública, assinaturas digitais. Justificativa: LGPD admite exceção para cumprimento de obrigação legal/regulatória, exercício regular de direitos, e interesse público sobre exercício de mandato.
- *Apagáveis sob solicitação:* audit log de cidadão (logins, acessos, consultas), comentários de cidadão (substituídos por placeholder "[comentário removido]" preservando integridade de thread), pedidos de e-SIC após prazo legal cumprido.
- *Pseudonimizáveis:* audit log de servidor/vereador para eventos não-funcionais (login pessoal, consulta a dados próprios) — após N anos, identidade substituída por hash estável; evento mantido para análise agregada.

Mecânica: evento `ApagamentoSolicitado(sujeito_id, escopo, base_legal)` (já mencionado em §22.3.4) dispara processo orquestrado. Apagamento nunca é DELETE silencioso — é ato registrado com escopo declarado e base legal documentada; auditoria do apagamento sobrevive ao apagado. Resposta ao titular tem prazo legal e é registrada (`PedidoApagamentoRespondido`).

Caso especial vereador ex-vereador: atos públicos (proposições, votações, falas) não-apagáveis (interesse público); dados pessoais não-funcionais (telefone, endereço, e-mail pessoal) apagáveis. Distinção dado funcional público ↔ dado pessoal acessório clara no modelo.

*Consentimentos.* Entidade `consentimento(identidade_id, finalidade, base_legal, concedido_em, revogado_em, versao_termo)`. Pontos de coleta: vinculação inicial gov.br (aceite de termo do ente), ativação de notificação opcional, comentário público em proposição. Termos versionados — mudança gera nova versão; consentimento antigo permanece atrelado à versão antiga. Revogação é evento auditado (`ConsentimentoRevogado`); tratamentos com base "consentimento" cessam em revogação, tratamentos com base "obrigação legal" não dependem.

Servidor/vereador no exercício da função: base legal é "execução de contrato/exercício regular de função pública", **não** consentimento. Coletar consentimento aqui seria erro conceitual com efeito ruim (sugere que pode revogar e parar o tratamento, o que não é verdade).

*Eventos de auth não atravessam a fronteira para a Plataforma de IA.* Plataforma de IA opera como `ator_sistema` próprio quando processa transcrição/ata/etc. Atravessamento humano só apareceria em V2.5+ com chatbot cidadão consultando seus próprios dados — e mesmo aí seria via propagação de contexto da request, não via evento de auth.

#### 22.5.3 Disciplinas arquiteturais derivadas

Padrões que aparecem repetidamente nos sete eixos e viram regra geral, não decisão por feature:

**1. Identidade ↔ Vínculo separados.** Identidade ancorada em CPF é supratenant (não tem `ente_id`). Vínculo é a relação da identidade com um Ente (servidor/vereador/admin) ou Município (cidadão). Mesmo CPF pode ter múltiplos vínculos. Provedor de identidade externa vincula a `identidade`, não a `vinculo` — uma identidade pode ter múltiplos vínculos com o mesmo CPF gov.br. Audit limpo: query por `identidade_id` atravessa todos os vínculos; query por `ente_id` em cada vínculo isola escopo.

**2. Papéis estáticos centrados em pessoa em tabela; papéis contextuais a recurso no recurso.** Papel estático ("vereador", "servidor_protocolo") em `usuario_papel`. Papel contextual ("relator deste parecer", "membro desta comissão") como coluna FK ou tabela de junção no bounded context dono, exposto como função de relação. Não há tabela genérica única de papel com contexto polimórfico.

**3. Motor de autorização compartilhado, política como dado por bounded context.** `policy.check` é mecânica transversal (motor, código compartilhado). A política declarativa de cada ação mora no bounded context dono do recurso. Política reusa a DSL pequena compartilhada com tramitação (§22.4 eixo C) e compliance (Invariante 4) — disciplina arrastada de §22.4.3 disciplina 5, agora estendida para auth.

**4. Mandato como entidade com cascata de papéis temporais.** Mandato vive em entidade própria com estado. Cascata via `tem_mandato_vigente`: licença ou cassação derruba todos os papéis temporais em uma operação, sem desativar individualmente. Atribuição dos papéis permanece registrada (audit histórico); apenas o exercício é suspenso. Suplente que assume mandato titular não herda automaticamente cargos/membership do titular licenciado.

**5. Toda função de relação aceita `instante` como parâmetro.** Default `now()`; consulta histórica usa instante específico. "Quem era presidente da Mesa em 12/03/2024?" sempre tem resposta consultando dados dessa data, não dados de hoje. Append-only com mutação controlada permite isso sem refactor.

**6. Distinção autenticação ↔ assinatura é arquitetural.** Sessão de auth é efêmera, vinculada ao ator. Assinatura digital é artefato persistente, vinculada ao recurso. Modelagem separada (`assinatura_digital` é tabela própria, não derivação de sessão). ICP-Brasil é mecanismo de assinatura, não de autenticação; cada assinatura é ato deliberado com hash do conteúdo específico, não derivado da sessão.

**7. Tunables operacionais como configuração, não como código.** Todo parâmetro operacional sensível ao contexto (TTL, retenção, threshold, janela, limiar) é configurável, com defaults sugeridos funcionando como ponto de partida. Configuração tem escopo (global, por tipo de vínculo, por ente). Limites globais protegem contra valores inseguros (cliente pode reduzir TTL, não aumentar além do teto). Mudanças de configuração são atos auditáveis. Configuração de tunable é dado, não release — bate com Invariante 4. Default seguro: se configuração corromper ou faltar, sistema usa default; nunca falha aberto.

**8. Defesa em profundidade na avaliação de autorização.** Camada externa (middleware) faz checagens grossas independentes do recurso. Camada interna (in-domain) faz checagem fina por operação com recurso carregado. Cada camada faz o que é boa em fazer. RLS no banco (§22.2) é defesa final, não defesa primária.

**9. Taxonomia de eventos de auth em quatro classes.** Domain events no bus, audit log de produto, logs de aplicação, métricas. Cada classe tem destino, retenção e público próprios. Disciplina anti-confusão: audit nunca recebe log de aplicação; log de aplicação nunca recebe domain event; métrica nunca duplica audit. Auth é o lugar onde mais se erra essa separação — reforço obrigatório.

**10. Apagamento LGPD é política diferenciada, nunca DELETE silencioso.** Política por classe de evento decide se apaga, pseudonimiza, ou recusa com motivo. Apagamento é ato auditado com escopo e base legal. Auditoria do apagamento sobrevive ao apagado.

#### 22.5.4 Decisões deferidas e pontos a confirmar

Decisões concretas de implementação deferidas para outros chats:

**Para o chat de stack/infra:** provedor de IdP concreto (Cognito vs. Keycloak vs. Auth0 vs. próprio); biblioteca/framework para WebAuthn server-side; biblioteca/SDK para validação de cadeia ICP-Brasil; mecanismo concreto de revogação imediata de tokens (lista negra in-memory + Redis vs. JWT com expiração curta + revogação por TTL); particionamento concreto do audit log; mecanismo de agregação de `LoginFalhou` (worker + janela vs. roll-up periódico).

**Para revisão jurídica (após contratação de jurídico especializado em direito digital + administrativo):** revisão dos pisos legais de retenção em §22.5.2 eixo G; texto canônico dos termos de consentimento por finalidade; política concreta de anonimização vs. pseudonimização para casos limítrofes; tratamento de dados de menores em audiência pública e participação cidadã.

**Para validação com pesquisa qualitativa antes do lançamento:** taxa real de adoção de gov.br entre cidadãos engajados em política municipal no Nordeste (premissa do eixo A); apetite real de servidores/vereadores por WebAuthn vs. TOTP em diferentes faixas de letramento digital; calibração das janelas de step-up (5 min default funciona, ou frusta?); calibração do modelo de "comentário/manifestação anônima" como configuração por ente.

**Para V1.5/V2:** detecção de risco e step-up adaptativo; modelo concreto de baseline de comportamento; painel de risco para admin do ente; capacidade de exigir nível mínimo gov.br por fluxo (arquitetura suporta, exposição diferida); SSO com AD/LDAP municipal; federação com outros IdPs.

**Para confirmar com especialista em regimento (após contratação):** ordem regimental de substituição da presidência da Mesa é universal o bastante para template padrão, ou variável por câmara? Reset de fator de vereador em véspera de sessão crítica precisa de fluxo regimental específico ou basta o fluxo de emergência genérico? Aprovação dual para reset de poder elevado deve seguir hierarquia regimental (presidente da Mesa aprova reset de secretário-geral, etc.) ou pode ser configurada livremente pelo ente?

### 22.6 Modelo de sessão plenária, áudio e real-time

Esta subseção consolida as decisões fechadas sobre o modelo de dados do bloco que cobre sessão plenária, captura e processamento de áudio/vídeo, e canal de tempo real para clientes finais. As decisões foram tomadas em oito eixos discutidos individualmente (A — Entidade sessão; B — Pauta; C — Presença e quórum; D — Áudio/vídeo; E — Diarização e transcrição; F — Tribuna; G — Real-time; H — Bulk histórico). Eixo H saiu da pauta na discussão deste bloco — cliente piloto descartou a eventualidade de importar gravações históricas; tema reabre quando virar exigência real. Sete eixos foram fechados; aqui estão sintetizados como referência canônica. Esta subseção fecha os itens 1 e 2 da antiga §22.6 (modelo de real-time e modelo de dados de sessão + áudio).

#### 22.6.1 Visão geral e taxonomia de entidades

O bloco se materializa em entidades agrupadas por papel:

**Entidades temporais e estruturais.** `legislatura` (período de 4 anos), `sessao_legislativa` (ano legislativo dentro da legislatura), `sessao` (encontro plenário individual).

**Entidades de pauta.** `pauta_sessao` (uma por sessão), `pauta_sessao_versao` (snapshots canônicos append-only), `pauta_item` (estado mutável durante execução), `pauta_alteracao` (alterações intra-sessão append-only).

**Entidades de presença.** `presenca_evento` (append-only, com modalidade plenário/remoto e tipo `entrada` | `saida` | `retorno` | `mudanca_modalidade`), `justificativa_ausencia` (ato administrativo apartado).

**Entidades de tribuna.** `inscricao_oradores` (intenção de fala com `origem_inscricao`), `fala_executada` (execução com cronômetro derivado), `decisao_mesa` (registro de decisões do presidente sobre questão de ordem).

**Entidades de gravação.** `gravacao_segmento` (unidade técnica do arquivo de gravação, múltiplos possíveis por sessão).

**Entidade de ata.** `ata_publicada` com discriminator `origem_redacao` aceitando `redigida_externamente` (V1) e `gerada_automaticamente` (entrega futura, schema preparado).

**Ponteiros de transcrição no core.** `transcricao_sessao` (metadata leve no core apontando para transcrição que vive na Plataforma de IA).

**Entidades de transcrição na Plataforma de IA.** `transcricao_versao` (versionada por modelo), `transcricao_palavra` (granularidade canônica), `fala_atribuicao` (cluster do diarização → vereador), embeddings por sentença.

#### 22.6.2 Decisões por eixo

**Eixo A — Entidade sessão, tipos e ciclo de vida.** Hierarquia temporal em três entidades explícitas (`legislatura` → `sessao_legislativa` → `sessao`) com FK encadeada — consistente com a disciplina §22.5 de "papéis temporais como entidade explícita" e com a `legislatura` já requerida por `mandato`. Tipo de sessão como enum nominal (`ordinaria` | `extraordinaria` | `solene` | `secreta` | `especial`) + capabilities como atributos da própria sessão (`delibera`, `transmite_publica`, `gera_ata_regimental`, `permite_voto_secreto`, `permite_modalidade_remota`), defaults derivados do tipo com override individual registrado em audit. Estados grandes na entidade `sessao` (`agendada` | `aberta` | `suspensa` | `encerrada` | `não_realizada` | `arquivada`); fase do expediente vs. ordem do dia como atributo derivado da pauta corrente, não estado da sessão (consistente com disciplina §22.4 de não inflar state machine principal quando subprocessos resolvem). `não_realizada` como estado terminal alternativo a `encerrada` para sessões prejudicadas por falta de quórum, luto institucional ou caso fortuito. Modalidade da sessão (`presencial` | `remota` | `hibrida`) como atributo independente do tipo, default `presencial`. Numeração canônica por `(ente_id, sessao_legislativa_id, tipo_sessao, numero_sequencial)` resetando por sessão legislativa.

**Eixo B — Pauta.** Entidade `pauta_sessao` única por sessão (descartadas: pauta como projeção sem entidade; blocos por fase como entidade própria), com itens carregando `fase` como atributo (`expediente` | `grande_expediente` | `ordem_do_dia` | `explicações_pessoais` | `tribuna_livre_cidadao`). Versionamento híbrido: snapshots canônicos pré-sessão em `pauta_sessao_versao` (publicação inicial, republicação pré-sessão, snapshot de execução final — append-only, similar ao padrão §22.4 eixo B), e alterações intra-sessão em `pauta_alteracao` (append-only, modeladas como eventos com `tipo` ∈ `inclusao` | `exclusao` | `inversao` | `retirada_pedido_autor`). Tipos de item com enum fechado e FKs declarativas por tipo (proposição com `proposicao_id`, leitura/comunicado/homenagem com `texto_descricao` fallback) — descartado polimorfismo aqui pelo conjunto pequeno e estável. Votação aponta para proposição via `(objeto_tipo, objeto_id)` polimórfico de §22.4 eixo G, com `pauta_item_id` opcional como contexto temporal — distinção importante: votação é sobre a matéria, não sobre o item da pauta; matéria pode ser votada em duas sessões (1ª e 2ª discussão), duas votações com `pauta_item_id` diferentes mas mesma `proposicao_id`. Visibilidade pública/restrita como atributo da versão da pauta (`pauta_sessao_versao.publica`), não capability da sessão — permite porção republicada como pública mesmo em sessão originalmente secreta.

**Eixo C — Presença e quórum.** Eventos append-only em `presenca_evento` com `tipo` ∈ `entrada` | `saida` | `retorno` | `mudanca_modalidade` e `modalidade` ∈ `plenario` | `remoto` (descartadas: presença binária por sessão; intervalos explícitos com risco de não-fechamento). Função canônica `está_presente_em(usuario, sessao, instante = now())` materializada como busca do último evento por vereador até `instante`. Captura na V1 com três fontes: `painel_eletronico` quando câmara tem; `manual_secretaria` como caminho universal; `inferida_por_voto` e `inferida_por_tribuna` materializadas como eventos explícitos quando vereador vota ou usa tribuna sem check-in formal — todas as inferências viram evento concreto, evitando código de fallback frágil. Precedência em conflito: `manual_secretaria > painel_eletronico > inferida_*`. Justificativa de ausência como ato administrativo apartado (`justificativa_ausencia` com state machine pequena `pendente` → `aprovada` | `indeferida`, não como tipo de evento de presença) — naturezas diferentes (fato observado vs. juízo administrativo posterior). Quórum como consulta derivada sobre `presenca_evento` (sem materialização em snapshots), agregadores `presentes_plenario(sessao, instante)` e `presentes_remoto(sessao, instante)` expostos à DSL do motor de votação. Presidente não-vota como regra DSL no motor de votação, não no modelo de presença. **Decisão de simplificação na V1 — integração com plataforma de videoconferência fica em Nível 1:** presença remota é declarada manualmente pela secretaria via UI, sem integração técnica com plataforma; câmara usa Zoom, Google Meet ou outra plataforma à sua escolha; nosso sistema apenas observa o resultado via marcação humana. Captura via `manual_secretaria`. Enum `origem` na V1 **não** inclui `videoconferencia`. **Nota prospectiva:** quando integração técnica Nível 2 entrar em backlog (provável V1.5/V2), plataformas suportadas em ordem de prioridade são (1) Zoom e (2) Google Meet — registro permite que pesquisa qualitativa pré-construção valide a ordem antes do trabalho de engenharia.

**Eixo D — Áudio/vídeo: captura, armazenamento, ciclo de vida.** `gravacao_segmento` modelado como **unidade técnica do arquivo de gravação** (não unidade regimental da sessão) — uma sessão típica gera um único segmento, mas múltiplos são possíveis em casos reais (falha técnica e reinício do OBS; opção da câmara em dividir manualmente). Alinhamento entre arquivos e fatos regimentais da sessão é por instante, não por chave hierárquica forte. Schema com `iniciou_em`, `encerrou_em`, `motivo_inicio`, `motivo_fim`, `audio_uri`, `video_uri` nullable, `container_bruto_uri`, `audio_hash` sha256, `fonte_ingestao` ∈ `gravacao_local_pos_sessao` | `rtmp_duplicado_ao_vivo` | `youtube_api_fallback` | `importacao_legado` (consistente com §22.3.4 v1.6/v1.7; `importacao_legado` presente no enum mas sem fluxo produtor V1 — entra quando bulk histórico voltar à pauta), `acesso_restrito` booleano para sessão secreta. Fonte de captação V1: endpoint de ingestão padronizado e agnóstico à fonte (§16.4 v1.7) recebe upload do utilitário CLI/watch folder que detecta arquivo finalizado em pasta configurada, calcula hash, faz upload com retomada e deduplicação. Sem chunks técnicos persistidos na V1 (RTMP duplicado e processamento ao vivo dependem do satélite Plugin de Captura Sincronizada — V2+). Vinculação entre arquivo recebido e sessão regimental é feita pelo servidor via UI pós-upload (Opção A); convenção de nome de arquivo é atalho opcional. Estado de processamento derivado de vínculos e eventos, sem coluna mutável — evento `GravacaoSegmentoCaptado(sessao_id, segmento_id, audio_uri, video_uri, ...)` entra no conjunto canônico de eventos de integração da fronteira core → IA de §22.3.3. Container bruto (MP4/MKV) é objeto opaco do core; extração de áudio em formato técnico para transcrição é responsabilidade da Plataforma de IA. Vídeo como cidadão de segunda classe (`video_uri` nullable, não processado pela IA na V1; arquivamento institucional + insumo futuro para shorts V1.5).

**Eixo E — Diarização, atribuição e transcrição.** Transcrição vive na Plataforma de IA (§22.3.4), em granularidade canônica de palavra (`transcricao_palavra` com `t_inicio_ms`, `t_fim_ms`, `speaker_cluster_id`, `confidence`); sentença e turno são projeções computadas sob demanda. O core mantém metadata leve em `transcricao_sessao` (ponteiros + counts), sem replicar conteúdo. Versionamento explícito por modelo: `transcricao_versao` append-only (descartado: transcrição mutável com modelo corrente); reprocessamento com modelo melhor gera versão nova preservando rastreabilidade. Atribuição de fala a vereador (cluster anônimo → identidade) na V1 via combinação: Caminho C (inferência por contexto de domínio — tribuna, votação nominal, presidência em exercício no instante) pré-atribui automaticamente fragmentos cobertos por dados estruturados do core; revisão manual pelo servidor preenche clusters não-ancorados; identificação automática por voiceprint (Caminho A) fica para V1.5+ quando dados de várias sessões formarem perfis vocais maduros. Embeddings por sentença na V1 (multi-resolução só em V2 com caso de uso específico). Entidade `fala_atribuicao` na Plataforma de IA carrega `sujeito_tipo` ∈ `vereador` | `servidor` | `convidado` | `cidadao_inscrito` | `publico_nao_identificado` | `descartar`. Fluxo de revisão humana: eventos `TranscricaoBrutaConcluida`, `TranscricaoAtribuida`, `TranscricaoRevisada` atravessam a fronteira IA → core como eventos de integração. **Decisão 20/06/2026 (reverte a v1.7):** geração automática de ata **entra na V1** como feature-âncora — **e o schema não muda**. `ata_publicada` já fora desenhado (v1.7/v1.8) com o discriminator `origem_redacao` aceitando ambos `redigida_externamente` **e** `gerada_automaticamente` (com `transcricao_versao_base_id`, `rascunho_llm_uri`, `modelo_llm_id`, `prompt_versao` nullables). A V1 agora **expõe os dois caminhos**: a ata-IA produz `gerada_automaticamente` (rascunho sob revisão humana obrigatória, §16.8); a anexação pelo servidor produz `redigida_externamente`. A reconciliação foi só do **gate de capability de produto**, não do modelo de dados — o `TranscricaoRevisada` agora tem consumidor downstream V1 também no motor de geração de ata. **Revisão manual de transcrição pelo servidor é opcional na V1** — câmara que revisa tem busca semântica de qualidade superior; câmara que não revisa tem busca funcional mas com atribuição menos precisa via Caminho C apenas. Na V1, `TranscricaoRevisada` tem consumidor downstream limitado (motor de busca semântica para melhor indexação, audit log de produto); consumo pelo motor de geração de ata é futuro quando feature liberar.

**Eixo F — Tribuna.** `inscricao_oradores` como entidade única com `origem_inscricao` discriminando caminhos (`pre_sessao_app` | `pre_sessao_secretaria` | `intra_sessao_pedido` | `automatica_por_autoria`); `fala_executada` como entidade separada de inscrição (intenção vs. execução; inscrição pode terminar em `desistencia` sem gerar fala). `fala_executada.tipo_fala` ∈ `principal` | `aparte` | `pela_ordem` | `questao_de_ordem` | `explicacao_pessoal` | `comunicado`. Apartes vinculados via `fala_pai_id` permitindo reconstruir estrutura "fala principal com apartes inseridos". Cronômetro como projeção sobre eventos (`FalaIniciada`, `FalaEncerrada`, `CronometroPausado`, `CronometroRetomado`, `AparteConcedido`, `TempoAdicionalConcedido`), não persistido como snapshot — `tempo_efetivamente_usado_segundos` computado ao encerrar. Tempos regimentais por fase e por câmara como configuração, não engessados. `decisao_mesa` como entidade apartada para registro de decisão do presidente sobre questão de ordem (ato regimental com efeito jurídico, vai para ata — disciplina §22.4.3 de "atos auditados têm registro próprio"). Tribuna subordinada à fase da pauta, não ortogonal — execução ocorre dentro de fase ativa, com vínculo opcional a `proposicao_ref_id` quando matéria específica. Sessão solene reusa `fala_executada` com campos relaxados (`inscricao_id` nullable, tempos não rigidamente aplicados); diferença é capability `delibera=false` da sessão (Eixo A), não estrutura modelar diferente. Eventos da tribuna são consumidos por (a) painel ao vivo via SSE (Eixo G), (b) ata humana ou rascunho automático (Eixo E), (c) atribuição automática Caminho C (Eixo E) — sistema sabe que orador X está com a palavra no intervalo `[iniciou_em, encerrou_em]` da `fala_executada`, então cluster de diarização cobrindo esse intervalo é pré-atribuído a X.

**Eixo G — Real-time durante a sessão.** SSE (Server-Sent Events) como protocolo único para clientes finais na V1 (descartados: WebSocket pela complexidade operacional desnecessária dado o perfil unidirecional dominante; long polling como protocolo principal), com long polling como fallback degradado para clientes em ambiente com proxy agressivo. Consistente com §22.3.2 que já adotou SSE entre core e Plataforma de IA. SSE é **projeção do bus interno**, não substituto — eventos do bus (`VotoRegistrado`, `FalaIniciada`, `PresencaEventoRegistrado`, etc.) são consumidos por serviço de projeção SSE que decide quais virar mensagens para quais canais. Canais: `sessao/{id}/plenario` (painel ao vivo, eventos públicos da sessão), `vereador/{id}/dashboard` (subconjunto filtrado + notificações pessoais), `publico/sessao/{id}` (subconjunto público para portal cidadão, sem voto secreto individualizado). Autorização avaliada na abertura da conexão e por evento via `policy.check` in-domain (§22.5), sem revogação dinâmica em conexão aberta na V1 — re-avaliação ocorre no próximo reconnect. Sequência monotônica por canal (`{canal}:{seq}`); janela de retenção de 5 minutos em Redis stream para suportar replay via `Last-Event-ID`; reconexão após esse limite recebe snapshot completo do estado corrente da sessão como evento inicial. Fan-out dentro do monolito como módulo dedicado na V1 ("Real-Time Projection"); extração para serviço separado fica para V1.5+ quando volume justificar (consistente com §22.2 — monolito como espinha, satélites extraídos sob demanda). Cronômetro de tribuna como marcos + cálculo client-side (descartado: ticks por segundo) — servidor emite transições estruturais (`FalaIniciada`, `CronometroPausado`, `TempoAdicionalConcedido`); cliente computa display local; round-trip NTP-like na conexão inicial ajusta drift de relógio (drift de 10-50ms aceitável; detalhes de implementação para chat de stack). O que **não** é streamado em SSE na V1: transcrição ao vivo (depende do satélite Plugin de Captura Sincronizada V2+), voto secreto individualizado durante a votação (apenas resultado agregado pós-encerramento), conteúdo grande como PDFs e áudios (SSE carrega URIs, não bytes).

#### 22.6.3 Disciplinas arquiteturais derivadas

Padrões que aparecem repetidamente nos eixos do bloco e viram regra geral, não decisão por feature:

**1. Tempo é coordenada de primeira classe na sessão plenária.** Único contexto do produto onde "quando" é dado primário, não metadata de auditoria. Cada voto, fala, presença, item despachado carrega instante como parte do dado de domínio, e instante é parâmetro de funções de relação (`está_presente_em`, `quem_está_na_tribuna`, etc.). Disciplina arrastada de §22.5.3 nº 5 ("instante como parâmetro em toda função de relação"), aplicada aqui com peso maior.

**2. Distinção entidade técnica vs. entidade regimental.** `gravacao_segmento` é fato técnico do arquivo de gravação; `sessao` é fato regimental do encontro plenário. Os dois não têm correspondência hierárquica forte (uma sessão pode gerar múltiplos arquivos ou um arquivo único; um arquivo pode cobrir múltiplas suspensões regimentais). Alinhamento é por instante. Padrão aplicável a outras tabelas técnicas vs. regimentais futuras (ex.: stream de RTMP vs. fase da sessão; transcrição automática vs. ata legal).

**3. Capabilities desacopladas do tipo nominal.** Tipo de sessão é dado (nome regimental usado em ata, em portal, em comunicação institucional); comportamento é capability declarada e overridável com auditoria. Padrão aplicável a outras entidades com tipo + variação de comportamento (já presente em §22.4 eixo A para proposições com STI híbrido + colunas tipadas; aqui formalizado como disciplina geral).

**4. SSE como projeção do bus interno, não substituto.** Bus interno de domain events (Invariante 2) continua verdade-fonte. SSE é projeção para clientes externos via serviço dedicado. Padrão geral: stream para o cliente é sempre projeção; nunca emite no bus diretamente. Vale para qualquer canal real-time futuro (webhooks públicos V2, integrações de cliente terceiro).

**5. Estado emergente vs. estado mutável.** Estado de processamento (transcrição, gravação, ata) derivado de eventos e vínculos, sem coluna `status` mutável. Consistente com a postura event-driven da §22.3 e com a disciplina §22.4.3 de "guards no domínio + constraint no banco bloqueando mutação imprópria". Decisão aplicada uniformemente em D.4 (gravação), E.1 (transcrição), B.1 (pauta executada).

**6. Eventos de cronômetro como marcos, não ticks.** Cliente computa duração local a partir de marcos estruturais (início, pausa, retomada, encerramento); servidor não emite tick por segundo. Reduz tráfego SSE para zero em estado estável, simplifica modelo, evita acoplamento de cronômetro a frequência de polling. Padrão aplicável a outros casos de temporização contínua que aparecerem (timer de inscrição em pauta, contagem regressiva de prazo regimental).

#### 22.6.4 Decisões deferidas e pontos a confirmar

Decisões concretas de implementação deferidas para outros chats:

**Para o chat de stack/infra:** protocolo concreto do utilitário CLI/watch folder (Python, Go, ou C# pelo ecossistema Windows típico das câmaras); biblioteca/framework para SSE server-side; configuração concreta de Redis stream para retention de 5 min; mecanismo concreto de NTP-like sync entre cliente e servidor para cronômetro; fila do pipeline de IA — simples FIFO na V1 (com prioridade entrando quando aparecer caso de uso real, fora da V1); formato de container e codec para arquivamento de áudio/vídeo (FLAC vs. WAV; H.264 baseline vs. H.264 main); particionamento concreto de `presenca_evento` e `fala_executada` por `ente_id`.

**Para validação com pesquisa qualitativa/prova de conceito antes do lançamento:** medição de DER (Diarization Error Rate) real em câmara pequena com setup típico do mercado-alvo (mix de áudio do OBS, microfone ambiente ou similar) — recomendado para mês 1-2 da V1 com áudio real de teste; medição de accuracy do Caminho A (identificação automática por voiceprint) em condições reais quando voiceprints maturarem para validar o gate V1.5+; calibração da janela de retenção SSE (5 min default funciona, ou clientes mobile precisam de mais?); validação da ordem de prioridade Zoom > Google Meet para integração Nível 2 com câmaras que usam sessão híbrida.

**Para confirmar com especialista em regimento (após contratação):** numeração de sessões por `(sessao_legislativa, tipo)` resetando anualmente é universal no Nordeste, ou existem câmaras que numeram contínuo cross-tipo ou cross-ano?; tempos regimentais por fase como configuração padrão de câmara (5min expediente, 10min ordem do dia) batem com a prática real ou precisam de variação maior?; aparte tem regra universal de cronômetro principal continuar correndo, ou varia significativamente entre regimentos?

**Para V1.5/V2:** identificação automática por voiceprint (Caminho A) quando voiceprints maturarem com dados V1; integração Nível 2 com plataforma de videoconferência (Zoom primeiro, Google Meet segundo); RTMP duplicado durante sessão via satélite Plugin de Captura Sincronizada habilitando transcrição quase ao vivo; fan-out SSE como serviço separado quando volume justificar; embeddings multi-resolução quando caso de uso específico aparecer.

**Para satélite Plugin de Captura Sincronizada:** markers de eventos legislativos como timestamps no arquivo de gravação (votação aberta/encerrada, orador chamado, ponto de pauta despachado) reduzindo alucinação da ata e habilitando transcrição estruturada por evento; integração WebSocket OBS para sincronização gravação ↔ domínio; browser sources prontos; auto-config; diagnóstico de áudio.

**Para geração de ata por IA — V1 (decisão 20/06/2026; reverte a v1.7, que a tratava como satélite/futuro):** deixa de ser satélite e entra na V1 como feature-âncora em modo produtividade, com revisão humana obrigatória (§16.8). O dataset golden (transcrição revisada, ata humana anexada) segue se formando das câmaras e retroalimenta a qualidade do modelo. O pitch comercial agora **nomeia a ata-IA como entrega da V1** (não mais "roadmap declarado"); o modo "registro oficial" substituto da ata escrita é que permanece configuração futura.

**Para confirmar quando bulk histórico voltar à pauta (eixo H reaberto sob demanda):** estratégia de atribuição em transcrição histórica (não-atribuída por default vs. uso parcial do Caminho A com voiceprints atuais); endpoints de ingestão histórica como API interna admin-only; caveat de qualidade visível em busca semântica e portal. Modelo deste bloco já comporta sem refactor estrutural — convenção §22.4.3 cobre campos `origem`/`origem_ref`/`origem_importado_em`; enums têm valor `importacao_legado` presente mas sem fluxo produtor V1.

### 22.7 Motor de regras de compliance

Esta subseção materializa o Invariante 4 (regras de compliance são dados, não código): na V1, o TCE-CE é **configuração**, não branch de código, e a arquitetura comporta os outros 26 TCEs como **conteúdo novo (dados)**, sem refactor estrutural. O bloco é trabalhado **por eixos**, em ordem deliberadamente não-sequencial (decisão de método): **A** (vocabulário da DSL) → **C** (stress-test com templates reais do TCE-CE) → **B** (schema das tabelas), seguido de cinco eixos adicionais. A inversão C-antes-de-B é proposital: validar que a forma da DSL expressa requisitos reais **antes** de cravar schema. Esta subseção consolida os **Eixos A (§22.7.2–22.7.3), C (§22.7.5), B (§22.7.6) e o Eixo de runtime (§22.7.7)**: o Eixo C **validou a forma A2** contra requisitos reais do TCE-CE e **derivou de carga real** o vocabulário que a §22.7.4 deixara parqueado; o Eixo B **cravou o schema estático** das tabelas de template/regra sobre esse vocabulário; o **Eixo de runtime** (primeiro dos +5, elevado por S1) cravou o **comportamento temporal** do motor — materialização de obrigação, avaliação, monitoramento de prazo e auditoria. Os demais eixos (+5: geração de artefatos de envio ao TCE; expansão a outros TCEs) seguem em aberto (roadmap em §22.7.4).

#### 22.7.1 Visão geral: a DSL é unificada aqui, não nasce aqui

A DSL declarativa **já existia** em três contextos antes de §22.7, sob a disciplina "motor declarativo compartilhado" (§22.4.3 disc. 5; §22.5.3 disc. 3), que sempre exigiu que fosse **uma só**. O Eixo A **formaliza o vocabulário comum** sobre o qual os quatro usos se apoiam:

- **Tramitação (§22.4 eixo C):** expressões booleanas pequenas — sem loops, sem variáveis mutáveis, sem efeitos colaterais; ações são identificadores que o motor mapeia para handlers; agregadores sobre subprocessos expostos como funções (`pareceres.todos_concluidos`, `pareceres.algum_rejeitou`, `pareceres.contagem_terminal >= N`).
- **Autorização (§22.5 eixo B):** decisões dinâmicas avaliadas como expressões sobre **funções de relação** expostas pelos bounded contexts donos dos recursos.
- **Regras de plenário (§22.6):** quórum, regras de votação por matéria e tempos de tribuna como configuração no motor, expondo agregadores como `presentes_plenario(sessao, instante)` e `presentes_remoto(sessao, instante)`.
- **Compliance (§22.7 — novo):** o envelope que o Eixo C vai estressar com templates reais do TCE-CE.

O Eixo A é a base que torna esses quatro usos **uma coisa só**, não quatro DSLs parecidas.

#### 22.7.2 Decisões do Eixo A (vocabulário da DSL)

1. **Forma "A2" — núcleo de expressão + envelopes YAML por contexto.** Um **núcleo de expressão** comum (a gramática booleana/valor sem efeitos colaterais herdada de §22.4 eixo C) embrulhado por **envelopes em YAML específicos por contexto**. O núcleo é o mesmo para todos; o envelope adapta forma e campos ao contexto. *(Opções alternativas de forma avaliadas e descartadas: a transcrever — §22.7.4.)*
2. **Type-checking estático no momento de salvar a regra.** A regra é tipada estaticamente no **save time**, não só na avaliação; regra mal-formada/mal-tipada **não chega a persistir como ativa**. Justificativa comercial que governa §22.7: uma regra falhando em runtime e fazendo um cliente **perder janela de envio ao TCE** é incidente inaceitável — empurrar a detecção para o save time tira essa classe de falha do caminho crítico. Bate com o Invariante 4 (regra é dado, mas **dado validado** antes de virar configuração ativa).
3. **Registry central de funções de relação, com ownership por bounded context.** Existe um **registry central** onde a DSL resolve o significado e a assinatura de cada função de relação; a **propriedade (implementação e manutenção)** de cada função pertence ao **bounded context dono do recurso**. Consistente com §22.5 eixo B. Alimenta o type-checker do save time (decisão 2).
4. **Sistema de tipos com primitivos e compostos.** O Eixo A fechou um sistema de tipos com tipos primitivos e compostos, usados pelo type-checker para validar expressões e chamadas. *(Lista exata de tipos e regras de coerção: a transcrever — §22.7.4.)*
5. **Conjunto de operadores núcleo.** Operadores lógicos, de comparação e demais definidos, coerentes com a natureza "sem loops, sem mutação, sem efeitos colaterais". *(Lista exata e precedência: a transcrever — §22.7.4.)*
6. **Inventário de funções builtin.** Biblioteca da própria DSL — distinta das funções de relação dos bounded contexts — cobrindo manipulação de datas/prazos, agregação sobre coleções e utilitários. *(Lista exata e assinaturas: a transcrever — §22.7.4.)*
7. **Schemas de envelope por contexto: compliance, tramitação, autorização.** Cada contexto tem um schema de envelope próprio em torno do núcleo. **Tramitação** formaliza o que §22.4 eixo C descreve (template, estado, transição, guard, ação); **autorização** formaliza §22.5 eixo B (política por ação sobre funções de relação); **compliance** é o envelope novo, alvo do Eixo C. *(Schema concreto de cada envelope, em especial compliance: a transcrever — §22.7.4.)*

#### 22.7.3 Disciplinas arquiteturais derivadas

1. **Uma DSL, um núcleo, múltiplos envelopes.** Não há quatro DSLs; qualquer evolução do núcleo é feita uma vez e propaga para todos os contextos.
2. **Validação no save time é disciplina, não otimização.** Regra mal-tipada não persiste como ativa; o type-checker é parte do contrato de salvar regra.
3. **Registry central, ownership distribuído.** Resolução central de assinaturas; implementação e manutenção no contexto dono. Promover ou alterar uma função de relação é decisão do contexto dono.
4. **Ferramental de simulação/debug compartilhado** entre os usos da DSL, consistente com a promessa de §22.4.3 disc. 5.

#### 22.7.4 Decisões deferidas e pontos a confirmar

**A transcrever da sessão de origem (não consolidado como canônico no v1.9).** A sessão que fechou o Eixo A é posterior ao v1.8 e seu detalhe granular **não está no material de handoff** — só o registro-resumo. Ficam pendentes de transcrição literal, **a confirmar antes de cravar**: a lista de tipos primitivos/compostos e regras de coerção (decisão 4); a lista de operadores núcleo e precedência (5); o inventário de builtins e assinaturas (6); o schema concreto dos envelopes, em especial o de compliance (7); a mecânica fina do registry — visibilidade entre contextos e versionamento de assinatura (3); e as opções de forma descartadas no Eixo A com o porquê (1). **Estes itens não foram inventados deliberadamente.** **Status pós-Eixo C (v1.10):** o stress-test (§22.7.5) **derivou de carga real** a maior parte deste vocabulário — tipos (4), operadores (5), builtins (6) e a forma do envelope de compliance (7) estão agora **listados em §22.7.5, justificados por requisito**, não mais por memória. O que **permanece a reconciliar** contra a sessão de origem: a mecânica fina do registry (3 — visibilidade entre contextos e versionamento de assinatura) e as opções de forma descartadas no Eixo A (1). O detalhe que falta é **complemento**, não **bloqueio** — a forma A2 já está validada.

**Roadmap dos eixos seguintes.** **Eixo C** ✅ **concluído (v1.10, §22.7.5)** — forma A2 validada. **Eixo B** ✅ **concluído (v1.11, §22.7.6)** — schema estático: definição (sem `ente_id`) + binding por tenant + registry como catálogo de infra + calendários; runtime roteado a um +5 eixo. **Eixo de runtime** ✅ **concluído (v1.12, §22.7.7)** — comportamento temporal: materialização de obrigação (`prazo_dominio_ativo` polimórfico, disc. 6), modelo de avaliação (evento + sweep + sob demanda), monitoramento de prazo (S1, com re-stamp no deslize de circular) e auditoria append-only (`compliance_avaliacao` = prova de compliance). **Fechou dois** dos +5 candidatos — *comportamento temporal* (S1) + *auditoria de avaliação*; **versionamento** já fora fechado pelo Eixo B (cópia integral). **+2 eixos restantes**, escopos a confirmar e não assumir: **geração de artefatos de envio ao TCE** no formato exigido (o runtime rastreia a *obrigação de enviar*; este eixo gera o *arquivo*); **estratégia de expansão para os outros 26 TCEs sem refactor** (Invariante 4 — **o Eixo C mostrou que `dominio` é taxonomia em camadas, não "qual TCE", §22.7.5 S2**).

**Plenário e envelope — RESOLVIDO no Eixo C (§22.7.5, achado S4).** As regras de §22.6 (quórum, votação por matéria, tempos de tribuna) **usam o envelope de _guard_ de tramitação/plenário, não o de compliance**: têm semântica de "esta ação é válida agora?" (guard em runtime), não de "obrigação que vence num prazo" (compliance). O **núcleo** é compartilhado (forma A2: um núcleo, múltiplos envelopes); os **envelopes** diferem por semântica.

#### 22.7.5 Eixo C — stress-test da DSL (forma validada, vocabulário derivado de carga real)

O Eixo C pegou requisitos **reais** do TCE-CE da parte legislativa (varredura sourced: remessa/prazos, atos legislativos, transparência ativa, quórum) e os expressou como **templates no envelope de compliance**, para descobrir lacunas no vocabulário **antes** de cravar schema. Rascunho de origem com os templates escritos, fontes e a classificação completa: `docs/05-eixo-C-stress-test-rascunho.md`.

**Veredito.** ✅ **A forma A2 sobreviveu.** Três requisitos diversos encaixaram limpo no envelope (remessa mensal ao TCE; transparência em tempo real condicional ao porte; publicação de ato legislativo); um (quórum) **não encaixou de propósito** e a não-aderência foi o resultado (achado S4). **Nenhuma decisão estrutural do Eixo A precisou reabrir** — as descobertas **preenchem** a §22.7.4 com vocabulário **justificado por requisito real**.

**Vocabulário derivado (preenche o parqueado de §22.7.4, decisões 4/5/6/7):**

- **Builtins (dec. 6):** relógio injetado determinístico (`hoje`/`agora`); aritmética de calendário (`fim_de` competência; `proximo_dia_util` e `soma_dias_uteis`, ambos dependentes de um **calendário de feriados nacional+municipal como dado de domínio**); `arredonda_cima` (ceil) — **obrigatório**, o atalho "metade mais um" erra em N ímpar; `fracao(num,den)` com **aritmética exata/racional** (não float, senão o ceil de maioria erra); `prazo_vigente(dominio,tipo,chave)` (lê calendário de domínio com override por Ofício Circular) e `parametro_tenant(chave)` (lê config por tenant) — estes dois na fronteira builtin↔núcleo.
- **Funções de relação (dec. 3, expostas pelo contexto dono):** `populacao(ente)`, `membros_da_casa(ente)` (Cadastros/Ente); `remessa_enviada(ente,sistema,competencia)` (Remessa-tracking); `publicada_no_portal(despesa)`, `data_registro_contabil(despesa)` (Transparência/Execução); `publicado(ato)`, `data_promulgacao(ato)` (Atos Legislativos); `votos_favoraveis(votacao)` (Plenário, §22.6).
- **Núcleo/tipos (dec. 4/5) — extensões, não contradições da forma A2:** tipo `Competencia` (período); tipo composto `Maioria/Limiar` = `(fração, base ∈ {presentes, membros})` — o **denominador** é a armadilha; tipos temporais `Data`/`Instante`/`Duracao`; registros (acesso a campo `ato.tipo`); conjunto/enum com o **operador `in`** (pertinência) — novo.

**Achados estruturais (refinam o envelope de compliance — dec. 7):**

- **S1 — o motor de compliance _monitora prazo_, não só avalia booleano.** A semântica que define o envelope é **obrigação temporal**: estado asserido que precisa valer **até um prazo** (ou **continuamente**, quando o bloco `prazo` é ausente). Distinta de _guard_ (tramitação) e _permissão_ (autorização). É **comportamento de motor** → elevado no roadmap dos "+5 eixos" (§22.7.4).
- **S2 — `dominio` é taxonomia em CAMADAS, não "qual TCE".** Apareceram três regimes numa só UF: `federal` (LC 131, LAI), `tce_estadual` (INs do TCE-CE), `regimento_tenant` (prazo de publicação que varia por casa, parametrizado por tenant). O Invariante 4 segue válido; o modelo de domínio é mais rico que "27 tribunais".
- **S3 — `prazo` é expressão MULTI-FONTE:** resolve por calendário de domínio com override por circular, por evento + dia-útil, ou por parâmetro de tenant + dia-útil. Generaliza o `prazo_dominio_ativo` (§22.4.3 disc. 6) e exige o calendário de feriados como dado.
- **S4 — quórum/votação/tribuna usam o envelope de _guard_ (tramitação/plenário), não o de compliance.** Resolve a pendência de §22.7.4. Núcleo compartilhado; envelopes distintos por semântica.

**Reclassificações (não são regra de compliance):** **numeração de atos** é invariante de integridade de dados (`unicidade(tipo,numero,ano,camara_id)`, `numero` como string) → vai para o schema (Eixo B / §22.4), não para o motor de regras; **índices ITM/PNTP-Selo** são medição/score, não regra dura bloqueante.

**Disciplina de severidade confirmada:** `severidade: bloqueante` tem referente real (bloqueio de transferências voluntárias via LRF art. 73-C; multa pessoal ao Presidente) — é o que justifica o type-check no save time (§22.7.2 dec. 2). `aviso` cobre obrigação sem essa consequência (ex.: publicação de ato, que o TCE-CE não fiscaliza diretamente).

> **Escopo honesto do stress-test:** 1 estado (TCE-CE), 4 templates — conjunto pequeno e diverso de propósito, não exaustivo. O veredito "forma validada" está calibrado a isso. `[GAP]`s sourced (texto exato do prazo SIM da IN 04/2019; prazo definitivo da PCS sob IN 01/2025; reconhecimento da APRECE como veículo oficial) **não bloqueiam a validação de forma** — importam ao popular conteúdo no Eixo B e pedem confirmação do especialista em regimento.

#### 22.7.6 Eixo B — schema das tabelas de template/regra

O Eixo B materializa em schema o vocabulário que o Eixo C validou (§22.7.5) — a virada
design→implementação. Rascunho de origem com a DDL completa e as decisões: `docs/06-eixo-B-schema-rascunho.md`.

**Fronteira de escopo.** O Eixo B é o schema **estático** do motor: definição da regra, binding por
tenant, registry/tipos e calendários. **Não** inclui as tabelas de *runtime* (instâncias de
obrigação, resultados de avaliação, monitoramento de vencimento) — o achado S1 (o motor _monitora
prazo_) roteou esse comportamento para um dos "+5 eixos". A ponte explícita: cada definição carrega
o que o runtime vai ler (forma compilada, assinatura, severidade, prazo).

**Decisão estrutural central — definição (domínio) vs. binding (tenant).** O achado S2 (`dominio` em
camadas) força **separar a definição da regra** — dado de domínio, central, **sem `ente_id`** — do
**binding por tenant** — config, **com `ente_id`**. É uma divergência **consciente** do padrão de
§22.4 eixo C ("override por câmara via cópia integral"): regra `federal`/`tce_estadual` é **lei
uniforme mantida central**, não customização por câmara; copiá-la por ~1.500 entes seria
insustentável de manutenção. O versionamento por cópia integral é honrado na *definição*; o
*binding* só carrega o que varia por casa (parâmetro, on/off, pin de versão).

**As tabelas (DDL completa em `docs/06`):**
- **`template_compliance`** (domínio, sem `ente_id`): a definição versionada por cópia integral.
  `dominio`+`chave_dominio` (S2); envelope (`severidade`, `referencia_normativa`); a expressão em
  **dois formatos** — `fonte_yaml` (auditável, "regra é dado") + `forma_compilada` jsonb (AST tipado
  que o motor avalia) + `assinatura_parametros`. `registry_versao_ref` carimba contra qual versão do
  catálogo a regra passou no type-check do save time (dec. 2). Imutabilidade = conteúdo append-only
  + ponteiro de vigência (`estado_versao`), como §22.4.3 disc. 3/4. `UNIQUE(chave_template, versao)`.
- **`compliance_regra_tenant`** (tenant, `ente_id`): binding. Resolução **por escopo**, não
  linha-por-tenant — `federal`/`tce_estadual` aplicam por default à jurisdição (resolução
  ente→jurisdição via UF); o binding só materializa `parametros_tenant`, opt-out auditado
  (`ativa=false`+motivo) ou pin de versão. `regimento_tenant` **exige** binding para ativar (3ª
  camada de S2). `UNIQUE(ente_id, template_chave)`.
- **`prazo_dominio_vigente`** (domínio): prazo regulatório por jurisdição/tipo/período com
  **override por Ofício Circular** (S3, "prazo deslizante") — append-only + flag `vigente`. Lido
  pelo builtin `prazo_vigente`. **Distinto** do `prazo_dominio_ativo` de *runtime* (obrigação
  concreta, no eixo de comportamento do motor): `_vigente` = referência regulatória; `_ativo` =
  obrigação. Nomes distintos de propósito.
- **`calendario_feriado`** (domínio): feriados nacional + municipal (FK `municipios`), lido por
  `proximo_dia_util`/`soma_dias_uteis`. Móveis entram como datas já resolvidas por ano na carga.

**Registry como catálogo de infra, não tabela de tenant.** O registry de funções de relação + o
catálogo de tipos (Eixo A dec. 3/4) **não são dados de tenant**: são **infra compartilhada pelos
quatro usos da DSL**, residente no módulo core/compartilhado. As assinaturas são declaradas **em
código no bounded context dono** (co-locação evita drift), publicadas num **catálogo versionado**
que o type-checker lê; builtins idem (biblioteca da DSL). Evolução de assinatura = bump de versão do
catálogo + **passe de re-validação** que re-roda o type-check das regras vigentes e sinaliza quebras
no deploy — estende a garantia do save-time type-check à evolução do registry. **Materializa a
"mecânica fina do registry" de §22.7.4** (visibilidade entre contextos + versionamento de
assinatura) **sem reabrir a forma A2**. Única tabela aqui: `registry_catalogo_versao` (log de versão
para o `registry_versao_ref` referenciar e dirigir a re-validação).

**Reclassificações confirmadas no schema (§22.7.5):** numeração de ato é invariante de integridade
(`UNIQUE(tipo,numero,ano,camara_id)`, `numero` como string) no schema legislativo (§22.4), não regra
do motor; índices ITM/PNTP ficam fora.

**Disciplina derivada (nova).** **Domínio vs. tenant é decisão explícita por tabela do motor.**
Regra/calendário/catálogo que valem para um regime regulatório são tabelas de **domínio** (sem
`ente_id`, índice por `dominio`/jurisdição, como `municipios`); só o que varia por casa carrega
`ente_id`. Diverge **conscientemente** do default §22.2 ("`ente_id` em toda tabela") onde a tabela é
genuinamente de domínio — e é a materialização de S2 no schema.

**Pontos a confirmar e deferidos.** Persistência concreta do catálogo (tabela gerada vs. estrutura
carregada no boot) e geração de id — chat de stack (§22.4.4). Conteúdo dos calendários (feriados;
valores exatos de prazo das INs) é `[GAP]` a popular com o especialista em regimento — a **forma**
não depende do valor. Precedência literal de operadores e formas descartadas do Eixo A seguem a
reconciliar (§22.7.4). Avaliador executável da DSL (parser+type-checker rodando os 4 templates do
Eixo C contra o catálogo) é a primeira implementação de fato, logo após a consolidação.

#### 22.7.7 Eixo de runtime do motor — comportamento temporal + auditoria

Primeiro dos "+5 eixos", **elevado por S1** (§22.7.5: o motor _monitora prazo_, não só avalia
booleano). Aqui o motor deixa de ser **forma** (schema estático, §22.7.6) e passa a ser
**comportamento**: como uma regra vira **obrigação concreta**, é **avaliada**, tem **prazo
monitorado** e gera **prova auditável**. Rascunho de origem: `docs/07-eixo-runtime-motor-rascunho.md`.

**Fronteira de escopo.** Dentro: materialização de obrigações, modelo de avaliação, monitoramento de
prazo (S1) e auditoria de avaliação. Fora (roteado): geração do **arquivo** de remessa ao TCE (outro
+5 eixo — aqui rastreia-se a *obrigação de enviar*, não se gera o arquivo); versionamento de regra
(fechado no Eixo B, cópia integral); expansão a outros TCEs (Invariante 4, conteúdo). Este eixo
**funde dois** dos "+5 candidatos" — *comportamento temporal* (S1) + *auditoria/rastreabilidade de
avaliação* — porque o relógio e a prova são o mesmo loop de execução.

**Nó central — a obrigação temporal (S1) parte em dois sabores.** (a) **Com prazo** (deadline-bound):
"enviar a remessa do SIM da competência 2026-05 até D" — tem `vence_em`, **materializa** uma instância
com relógio aberto. (b) **Contínua** (standing): "manter transparência em tempo real" — sem prazo
(bloco `prazo` ausente), **não materializa** obrigação; é asserção reativa cuja violação vira só
registro de avaliação. **Decisão:** `prazo_dominio_ativo` é a tabela das obrigações **com prazo**;
contínuas produzem só avaliação. Fecha o **trio de tenancy** do motor: definição = domínio (sem
`ente_id`), binding = tenant, **obrigação = tenant** (`ente_id` — é sempre de *um* ente).

**Generalização disparada (disc. 6).** O 2º caso de "prazo de domínio" chegou (envio TCE), então a
disciplina 6 manda **generalizar `proposicao_prazo_ativo` → `prazo_dominio_ativo` polimórfico** agora
(não é abstração prematura — é a regra que §22.4.3 deixou agendada). Sujeito polimórfico
`(objeto_tipo, objeto_id)` (disc. 2): competência, ato, proposição, sessão.

**As tabelas novas (DDL completa em `docs/07`):**
- **`prazo_dominio_ativo`** (tenant, `ente_id`): a obrigação materializada com relógio. Aponta a
  **versão exata** da regra (`template_compliance_id`); idempotência por
  `UNIQUE(ente_id, template_chave, objeto_tipo, objeto_id)`; ciclo de vida **enum fixo em código**
  (`pendente → cumprida | vencida | dispensada | cancelada`) — universal entre câmaras/regimes, logo
  **não é template** (mesmo princípio de emendas, §22.4 eixo D); a DSL governa a *asserção* e o
  *prazo*, não o ciclo. `acao_no_vencimento` é identificador que o motor mapeia (V1: emitir evento).
- **`compliance_avaliacao`** (tenant, `ente_id`): **append-only** (imutabilidade nível a, §22.4.3
  disc. 4) — a **prova de compliance** (Invariante 10 + confiança operacional). Carimba **regra +
  catálogo** (`template_compliance_id` + `registry_versao_ref`): fecha o ciclo do save-time type-check
  (Eixo B carimba na definição) com o runtime (carimba na avaliação). A obrigação guarda só o estado
  corrente (cache via `ultima_avaliacao_id`); a trilha vive aqui — mesma divisão de
  `texto_vigente_versao_id` → `proposicao_texto_versao` (§22.4 eixo B).

**Modelo de avaliação (lógico decidido, infra deferida — mesma disciplina de §22.4 eixo C).** Três
gatilhos, um motor: **evento** (primário — `AtoAssinado`, `CompetenciaFechada` etc. via o bus que já
existe, Invariante 2/§22.3, materializa e/ou reavalia), **sweep agendado** (único gatilho temporal —
avança vencimento e materializa recorrências de calendário), **sob demanda** (UI; útil p/ regras
contínuas). O avaliador é o **mesmo** dos 4 usos da DSL (disc. 5) — compliance não ganha avaliador
próprio. Infra (worker/fila/cron) → chat de stack.

**Monitoramento de prazo (coração do S1).** "A vencer"/"vence em breve" são **derivações de leitura**
sobre `vence_em` (relógio injetado, builtin do Eixo C), **não** estados persistidos — a máquina fica
pequena. **Re-stamp no deslize de circular (S3 ↔ Eixo B):** quando `prazo_dominio_vigente` ganha nova
linha `vigente` (Ofício Circular deslizou o prazo), as obrigações **abertas** daquele
`(dominio, tipo, periodo)` têm `vence_em` re-carimbado (com novo `prazo_fonte_ref`, auditado);
obrigações já `cumprida`/`vencida` não se mexem (fato consumado). `acao_no_vencimento` na V1 = **emitir
evento** sempre; escalonamento/notificação são **consumidores**, não lógica na obrigação (reusa o bus,
§22.3). Eventos do runtime: `ObrigacaoComplianceMaterializada/Avaliada/Cumprida/Vencida/Dispensada`.

**Pontos a confirmar e deferidos.** Política concreta de escalonamento por tipo de requisito (quem é
notificado, quando) é `[GAP]` de produto/UX + especialista; valores de prazo das INs já eram `[GAP]`
do Eixo B — a **forma** não depende deles. Infra de processamento do sweep/consumo de eventos e
geração de id → chat de stack (§22.4.4). Próximo candidato de igual valor: o **avaliador executável da
DSL** (primeira implementação de fato), agora com o loop de runtime desenhado para validar
end-to-end (materializa → avalia → monitora → audita).

### 22.8 Itens parqueados da North Star

O único item que aqui restava — **provedores/modelos de LLM e implicações de soberania** (Invariante 6: gerenciado vs. self-hosted, modelo por feature, custo, LGPD) — foi **resolvido no Eixo 10 (§22.9, v1.27):** solução **híbrida** (self-host de ASR/embeddings + `pgvector`; frontier LLM via API gerenciada **vendor-agnóstica** atrás da porta de inferência), honrando o zero-vendor no grosso e a soberania pela porta + preferência in-region. **Não há mais itens parqueados da North Star.**

---

### 22.9 Stack técnico e infraestrutura da V1

Fase do chat de stack (continuação de §22.4.4, após fechar a North Star). Decidido eixo a eixo.

**Eixo 1 — Local de deployment e soberania (fechado, v1.16).** A aplicação é **deployment central único** (SaaS multi-tenant, §22.2 — *não* instalação por município; a leitura "on-prem em cada câmara" foi explicitamente descartada por quebrar §22.2, o compliance central de §22.7 e as apostas de IA). Três decisões:

1. **Provider-neutral / open-source-first como disciplina permanente.** k8s, PostgreSQL, Redis, object storage S3-compatível (**MinIO** como implementação de referência open-source — o código fala API S3; rodar MinIO self-hosted no cluster ou apontar para o endpoint S3 da cloud-BR é detalhe de *deploy*, Eixo 5, e a compatibilidade de API torna a migração cloud→colo zero-refactor), fila — tudo portável, **zero serviço proprietário de hyperscaler amarrado**. Isso torna "on-prem vs. cloud" uma escolha de *deploy*, trocável, não de arquitetura; materializa o Invariante 6 sem lock-in e mantém on-prem como opção de primeira classe para sempre. Nada no stack (Clojure + ecossistema aberto) exige managed service proprietário.
2. **V1 deployada em cloud de região brasileira** (AWS sa-east-1 como referência; Azure Brazil South / GCP São Paulo equivalentes em soberania — provedor concreto é detalhe de deploy, não de arquitetura, dado o provider-neutral). Razão: velocidade de V1 (sem lead time de hardware/GPU no §18 já apertado), **DR multi-AZ de fábrica** (single-site on-prem é ponto único de falha, inaceitável sob o SLA de janela de sessão §16.10), elasticidade para a carga em rajada de sessão, GPU alugada > comprada no início. **Fundamentado por pesquisa sourced:** região-BR de hyperscaler é **procurement-safe** para câmara municipal — a "nuvem soberana" obrigatória é federal (Decreto 12.572/2025, escopo SISP ~250 órgãos, não alcança município); a LGPD regula transferência internacional, não localização física; editais municipais pedem dado-no-Brasil + ISO 27001/27017/27018/27701, coberto por sa-east-1; o próprio governo federal roda em hyperscaler sob a "Nuvem de Governo".
3. **Caminho comprometido para iron própria / colocation** (datacenter Tier-III BR, não escritório) quando **escala + capital + eventual edital soberanista** justificarem — anos 1-3, mesmo padrão de migração planejada do §22.2 (shared DB → pool-per-UF). Soberania total é **meta arquitetural**, capturada sem pagar capex + ops + V1 lenta + DR de segundo site na corrida ao primeiro cliente.

**Implicação de IA (encaminhada aqui, fechada no Eixo 10):** a V1 mantém uma **porta de inferência abstraída**, com dado de plenário in-region e fora de treino de modelo. O conteúdo concreto — **híbrido** (self-host de ASR/embeddings; frontier LLM via API gerenciada **vendor-agnóstica** atrás da porta) — está cravado no **Eixo 10**, que **supera a leitura inicial "Bedrock in-region"**: pelo zero-vendor do Eixo 5, o grosso vai pra self-host e o Bedrock passa a ser apenas *um* vendor possível atrás da porta.

**Eixo 2 — Persistência (fechado, v1.18).** **PostgreSQL vanilla** é o banco transacional do core e do motor (decisão Emilio, 20/06). Coerente com o provider-neutral do Eixo 1: **self-managed na V1 via operador `CloudNativePG`** (no cluster k8s do Eixo 5 — **sem solução de vendor de cloud**; Postgres vanilla, engine real, jamais reimplementação proprietária tipo Aurora), portável para colo sem refactor. Confirma o default herdado de §22.2 (relacional, `ente_id`, RLS) e crava a mecânica fina:

1. **Numeração canônica gapless por linha-contador, não SEQUENCE nativa.** Numeração legal/regimental (protocolo de proposição, ato, ofício) **não pode ter buraco** — e a SEQUENCE nativa do Postgres *avança fora da transação* (rollback deixa lacuna permanente). A numeração canônica `(ente_id, tipo, ano, sequencial)` é gerada por **linha-contador** com `SELECT … FOR UPDATE` (ou `INSERT … ON CONFLICT … DO UPDATE … RETURNING`) **dentro da transação do ato** — gapless por construção, serializada por ente+tipo+ano. UUID interno segue como PK técnica (§22.4); o sequencial é o número *público* auditável. Reconcilia a menção casual a "SEQUENCE" do §22.2, que era taquigrafia para "numeração" e **não** se aplica aos números que exigem gapless.
2. **Idempotência do bus de eventos por chave + UNIQUE (compare-and-swap).** Todo evento de integração/domínio carrega `idempotency_key`; a aplicação no consumidor é protegida por `UNIQUE(consumidor, idempotency_key)` — reprocessamento (retry de fila, replay) colide no índice e vira no-op em vez de duplicar efeito. Materializa o "fila persistente desde o dia 1" (§22.3) com semântica **at-least-once na entrega + dedup no destino** (exactly-once efetivo), sem depender de garantia exótica da fila.
3. **Referência polimórfica `(objeto_tipo, objeto_id)` com integridade em camadas.** O padrão polimórfico já fechado (§22.4; `prazo_dominio_ativo` §22.7.7) não admite FK nativa — integridade vai por **guard na camada de serviço** (toda escrita valida o par contra o tipo-alvo) + **índice composto começando por `objeto_tipo`** (toda query polimórfica filtra o tipo primeiro) + **CHECK XOR só nas relações quentes/críticas** (colunas FK tipadas dedicadas com XOR onde a integridade referencial nativa paga o custo; nas frias, o guard de serviço basta). **Não** uniformizar XOR em tudo é decisão consciente — seria over-engineering.
4. **Particionamento declarativo nativo por `hash(ente_id)`.** Isolamento + escala multi-tenant (§22.2) via partição nativa por hash do `ente_id`; alinha com o caminho "shared DB → pool-per-UF" do Eixo 1 — a partição por hash hoje vira pool físico por UF amanhã sem reescrever o modelo. RLS keyed em `ente_id` (variável de sessão) permanece como **defesa em profundidade** (§22.5.3) *por cima* da partição, não em vez dela.

**`pgvector`** (embeddings) é open-source e provider-neutral-safe — registrado como **viável sem violar o Eixo 1**, mas **deferido ao Eixo 10** (LLM/IA infra): embeddings vivem na Plataforma de IA (§22.3.4, §22.8 eixo E), e pgvector-no-Postgres vs. store vetorial dedicado é decisão daquele eixo.

**Eixo 3 — Fila, worker e scheduling (fechado, v1.19).** **Fila durável + outbox transacional no próprio Postgres** (`SELECT … FOR UPDATE SKIP LOCKED`), **não** broker dedicado na V1 (decisão Emilio, 20/06). Materializa o "fila persistente desde o dia 1" (§22.3) sobre o banco do Eixo 2, escolhendo menos peça móvel (Eixo 1) e a escala real (~1.500 entes, carga em rajada de sessão, não firehose). Quatro decisões:

1. **Outbox transacional mata o dual-write.** O job/evento é enfileirado **na mesma transação** da escrita de domínio — commit atômico de estado + intenção de publicar. Um *relay* drena o outbox para o bus core↔IA (§22.3) e para consumidores in-process; a **idempotência `UNIQUE`+CAS do Eixo 2** torna redelivery um no-op. Elimina a janela de inconsistência do "grava no banco, depois publica no broker" sem garantia distribuída exótica.
2. **Fila sobre `SKIP LOCKED`.** Postgres como fila de trabalho real nessa escala — concorrência por `FOR UPDATE SKIP LOCKED`, retries com backoff e dead-letter modelados como linhas. Gargalo só apareceria em throughput de firehose, fora do nosso perfil.
3. **Worker em pools lógicos por classe de carga.** Processos Clojure puxando da fila, com **pools separados** — jobs rápidos de core (revogação de token, notificação) isolados dos jobs pesados e longos (transcrição/diarização/sumarização da ata-IA, §22.3.4). Um áudio de 40 min não pode esfomear uma operação interativa. Mesmo artefato de deploy, config de pool distinta (concretização → Eixo 5).
4. **Scheduling com leader-election, fonte de cron única.** Um agendador eleito por **advisory lock no Postgres** dispara o sweep de compliance (§22.7.7 — o único gatilho temporal do motor), roll-ups de `LoginFalhou` (§22.5) e monitoramento de prazo — **um cron, não cron-por-nó** (evita disparo duplicado em deploy multi-réplica).

**Porta abstraída + risco endereçado:** a fila fica **atrás de uma porta** (como a porta de inferência do Eixo 1) — trocar por NATS/RabbitMQ/Kafka vira detalhe de deploy se o throughput um dia exigir, sem refactor de chamada. O risco de contenção fila↔OLTP no mesmo Postgres é mitigado pela porta + pelo caminho shared-DB→pool-per-UF (§22.2) que distribui carga.

**Eixo 4 — Cache efêmero (fechado, v1.20).** **Valkey** (store in-memory protocolo-Redis, fork **BSD sob a Linux Foundation** do Redis 7.2) como camada efêmera **escopada a três usos** — não cache-tudo (decisão Emilio, 20/06). Resolve o ponto deferido de §22.5.4 (revogação imediata de token) e o backplane de tempo real previsto em §22.6. Quatro decisões:

1. **Três usos, todos comando-núcleo (zero módulo proprietário).** (a) **Denylist de revogação imediata de token** — TTL curto, checada por request; resolve §22.5.4 a favor de "lista in-memory" e **contra** "só TTL do JWT", porque eventos críticos (`MandatoCassado`, `ServidorDesligado`) não toleram a janela de até 15 min do access token. (b) **Rate-limit / anti-brute-force** (login, API) — contadores atômicos (`INCR`). (c) **Backplane do SSE** (§22.6) — `PUBLISH/SUBSCRIBE` para fan-out de evento de sessão ao vivo entre réplicas. Os três ficam **fora do OLTP do Eixo 2** por padrão de acesso (sub-ms, quente, por request).
2. **Valkey, não "Redis-o-produto".** Pela disciplina open-source-first do Eixo 1: BSD + governança Linux Foundation (AWS/Google/Oracle) supera o Redis relicenciado (SSPL; AGPLv3 no Redis 8) para uma disciplina *permanente* — nenhum vendor único relicencia de novo. Mesmo protocolo → **self-managed in-cluster** (Eixo 5; sem solução de vendor de cloud), portável a qualquer destino, **atrás de porta**. Os três usos não tocam os módulos proprietários do Redis Stack (RediSearch/JSON/Bloom), então a lacuna do fork não morde.
3. **Guardrail "nada durável".** O que mora no Valkey **degrada gracioso se ele sumir** — denylist reconstrói de evento, rate-limit zera por janela, SSE reconecta; flush / cold-start **nunca** perde dado. É a linha que impede o cache de virar segundo banco informal. **Não** vai pra cá: fila (outbox Postgres, Eixo 3), lock de scheduler (advisory lock Postgres, Eixo 3), estado durável (Postgres, Eixo 2).
4. **Backplane SSE no Valkey, não `LISTEN/NOTIFY`.** O `LISTEN/NOTIFY` do Postgres seria zero-peça-nova, mas quebra sob connection pooler em modo transação (PgBouncer), limita payload (8KB) e não persiste — registrado como a alternativa "zero sistema extra" e descartado porque o Valkey já entra pelos usos (a)/(b) e o backplane nele tem custo marginal zero.

**Eixo 5 — Compute / deploy do monólito + IA (fechado, v1.21).** **Kubernetes self-managed, zero solução de vendor de cloud** (decisão Emilio, 20/06 — leitura *estrita* do provider-neutral do Eixo 1: a cloud-BR é só **IaaS alugado** (VM, rede, disco); tudo por cima é open-source self-managed, tornando cloud→colo um **lift-and-shift sem refactor**, não uma migração de RDS→Postgres-próprio depois). Cinco decisões:

1. **Distro: Talos ou k3s em prod; `kind` no dev local.** Família lean self-managed — **Talos** (OS imutável, API-driven, superfície de ataque mínima — ideal pra colo e pra postura gov/soberania) ou **k3s** (single-binary, leve); ambos open-source, vendor-neutral, **mesma distro de V1-cloud ao colo**. **`kind`** (Kubernetes-in-Docker) padroniza o ambiente de dev local contra o mesmo k8s. Descartados: managed (EKS/GKE/AKS, são solução de vendor) e distro pesada (RKE2, excesso de peça/ops).
2. **Quatro workloads num cluster (V1), por namespace:** (a) monólito core — N réplicas atrás de **ingress open-source** (Traefik / nginx-ingress, não LB-as-a-service de vendor), HA; (b) satélite de IA — escala/isolamento próprios (§22.3.4); (c) worker-pools por classe (Eixo 3); (d) scheduler singleton leader-elected (Eixo 3).
3. **Stateful self-managed in/near-cluster.** **Postgres via operador `CloudNativePG`** (failover automático, PITR + backup pro MinIO, réplicas, upgrade pelo operador — é o que torna o Postgres self-managed do Eixo 2 operacionalmente viável); **Valkey** self-managed (efêmero, fácil pelo guardrail do Eixo 4); **MinIO** self-hosted (object storage, já referência do Eixo 1). **Reconcilia Eixos 2 e 4:** o hosting "managed RDS / ElastiCache" da V1 **cai** → vira self-managed; as decisões (Postgres vanilla + 4 pontos finos; Valkey escopado a 3 usos) ficam **intactas** — muda só o hosting, que o Eixo 1 já dizia ser deploy trocável.
4. **Deploy seguro sob o SLA de janela de sessão (§16.10) — confiança operacional como config de infra:** graceful drain de SSE (`preStop` + `terminationGracePeriod` longo) + rolling/blue-green + **gate de janela de deploy que evita sessão ao vivo** (o scheduler do Eixo 3 conhece os horários). Não derrubar vereador no meio de votação é requisito comercial, não nicety.
5. **GPU fora da fundação.** Cluster V1 pode ser **CPU-only** — LLM via porta de inferência (Eixo 1), ASR/transcrição atrás de porta; **node pool de GPU (VM alugada) é adição, não base**; sizing concreto vai pro Eixo 10.

**Custo assumido (entra no dimensionamento de time, §18):** ops de stateful self-managed sob SLA exige trabalho de SRE (drill de failover, teste de PITR, upgrade de major) — `CloudNativePG` mitiga, mas o time é dono do Postgres. Trade consciente: mais ops agora por zero lock-in + soberania + colo sem refactor.

**Eixo 6 — IdP / autenticação (fechado, v1.22).** **Keycloak self-managed** como provedor de identidade (decisão Emilio, 20/06) — resolve o "provedor de IdP concreto" deferido em §22.5.4. Cognito/Auth0 caem pela regra "zero solução de vendor de cloud" (Eixo 5); "próprio" descartado (auth é o pior lugar pra rolar à mão, e o modelo de §22.5 é rico demais). Três decisões:

1. **Keycloak faz a authN; o app faz a authZ.** Keycloak cobre nativo o que §22.5 exige: **federação OIDC** (gov.br entra como identity provider externo pro cidadão), **WebAuthn/passkey** (fator primário, v1.17), **TOTP**, **step-up** e enrollment de MFA. A **autorização** continua **in-app** (§22.5.3 — middleware grosso + `policy.check` fino, **sem PDP externo**): Keycloak emite o token, o domínio decide o acesso. Subsume a "biblioteca WebAuthn server-side" que §22.5.4 listava.
2. **Topologia de instância/realm casa com §22.5.** **Instância Keycloak fisicamente separada pro admin interno** (§22.5 — "IdP fisicamente separado dos clientes desde a V1"; hardware key obrigatória mora lá). Usuários-fim (cidadão/servidor/vereador) na instância principal, com **escopo de tenant no app via `ente_id`** — **não realm-por-ente** (1.500 realms seria insustentável, mesmo raciocínio do §22.7.6).
3. **Integra com o que já está fechado.** Revogação imediata de token usa a **denylist no Valkey** (Eixo 4); Keycloak **persiste em Postgres** (mesmo substrato `CloudNativePG` do Eixo 5, DB lógico próprio). **ICP-Brasil permanece fora da auth** — assinatura app-level (§22.5), não toca o Keycloak.

**Custo assumido:** Keycloak é serviço Java com store próprio e alguma impedância com o monólito Clojure — mais uma peça stateful (trade aceito por **não ser dono do código de auth** no lugar mais sensível).

**Eixo 7 — Libs web Clojure (fechado, v1.23).** Stack idiomática Clojure **data-driven**, com **Pedestal** na camada HTTP (decisão Emilio, 20/06 — escolhido sobre Ring/Reitit pelo **SSE async first-class** + **interceptors-as-data** + **pedigree Nubank**, com o trade de hiring-nicho + curva de interceptors aceito). Três decisões:

1. **HTTP/interceptors: Pedestal** (sobre Jetty). Cadeia de **interceptors como dado** (vetor de mapas — inspecionável/manipulável em runtime, mais aderente ao ethos rule-as-data que o middleware-função do Ring). **SSE/streaming first-class** — encaixe direto no real-time de §22.6 (o motivo decisivo; o argumento pró-Ring via Loom/virtual-threads foi pesado e perdeu pro async nativo). Roteamento pela route-table de dado do Pedestal; **Malli** como interceptor de coerção/validação; virtual threads (Java 21) disponíveis pro trabalho bloqueante em interceptor.
2. **Resto da stack (compõe com Pedestal):** **Malli** (schema/validação data-driven — espelha o type-system do motor §22.7, e gera os tipos TS do Eixo 8); **banco em três camadas não-concorrentes** — **HoneySQL** constrói SQL como dado → **next.jdbc** executa e devolve mapas → **HikariCP** é o pool de conexões (Postgres do Eixo 2); + **Migratus** (migrations) e **Jsonista** (JSON). HugSQL descartado (HoneySQL cobre 100% incl. `FOR UPDATE`/`ON CONFLICT…RETURNING`/CTE; raw next.jdbc é o escape hatch; HugSQL só valeria com DBA dono de `.sql`). **Gestão de componentes / DI: Component** (Stuart Sierra) — decisão Emilio *sobre* Integrant (grafo explícito em código, records+protocols; aceita perder o "system-as-data" pelo modelo clássico). Só recurso **stateful** é componente (datasource Hikari, cliente Valkey, cliente Keycloak, service Pedestal, worker-pools + scheduler); next.jdbc/HoneySQL/Malli são libs puras.
3. **Trade assumido:** Pedestal é nicho mais raro que Ring (hiring no pool fino do NE) e tem curva de interceptors — aceito pelo ganho de SSE-async + alinhamento de dado + coerência com a tese de contratação Clojure (stack-Nubank).

**Eixo 8 — Frontend web (fechado, v1.24).** **TypeScript + React (Next.js), self-hosted** (decisão Emilio, 20/06). É o **único eixo que rompe a coerência Clojure de propósito** — e a razão é **risco de talento**: o frontend é onde se alarga o pool (React é abundante, inclusive no NE), então o orçamento de nicho fica no backend (onde o motor justifica) e o frontend vai mainstream. Três decisões:

1. **Next.js self-hosted, não Vercel.** Coerente com "zero solução de vendor de cloud" (Eixo 5): Next roda **self-hosted no cluster k8s** (Node server, ou static export onde couber), nunca Vercel. O SSR/SSG do Next resolve **SEO + a11y do portal cidadão** (transparência é a cara pública; discoverability é requisito, não enfeite).
2. **Três superfícies, um framework:** portal cidadão (SSR/SSG p/ SEO), app interno servidor/vereador (app-like) e painel de sessão ao vivo — este consome o **SSE do Pedestal via `EventSource`** (Eixo 7, real-time §22.6). Design system (Lexend, 7 superfícies) implementado em componentes React.
3. **Contrato tipado front↔back via `Malli→TS`.** Os schemas Malli do backend (Eixo 7) **geram os tipos TypeScript** — recupera a maior parte do ganho de schema-compartilhado sem CLJS; o contrato de API fica tipado nas duas pontas.

**Trade assumido:** segunda língua/runtime + context-switch — aceito por largura de contratação (de-risca o risco #1), SEO maduro e ecossistema pra entregar a aposta de UX moderna. **CLJS descartado** (dobraria a aposta de nicho onde não precisa).

**Eixo 9 — Mobile (fechado, v1.26).** **PWA-first na V1** (decisão Emilio, 20/06): o frontend React/Next do Eixo 8 é **responsivo + instalável (PWA)**, com Web Push (iOS 16.4+ / Android) — **sem app nativo separado na V1**. Cobre as três superfícies no mobile via web. Duas decisões:

1. **V1 = PWA.** Servidor (desktop-primário), cidadão (transparência/participação) e vereador acessam mobile pela PWA do Next; push de alerta de sessão/votação via Web Push. **Zero custo de codebase nativo** no §18 apertado — alinhado à disciplina "escopo diferido por default" (§15): não pré-construir app nativo sem requisito validado.
2. **App nativo deferido; quando vier, React Native + Expo.** O vereador é mobile-primário (§16.7) e a assinatura-2-toques + experiência polida podem justificar nativo **pós-V1**; a direção pré-alinhada é **RN + Expo** (reusa o skillset React do Eixo 8, compartilha tipos Malli→TS) — **registrada pra não relitigar, não pré-construída**. Flutter/native descartados pelo mesmo motivo do Eixo 8 (3ª língua / 2× trabalho).

**Eixo 10 — LLM / IA infra (fechado, v1.27). Último eixo do chat de stack.** Solução **híbrida** (decisão Emilio, 20/06), resolvendo a tensão entre o "zero solução de vendor de cloud" (Eixo 5) e a qualidade de fronteira necessária no copiloto (Aposta 1). Separa as cargas de IA em dois baldes:

1. **Self-host (in-house, honra o zero-vendor) — o grosso e o sensível.** **Transcrição (ASR):** Whisper-class self-hosted em **GPU própria** (alugada na cloud-BR, colo depois — **resolve o node-pool de GPU deferido no Eixo 5**); áudio de plenário nunca sai. **Embeddings:** modelo open-source self-hosted + **`pgvector`** no Postgres/CloudNativePG — **resolve o pgvector deferido dos Eixos 2/4**.
2. **Frontier LLM — a única peça externa, atrás da porta de inferência, vendor-agnóstica.** Só o **copiloto legislativo + rascunho da ata-IA** chamam um LLM de fronteira via **API gerenciada** (§22.3.4). Decisão central: **provider-neutral atrás da porta** — **qualquer vendor de LLM** (Claude, Maritaca/Sabiá, etc.) é implementação trocável por config, **não compromisso arquitetural**; a porta torna trocar de provedor — ou virar self-host quando open-weights fecharem o gap + GPU de colo — um detalhe de deploy. Preferência por in-region / BR-soberano onde der, sem cravar marca.
3. **Governança de dado:** o input do copiloto é majoritariamente **texto legislativo público**; dado pessoal / sessão fechada é governado/filtrado antes de cruzar a porta.

**Reconciliação e fechamento:** **supera o placeholder "Bedrock" do Eixo 1** (Bedrock vira só *um* vendor possível atrás da porta) e **resolve §22.8 item 1** (LLM/soberania, parqueado). O **espírito** do zero-vendor fica preservado (porta = sem lock-in, grosso self-hosted, in-region preferido); a **letra** ganha **uma** exceção estreita, consciente e swappable, no único ponto onde custaria o wedge.

**Chat de stack concluído: 10/10. A §22.9 fica completa.**

---

## 23. Como este documento deve ser usado

Este é o documento-mestre do projeto. Ele deve:

- Ser colado (ou anexado) como contexto inicial ao iniciar chats novos sobre este projeto.
- Ser atualizado a cada decisão relevante tomada em qualquer chat (responsabilidade de quem tomou a decisão).
- Servir como *single source of truth* de decisões consolidadas — em caso de conflito entre um chat antigo e este documento, o documento prevalece.

**Sugestão de uso em chats futuros:** criar um chat por fase estratégica (North Star + Stack, pesquisa de mercado, roadmap administrativo, mapa competitivo, captação seed, contratação-chave). No início de cada chat, colar este documento e declarar explicitamente a fase em que estamos entrando e o que se quer decidir.

**Próximo chat recomendado:** *"North Star Architecture + Stack V1"*. Prompt de abertura sugerido:
> *"V1 de escopo definida (ver documento-mestre anexo, seção 16). Preciso agora da arquitetura-alvo de 5 anos compatível com a Rota D e das decisões de stack que habilitam a V1 em 4 meses. Vamos começar pela North Star."*

**Instruções do Projeto no Claude:** manter instruções do Projeto minimalistas, com resumo de 10-15 linhas do macro (Rota D + apostas + três públicos + ondas de roadmap + escopo V1 sintetizado). Este documento fica fora das instruções, para ser colado sob demanda.

---

## 24. Histórico de revisões

| Versão | Data | Mudanças |
|---|---|---|
| 1.0 | 20/04/2026 | Versão inicial consolidando todas as decisões até aqui. |
| 1.1 | 20/04/2026 | Adicionadas seções 16 (Escopo V1 consolidado em 10 módulos), 17 (aplicação da régua aos casos borderline) e 18 (ordem de construção dos 4 meses). Seção 15 reformulada como "Régua de escopo" (sem mudança de conteúdo, só de foco). Decisões em aberto renumeradas: removida "lista mínima da V1" (agora resolvida em §16); adicionadas "Stack técnico V1" e "Dimensionamento de time", ambas derivadas da North Star. Glossário e princípios mantidos. |
| 1.2 | 25/04/2026 | Adicionada seção 22 (Decisões da North Star Architecture) consolidando 10 invariantes arquiteturais e três decisões de alto nível: tenancy (shared DB + RLS + `ente_id`, com migração planejada para pool-per-UF), modelo de serviços (core monolítico modular + Plataforma de IA como satélite), ingestão de dados de legado (endpoints explícitos + marcador `origem`). Seção 16.9 (Migração) reescrita: conectores automatizados saíram da V1 inicial, fica apenas a infraestrutura que viabiliza Migração depois sem refactor. Seção 19 atualizada para refletir progresso da North Star. Seções 22 (Como o documento deve ser usado) e 23 (Histórico) renumeradas para 23 e 24. |
| 1.3 | 25/04/2026 | Adicionada subseção 22.3 (Contrato entre core e Plataforma de IA) consolidando cinco sub-decisões fechadas: topologia híbrida por classe de operação (síncrono/assíncrono/streaming), protocolo concreto (HTTP/JSON+OpenAPI / bus de eventos + filas / SSE), buses lógicos separados com eventos de integração explícitos como contrato, propriedade de dados dividida por natureza do artefato (object storage compartilhado para áudio bruto; artefatos técnicos na IA; artefatos legais no core), modelo de erros e retry com taxonomia de seis categorias. Decisões arrastadas que viraram parte da North Star: object storage compartilhado em BR como infra arquitetural, fila persistente obrigatória desde o dia 1, SSE como protocolo de streaming já adotado, distinção evento de domínio vs. evento de integração como disciplina geral. Antiga seção 22.3 (Itens parqueados) renumerada para 22.4 e atualizada — item "Contrato core ↔ Plataforma de IA" removido por estar fechado, demais itens renumerados de 1-6. Seção 19 atualizada para refletir o novo estado. |
| 1.4 | 26/04/2026 | Adicionada subseção 22.4 (Modelo de dados do processo legislativo) consolidando decisões dos oito eixos discutidos individualmente: estrutura das proposições (STI híbrido com colunas tipadas para atributos quentes + JSONB sidecar para PDL), texto da proposição (versionamento append-only com proveniência explícita, blob markdown estruturado leve, estratégia híbrida inline/URI com threshold de 32KB, `estado_versao` para rascunho/vigente/superada/arquivada), tramitação (máquina de estados declarativa com DSL pequena, cópia integral de templates, paralelismo via subprocessos com handles, prazos como tabela operacional), emendas (entidade própria com numeração local e ciclo universal em enum, aplicação ao texto-mãe via rascunho humano), apensação (tabela de associação com histórico, mudança de principal como dois atos auditados), pareceres (entidade governada pelo motor de tramitação, polimorfismo de objeto), votação (votos append-only, votos secretos em tabela separada para preservar sigilo, polimorfismo de objeto), identidade canônica e imutabilidade (UUID interno + numeração canônica atômica, taxonomia de imutabilidade em três níveis). Disciplinas arquiteturais derivadas: convenção de campos transversais (`created_at`, `updated_at`, `created_by`, `updated_by`, `lock_version`, `origem*`), padrão de referência polimórfica `(objeto_tipo, objeto_id)`, padrão de versionamento de texto, taxonomia de imutabilidade em três níveis, motor declarativo compartilhado com compliance, padrão de "prazo de domínio" generalizável. Antiga seção 22.4 (Itens parqueados) renumerada para 22.5; item "Modelo de dados do processo legislativo" removido por estar fechado, demais itens renumerados de 1-5; item de compliance carrega disciplina arrastada de compartilhar DSL com tramitação. Seção 19 e referência interna em 22.3.6 atualizadas para refletir nova numeração. |
| 1.5 | 26/04/2026 | Adicionada subseção 22.5 (Modelo de autenticação e autorização) consolidando decisões dos sete eixos discutidos individualmente: sujeitos e fluxos de autenticação por ator (gov.br exclusivo para cidadão com bronze/prata/ouro aceitos uniformemente, senha+MFA com TOTP obrigatório para servidor/vereador, IdP fisicamente separado para admin interno com WebAuthn hardware key obrigatório, ICP-Brasil reservada exclusivamente para assinatura digital), modelo de autorização híbrido pragmático (RBAC clássico + DSL pequena compartilhada com tramitação e compliance, sem peça de infra dedicada de auth na V1, inventário de ~10 funções de relação expostas pelos bounded contexts donos), papéis com granularidade dupla (estáticos centrados em pessoa em tabela; contextuais a recurso no recurso), mandato como entidade explícita com cascata via `tem_mandato_vigente`, Mesa Diretora como tipo especial de comissão, distinção titularidade vs. exercício via `quem_exerce_presidencia`, sessão e escopo ativo (identidade ↔ vínculo separados, um vínculo ativo por sessão, multi-perfil sem vazamento de permissões), avaliação dinâmica em defesa em profundidade (middleware grosso + `policy.check` fino in-domain, sem PDP externo na V1, fail closed por default com fail explicit para transientes), MFA e step-up (TOTP obrigatório, WebAuthn opcional como upgrade, step-up para operações sensíveis com janela default 5min, ICP-Brasil estritamente para assinatura), recuperação de fator com aprovação dual para perfis de poder elevado, taxonomia de eventos de auth em quatro classes (domain events no bus, audit log de produto, logs de aplicação, métricas), retenção por classe com defaults e pisos legais, política diferenciada de apagamento LGPD (não-apagáveis/apagáveis/pseudonimizáveis), consentimentos como entidade versionada por base legal. Disciplinas arquiteturais derivadas: identidade ↔ vínculo separados, dupla granularidade de papéis, motor de autorização compartilhado com política como dado por bounded context, mandato com cascata de papéis temporais, instante como parâmetro em toda função de relação, distinção autenticação ↔ assinatura como princípio arquitetural, tunables operacionais como configuração e não código, defesa em profundidade na avaliação, taxonomia de eventos em quatro classes, apagamento LGPD nunca como DELETE silencioso. Antiga seção 22.5 (Itens parqueados) renumerada para 22.6; item "Modelo de autenticação e autorização" removido por estar fechado; item de modelo de dados de sessão plenária e item de motor de regras de compliance carregam disciplinas arrastadas de 22.5. Seção 19 atualizada para refletir progresso da North Star. |
| 1.6 | 29/04/2026 | Incorporadas descobertas de pesquisa qualitativa sobre prática de captação de áudio/vídeo em câmaras municipais brasileiras: OBS Studio + transmissão YouTube Live é padrão de fato em câmaras pequenas e médias (maioria do mercado-alvo); câmaras médias frequentemente usam appliance proprietário (Promic/Riole, Softcam, ESCAL); câmaras grandes têm estúdio próprio. Cinco perguntas em aberto do briefing decididas com rationale registrado. **Decisões fechadas:** (1) **Plugin de Captura Sincronizada (categoria de adaptadores; primeiro adaptador concreto provavelmente OBS) fica fora da V1, vira satélite separado** sob o mesmo padrão da §16.9 (Migração) — produto técnico de outra natureza com estimativa 6-8 semanas (médio) a 3-4 meses (completo) que não passa na régua da §15. (2) **Na V1 entram apenas:** endpoint de ingestão padronizado e agnóstico à fonte, utilitário CLI/watch folder genérico (~2 semanas), e documentação de configuração de captação por categoria (guia prioritário OBS, guia genérico watch folder, nota para estúdios). (3) **Câmaras com appliance proprietário absorvidas pelo utilitário genérico na V1** — adapters específicos por fornecedor só sob demanda no satélite. (4) **Captação não vira seção própria** — fica distribuída entre §16.4 (escopo da V1), §22.3.4 (caminho arquitetural de ingestão), §11 (pitch zero-friction), §13 (premissa validada), §14 (riscos). (5) **Posicionamento universal "complementamos, não substituímos"** vale para qualquer cadeia de captação (OBS, appliance, estúdio próprio); pitch concreto é trabalho comercial. **Alterações por seção:** §11 ganha duas notas (pitch zero-friction agnóstico à cadeia atual e posicionamento universal "complementamos, não substituímos"); §13 ganha premissa validada (OBS+YouTube como padrão de fato, com nuance arquitetural explícita de que prevalência de mercado não privilegia OBS na arquitetura — endpoint e modelo de dados são agnósticos à fonte), premissa a monitorar (tendência regulatória de gravação como registro oficial — caso Ponta Grossa/PR), e clarificação de que a descoberta afeta CAC e não TAM (cuidado contra inflação de premissas comerciais); §14 ganha Risco 5 (dependência operacional da cadeia OBS+YouTube como risco de volume — herdado por ser a cadeia mais comum no mercado-alvo, não por ser a única absorvida — com mitigações arquiteturais transversais, operacionais, e de produto); §16.4 reescrita com nota dedicada sobre captação assumindo qualquer cadeia razoável de modo agnóstico, decisão de Plugin de Captura Sincronizada fora da V1, e tensão explicitamente registrada entre régua de escopo (§15) e demo power da Aposta 1; §17 ganha caso borderline aplicado para Plugin de Captura Sincronizada rico (fora da V1 com rationale completo); §18 ganha dependência de endpoint de ingestão e utilitário até o mês 3 para viabilizar primeira demo da Ata Automática; §22.3.4 explicitada com caminho de ingestão em três fontes (gravação local pós-sessão como primária na V1; RTMP duplicado ao vivo via Plugin de Captura Sincronizada em V2+; YouTube Live API como fallback de contingência) e disciplina derivada de uniformidade dos contratos de eventos independente da fonte; §22.6 item 2 enriquecido com disciplina arrastada sobre markers de eventos legislativos no áudio quando o satélite Plugin de Captura Sincronizada entrar em escopo. **Não alteradas:** §10 (contratação) — adicionar especialista em desenvolvimento de plugin de captação não se justifica antes do satélite virar realidade; §16.9 (Migração) — captação é ortogonal a migração e ambas seguem o mesmo padrão "satélite separado quando primeiro cliente justificar"; §22.4 e §22.5 — descoberta não toca modelo do processo legislativo nem modelo de auth; seções 1–9 — Rota D, sequenciamento, apostas, ondas de roadmap inalterados. **Plataformas de videoconferência prioritárias** (Zoom e Google Meet, em ordem) registradas em conversa de North Star como decisão prospectiva para integração Nível 2 futura — entram no documento-mestre quando a subseção 22.7 (modelo de sessão plenária + áudio + real-time) for consolidada. **Patch de framing aplicado em 29/04/2026 (mesma data da v1.6, sem bump de versão):** correção cirúrgica para deixar explícito que OBS é fonte prevalente no mercado-alvo, **não** fonte privilegiada arquiteturalmente. Endpoint de ingestão, utilitário CLI/watch folder, schema de gravação e enums de `fonte_ingestao` são agnósticos à fonte e funcionam para qualquer cadeia razoável (OBS, appliance Promic/Riole/ESCAL/Softcam, estúdio próprio, ad-hoc). Satélite renomeado de "Plugin OBS Oficial" para "Plugin de Captura Sincronizada" — categoria de adaptadores extensível por fornecedor; OBS é o primeiro adaptador concreto provável pela prevalência, não o único possível. Documentação V1 entregue por categoria (guia OBS prioritário pela prevalência, guia genérico watch folder, nota para estúdios), não por fornecedor único. §11 reformulada para pitch agnóstico à cadeia. §13 ganha nuance arquitetural explícita ("OBS é prevalente, não privilegiado"). §14 Risco 5 reformulado como dependência herdada por volume da cadeia mais comum, com modos de falha de outras cadeias também reconhecidos. §17 generalizado para a categoria. §22.3.4 fonte secundária generalizada. §22.6 item 2 atualizado. Patch sem bump de versão por ser correção de framing dentro da v1.6 ainda fresca, antes que viesse a se enraizar como premissa de outras decisões. |
| 1.7 | 29/04/2026 | **Geração automática de ata pós-sessão sai do escopo da V1.** V1 entrega anexação de ata redigida externamente pelo servidor (PDF searchable ou DOCX subido ao sistema, indexado para busca, assinado digitalmente conforme §22.5 eixo F, tratado como artefato legal com regime de imutabilidade pós-publicação). Trabalho técnico de geração automática roda em paralelo à V1 sem prazo declarado — pipeline de transcrição da Onda 0 continua sendo construído, processa áudio real desde a primeira câmara cliente, dataset golden (transcrição, ata humana anexada) se forma naturalmente conforme adoção. Quando geração atingir qualidade adequada para produção, é liberada em release nomeado (provavelmente V1.5 ou V2). **Transcrição automática continua na V1** porque busca semântica intra-câmara (§16.3) depende dela; pré-atribuição de fala via Caminho C (inferência por contexto de domínio: tribuna, votação, mesa) é automática; revisão manual pelo servidor é opcional (câmara que revisa tem busca de qualidade superior). **Alterações por seção:** §7 — ata automática removida da Onda 1, registrada como entrega futura sem prazo; §12 — tabela ganha coluna "Status V1", ata automática marcada como "Fora da V1" com nota sobre anexação como alternativa V1; §16.4 — substituída entrada "Geração automática de ata pós-sessão (feature-demo principal)" por entrada "Anexação de ata redigida externamente" descrevendo fluxo concreto (upload PDF/DOCX, assinatura ICP-Brasil, indexação, imutabilidade), adicionada nota explícita "Geração automática de ata pós-sessão fica fora da V1" com descrição do trabalho paralelo, esclarecida posição da transcrição automática na V1 e da pré-atribuição via Caminho C com revisão opcional, ata automática adicionada à lista "Não entra"; §18 — mês 2-3 atualizado para incluir "fluxo de anexação de ata pelo servidor" e "pipeline de transcrição automática com pré-atribuição via Caminho C", mês 3-4 ganha nota sobre trabalho técnico de geração automática rodando em paralelo durante toda a V1. **Não alteradas:** §10 (decisão de contratação cedo de especialista em regimento legislativo continua válida — agora ainda mais importante, porque a ata humana anexada é o dataset golden); §16.8 (Camada de Confiança mínima na V1 continua igual — cobre os outputs de IA que estão na V1: resumo cidadão, copiloto de redação, busca semântica); §22.3 e §22.4 e §22.5 (modelos de dados não tocados — entidade `ata_publicada` na futura §22.7 vai carregar discriminator `origem_redacao` aceitando ambos `redigida_externamente` e `gerada_automaticamente`, mas capability do produto V1 só expõe `redigida_externamente`); seções 1-6, 8-11, 13-15, 17, 19-21 — não tocadas; satélites Migração (§16.9) e Plugin de Captura Sincronizada (§16.4 v1.6) continuam como estavam. **Implicação de pitch:** §11 não foi reformulada nesta versão porque pitch é trabalho comercial; mas o time comercial deve saber que V1 entrega "modernização do fluxo legislativo com IA pontual (busca semântica, resumo cidadão, copiloto)" e não "geramos sua ata automaticamente" — ata gerada é roadmap declarado, não promessa V1. |
| 1.8 | 24/05/2026 | Adicionada subseção 22.6 (Modelo de sessão plenária, áudio e real-time) consolidando decisões dos sete eixos discutidos individualmente (eixo H — bulk histórico — saiu da pauta na discussão deste bloco; cliente piloto descartou a eventualidade de importar gravações históricas, tema reabre quando virar exigência real): entidade sessão com hierarquia temporal em três entidades explícitas (legislatura → sessão legislativa → sessão), tipos com enum nominal + capabilities desacopladas, seis estados incluindo `não_realizada` como terminal alternativo para sessão prejudicada, modalidade independente do tipo (`presencial` | `remota` | `hibrida`), numeração canônica resetando por sessão legislativa; pauta como entidade única com snapshots canônicos pré-sessão + alterações intra-sessão append-only, itens com `fase` como atributo, tipos com enum fechado e FKs declarativas, votação apontando para proposição com `pauta_item_id` opcional como contexto temporal, visibilidade pública/restrita como atributo da versão da pauta; presença como eventos append-only com modalidade plenário/remoto e tipo `entrada` | `saida` | `retorno` | `mudanca_modalidade`, função canônica `está_presente_em(usuario, sessao, instante)`, inferência automática materializada como evento explícito, justificativa de ausência como ato administrativo apartado, quórum como consulta derivada, integração com plataforma de videoconferência em Nível 1 na V1 (presença remota declarada manualmente; Zoom e Google Meet como plataformas prioritárias para Nível 2 futuro); áudio/vídeo com `gravacao_segmento` como unidade técnica do arquivo (não unidade regimental), endpoint de ingestão agnóstico à fonte + utilitário CLI/watch folder, container bruto opaco para o core, vídeo como cidadão de segunda classe, estado de processamento derivado de vínculos e eventos; diarização e transcrição com palavra como granularidade canônica na Plataforma de IA, sentença/turno como projeções derivadas, atribuição via combinação Caminho C (inferência por tribuna/votação/mesa) + revisão manual opcional pelo servidor, identificação automática por voiceprint como evolução V1.5+, transcrição versionada por modelo com ata mantendo ponteiro para versão-base, embeddings por sentença na V1, eventos `TranscricaoBrutaConcluida`/`TranscricaoAtribuida`/`TranscricaoRevisada` atravessando fronteira IA → core, **decisão da v1.7 incorporada** (geração automática de ata fora da V1; V1 entrega anexação de ata redigida externamente; schema de `ata_publicada` com discriminator `origem_redacao` comporta ambos caminhos preparando para liberação futura); tribuna com `inscricao_oradores` separada de `fala_executada` (intenção vs. execução), cronômetro como projeção sobre eventos (não persistido em snapshots), aparte/pela ordem/questão de ordem em `tipo_fala` com regras na DSL, `decisao_mesa` apartada para registro de decisão sobre questão de ordem, tribuna subordinada à fase da pauta com vínculo opcional à proposição; real-time com SSE como protocolo único para clientes finais (long polling como fallback degradado), SSE como projeção do bus interno, canais por tipo de consumidor (plenário/dashboard de vereador/portal público), autorização avaliada na abertura da conexão e por evento sem revogação dinâmica em conexão aberta na V1, sequência monotônica por canal com retenção de 5 min em Redis stream para replay, fan-out dentro do monolito como módulo dedicado na V1, cronômetro como marcos + cálculo client-side (não ticks por segundo). Disciplinas arquiteturais derivadas: tempo como coordenada de primeira classe na sessão plenária (instante como parâmetro de funções de relação, peso maior que outros contextos), distinção entidade técnica vs. entidade regimental (gravação técnica vs. sessão regimental, alinhamento por instante e não por chave hierárquica forte), capabilities desacopladas do tipo nominal (tipo é dado regimental, comportamento é capability overridável com auditoria), SSE como projeção do bus interno não substituto (bus continua verdade-fonte, stream é projeção para clientes externos), estado emergente vs. estado mutável (processamento derivado de eventos e vínculos sem coluna `status` mutável), eventos de cronômetro como marcos não ticks (cliente computa duração local a partir de transições estruturais). **Decisões deferidas explicitamente registradas:** prova de conceito de DER (Diarization Error Rate) e accuracy do Caminho A em câmara real para mês 1-2 da V1 antes de fechar arquitetura em cima de estimativas; fila do pipeline de IA como FIFO simples na V1 (prioridade entra quando aparecer caso de uso real); valor `importacao_legado` presente nos enums (`origem`, `fonte_ingestao`) desde a V1 mas sem fluxo produtor V1 (consistente com §22.3.4 v1.6, ativado quando bulk histórico voltar à pauta). **Alterações em outras seções:** §22.5 (antiga "Itens parqueados") renumerada para 22.7; antiga 22.6 item 1 (modelo de real-time) e item 2 (modelo de dados de sessão plenária + áudio) removidos por estarem fechados em 22.6; itens 3 e 4 renumerados para 1 e 2; novo item 1 (motor de compliance) ganha disciplina arrastada explícita de compartilhar DSL e motor também com regras do plenário (verificação de quórum, regras de votação por matéria, tempos regimentais de tribuna — todas decididas em 22.6 como configuração no motor). §19 atualizada para refletir progresso da North Star com 22.6 fechado e referência aos itens parqueados ajustada para 22.7. **Não alteradas:** §10 (decisão de contratação cedo de especialista em regimento legislativo continua válida), §16.4 (Sessões Plenárias na V1 — entradas e não-entradas mantidas conforme v1.7), §16.8 (Camada de Confiança mínima na V1 cobre outputs de IA presentes na V1: busca semântica, resumo cidadão, copiloto de redação), §22.3 e §22.4 e §22.5 (subseções já fechadas não tocadas — todas as decisões de 22.6 são consistentes com as disciplinas estabelecidas), seções 1-9, 11-18, 20-21 — não tocadas; satélites Migração (§16.9), Plugin de Captura Sincronizada (§16.4 v1.6) e Geração de Ata Automática (decisão v1.7) continuam como estavam. **Bulk histórico** (antigo eixo H) saiu da pauta neste bloco — cliente piloto descartou; reabre quando virar exigência real de qualquer cliente, em chat dedicado. |
| 1.9 | 19/06/2026 | Adicionada subseção 22.7 (Motor de regras de compliance), consolidando o **Eixo A — vocabulário da DSL**: forma "A2" (núcleo de expressão comum + envelopes YAML por contexto), type-checking estático no save time (regra mal-tipada não persiste como ativa), registry central de funções de relação com ownership por bounded context, sistema de tipos com primitivos e compostos, conjunto de operadores núcleo, inventário de builtins, e schemas de envelope para compliance/tramitação/autorização. Quatro disciplinas derivadas (uma DSL/um núcleo/múltiplos envelopes; validação no save time como disciplina; registry central com ownership distribuído; ferramental de simulação/debug compartilhado). **Listas granulares (tipos, operadores, builtins, schemas de envelope) explicitamente parqueadas em §22.7.4 como "a transcrever da sessão de origem"** — a sessão que fechou o Eixo A é posterior ao v1.8 e seu detalhe fino não estava no material de handoff; preenchidas quando recuperada ou validadas pelo Eixo C. Antiga §22.7 "Itens parqueados da North Star" reestruturada: o motor de compliance graduou de item parqueado para subseção própria (22.7); item remanescente (LLM/soberania) movido para nova **§22.8 — Itens parqueados da North Star**. §1 (Identificação) ganha o **nome do produto "O Plenário"** (tagline "Onde a câmara acontece"), tratado como decisão provisória até checks de domínio e INPI. **Não alteradas:** §22.1–§22.6 e demais seções. |
| 1.10 | 20/06/2026 | Consolidado o **Eixo C de §22.7 — stress-test da DSL de compliance** (nova subseção **22.7.5**). Requisitos reais do TCE-CE da parte legislativa (varredura sourced: remessa/prazos, atos legislativos, transparência ativa, quórum) expressos como templates no envelope de compliance; **forma A2 validada** (3 de 4 casos encaixaram limpo; quórum não-aderiu de propósito). **Nenhuma decisão estrutural do Eixo A reaberta** — o stress-test **derivou de carga real** o vocabulário que a §22.7.4 deixara parqueado: builtins (relógio injetado, dia-útil dependente de calendário de feriados, `arredonda_cima`, `fracao` exata, `prazo_vigente`, `parametro_tenant`), funções de relação por contexto dono, e extensões de núcleo (tipo `Competencia`, tipo composto `Maioria/Limiar` com base de cálculo, operador `in`, registros). Quatro achados estruturais: **S1** (o motor _monitora prazo_, não só avalia booleano — elevado no roadmap dos +5 eixos), **S2** (`dominio` é taxonomia em camadas federal/tce_estadual/regimento_tenant, não "qual TCE"), **S3** (`prazo` é expressão multi-fonte — generaliza `prazo_dominio_ativo`), **S4** (quórum/votação/tribuna usam envelope de _guard_, não de compliance — **resolve a pendência de §22.7.4**). Reclassificações: numeração de atos → integridade de dados (schema/Eixo B), não compliance; índices ITM/PNTP → medição, não regra dura. §22.7.4 atualizada (pendências de envelope e de vocabulário resolvidas/encaminhadas); intro de §22.7 e roadmap ajustados. Rascunho de origem: `docs/05-eixo-C-stress-test-rascunho.md`. **Próximo: Eixo B (schema) sobre vocabulário validado. Não alteradas:** §22.1–§22.6, §22.7.1–22.7.3, §22.8 e demais seções. |
| 1.11 | 20/06/2026 | Consolidado o **Eixo B de §22.7 — schema das tabelas de template/regra** (nova subseção **22.7.6**). **Decisão estrutural central:** separar a **definição da regra** (dado de domínio, central, **sem `ente_id`**) do **binding por tenant** (config, **com `ente_id`**) — divergência **consciente** do "override por câmara via cópia integral" de §22.4 eixo C, justificada por **S2** (regra federal/estadual é lei uniforme, não customização por câmara; copiá-la por ~1.500 entes seria insustentável). **Cinco tabelas:** `template_compliance` (definição versionada por cópia integral; `fonte_yaml`+`forma_compilada` tipada; carimba `registry_versao_ref` do save-time type-check), `compliance_regra_tenant` (binding **por escopo**, não linha-por-tenant — federal/estadual aplicam por jurisdição via UF, binding só p/ param/opt-out/pin; `regimento_tenant` exige binding), `prazo_dominio_vigente` (prazo regulatório com override por Ofício Circular — **S3** — distinto do `prazo_dominio_ativo` de runtime), `calendario_feriado` (nacional+municipal), `registry_catalogo_versao`. **Registry de funções de relação + tipos = catálogo de infra compartilhada declarado em código e versionado, não tabela de tenant** — materializa a "mecânica fina do registry" de §22.7.4 (versionamento de assinatura + re-validação no deploy) sem reabrir A2. **Fronteira:** Eixo B é schema **estático**; runtime (obrigação/avaliação/vencimento) roteado a um dos +5 eixos (**S1**). **Nova disciplina derivada:** domínio vs. tenant é decisão explícita por tabela do motor (diverge do default §22.2 onde a tabela é genuinamente de domínio). Rascunho de origem: `docs/06-eixo-B-schema-rascunho.md`. **Próximo: +5 eixos (comportamento de runtime do motor, elevado por S1) + avaliador executável da DSL. Não alteradas:** §22.1–§22.6, §22.7.1–§22.7.5, §22.8 e demais seções. |
| 1.12 | 20/06/2026 | Consolidado o **Eixo de runtime do motor de compliance** (nova subseção **22.7.7**) — primeiro dos "+5 eixos", **elevado por S1** (o motor _monitora prazo_, não só avalia booleano). O motor passa de **forma** (schema estático, §22.7.6) a **comportamento**. **Nó central — obrigação temporal (S1) em dois sabores:** *com prazo* (deadline-bound) materializa instância com relógio; *contínua* (sem bloco `prazo`) não materializa, só produz avaliação. Fecha o **trio de tenancy** do motor: definição = domínio, binding = tenant, **obrigação = tenant** (`ente_id`). **Generalização disparada (disc. 6):** 2º caso de prazo de domínio chegou (envio TCE) → `proposicao_prazo_ativo` generaliza para **`prazo_dominio_ativo` polimórfico** `(objeto_tipo, objeto_id)`. **Duas tabelas novas (runtime, tenant):** `prazo_dominio_ativo` (obrigação materializada com relógio; aponta a versão exata da regra; idempotência por `UNIQUE(ente_id, template_chave, objeto_tipo, objeto_id)`; ciclo `pendente→cumprida|vencida|dispensada|cancelada` **enum fixo em código**, não template — como emendas §22.4 eixo D), `compliance_avaliacao` (**append-only**, imutabilidade nível a — a **prova de compliance**, Invariante 10 + confiança operacional; carimba regra + catálogo, fechando o ciclo do save-time type-check do Eixo B com o runtime). **Modelo de avaliação (lógico decidido, infra deferida — como §22.4 eixo C):** evento (primário, via bus existente) + sweep agendado (único gatilho temporal) + sob demanda; avaliador é o **mesmo** dos 4 usos da DSL (disc. 5). **Monitoramento de prazo (S1):** "a vencer" é derivação de leitura, não estado persistido; **re-stamp no deslize de circular** (S3 ↔ Eixo B) atualiza `vence_em` de obrigações abertas, auditado; `acao_no_vencimento` na V1 = emitir evento, escalonamento é consumidor. **Fechou dois dos +5** (comportamento temporal + auditoria); versionamento já fechara no Eixo B. **Restam +2:** geração de artefatos de envio ao TCE; expansão a outros TCEs. Rascunho de origem: `docs/07-eixo-runtime-motor-rascunho.md`. **Próximo: avaliador executável da DSL (primeira implementação de fato). Não alteradas:** §22.1–§22.6, §22.7.1–§22.7.6, §22.8 e demais seções. |
| 1.13 | 20/06/2026 | **Reconciliação de SSOT — geração automática de ata por IA entra na V1** (reverte v1.7/v1.8, que a haviam tirado). Materializa a decisão de produto de 20/06 (feature-âncora de compra; `produto/05`§2, `produto/12`): nº 1 motivo de troca declarado em campo e arma contra o SAPL grátis. **Entra em modo "produtividade"** (economia de horas do servidor), não "registro oficial" (configuração futura); **revisão humana obrigatória antes de publicar** (§16.8). **Cauda:** captação de áudio vira parte da oferta (§16.4, §22.3.4, §22.6); pipeline áudio→transcrição→sumarização aperta o cronograma de §18 (flag de dimensionamento de time). **Sem mudança de modelo de dados:** `ata_publicada` já fora desenhado (v1.7/v1.8) com discriminator `origem_redacao` aceitando `redigida_externamente` **e** `gerada_automaticamente` — a V1 agora expõe os dois caminhos; reconciliação é só do gate de capability de produto. **Seções alteradas:** §7 (ata volta à Onda 1), §12 (tabela Status V1), §16.4 (entrada principal + "Não entra"), §18 (mês 3-4 vira escopo entregável), §22.4 eixo E (`ata_publicada` expõe ambos os caminhos), §22.6 (satélite → feature V1). **Não alteradas:** §22.1–§22.3, §22.5, §22.7–§22.8 e demais. Decomposta em features na trilha de produto/UX (`produto/13`). |
| 1.14 | 20/06/2026 | **Trilha de produto/UX — expansão de escopo da V1 com quick wins e fechamento de 3 lacunas de completude** (decisões Emilio, 20/06). **Novo módulo §16.11 — Painéis, Pendências e Notificações** (read-model sobre o substrato event-driven + motor de prazo; torna visível a aposta de confiança operacional e a proposta de valor da persona presidente, antes órfãs de feature). **§16.3** ganha o **fluxo pós-aprovação** (autógrafo → sanção/veto → promulgação → publicação), completando a fronteira "da proposição à publicação" — reusa votação (§16.4) + DSL (§22.7.5 S4); o modelo de dados já o antecipava (§22.4 eixo B). **§16.5** ganha (a) **consolidação de legislação manual assistida** (repositório as-enacted + texto vivo versionado; IA-auto e bulk histórico ficam V1.5+/Onda 2) e (b) **artefato de publicação oficial leve** (assinado/numerado/imutável + feed ao DOM; ser o DOe-de-registro fica V1.5). Decompõe-se em features em `produto/13` (**78 features, 11 módulos**). **Pendências:** prazos/rito do veto (especialista de regimento §10), profundidade do acervo histórico a consolidar (migração), validar com o beachhead se DOe oficial é dor de compra (jurídico). **Não alteradas:** §22.* e demais seções. |
| 1.15 | 20/06/2026 | **Decisão de stack — linguagem de backend = Clojure** (Emilio, 20/06). Resolve a parte de *linguagem de backend* do stack deferido em §22.4.4 (persistência concreta, IdP, infra de worker/fila e frontend/mobile seguem deferidos). Racional: o coração é o motor de **DSL/regras** (§22.7); Lisp homoicônico torna regra/AST/catálogo **dados nativos** (Invariante 4 + Disciplina 5), imutabilidade casa com auditoria append-only (Invariante 10), `ratio` nativo dá a aritmética exata do quórum. O protótipo `motor-dsl/` (Python, §7) foi **portado para Clojure** em `motor-dsl-clj/` — projeto deps.edn, 7 namespaces espelhando o Python, **8 testes / 44 asserções verdes** (`clojure -M:test`) + demo; Python fica como referência validada. **Seção alterada:** §22.4.4 (nota de resolução). **Não alteradas:** demais. |
| 1.16 | 20/06/2026 | **Aberto o chat de stack** (continuação de §22.4.4) e fechado o **Eixo 1 — local de deployment e soberania** (nova subseção **§22.9**). Deployment **central único** (SaaS multi-tenant §22.2; descartada explicitamente "on-prem por câmara" — quebraria §22.2 + compliance central §22.7 + apostas de IA). Três decisões: (a) **arquitetura provider-neutral / open-source-first** como disciplina permanente (k8s, Postgres, Redis, object storage S3-compatível, fila — zero managed service proprietário; "on-prem vs. cloud" vira escolha de deploy trocável, não de arquitetura); (b) **V1 em cloud região-BR** (sa-east-1 referência) pela velocidade + DR multi-AZ + elasticidade + GPU alugada — fundamentado por pesquisa sourced de que região-BR de hyperscaler é procurement-safe para câmara municipal (nuvem soberana obrigatória é federal/SISP, não alcança município; LGPD regula transferência, não localização); (c) **caminho comprometido para colo/iron própria** quando escala+capital+edital justificarem (anos 1-3, padrão §22.2). IA: inferência in-region (Bedrock/Claude sa-east-1) atrás de porta abstraída; §22.8 item 1 segue parqueado com a postura encaminhada. **Seção nova:** §22.9. **Não alteradas:** §22.1–§22.8 e demais. |
| 1.17 | 20/06/2026 | **Refinamento de §22.5 (login institucional) — passwordless-first via passkey + papel do e-mail código de uso único** (decisão Emilio, 20/06, no chat de stack; toca o eixo IdP/auth ainda não formalmente aberto, mas a correção entrou agora). **WebAuthn/passkey promovido de fator opcional a fator primário recomendado** para servidor/vereador (phishing-resistant por amarração ao origin, multifator num gesto, menos atrito) — senha + TOTP permanece como **piso obrigatoriamente disponível** para quem não usa passkey. **Reconciliação do "+ e-mail código de uso único":** Emilio o pediu para *login institucional*; gravado como canal de **bootstrap de primeiro acesso (enrollment) + recuperação** — **nunca fator standing de login** (posse de inbox é fator único e phishável). Mantém intacta a posição §22.5.2 eixo F de não usar e-mail como fator; só nomeia explicitamente seu lugar no fluxo institucional (substitui a antiga "senha temporária one-time" do enrollment). **Cidadão e admin interno inalterados** (gov.br exclusivo; hardware key física). **Seções alteradas:** §22.5.1 (servidor/vereador), §22.5.2 eixo F (MFA por tipo de vínculo). **Não alteradas:** §22.1–§22.4, §22.6–§22.9 e demais. |
| 1.18 | 20/06/2026 | Fechado o **Eixo 2 — persistência** do chat de stack (§22.9). **PostgreSQL vanilla** como banco transacional do core e do motor (Emilio, 20/06): managed em cloud-BR na V1 (RDS for PostgreSQL / CloudSQL / Azure Database for PostgreSQL — **engine Postgres real, não reimplementação proprietária tipo Aurora**, para honrar o provider-neutral do Eixo 1), portável para self-hosted/colo sem refactor; confirma o default herdado de §22.2 (relacional, `ente_id`, RLS). **Quatro pontos finos cravados:** (1) **numeração canônica gapless por linha-contador** (`SELECT…FOR UPDATE` / `ON CONFLICT…RETURNING` na transação do ato), **não SEQUENCE nativa** (que avança fora da transação e deixa buraco no rollback) — reconcilia a menção casual a "SEQUENCE" do §22.2; (2) **idempotência do bus por `idempotency_key` + `UNIQUE` (CAS)** no consumidor = at-least-once + dedup no destino, sem depender da fila; (3) **polimórfico `(objeto_tipo, objeto_id)` com integridade em camadas** — guard de serviço + índice composto começando por `objeto_tipo` + CHECK XOR só nas relações quentes (não uniformizar é consciente); (4) **particionamento declarativo nativo por `hash(ente_id)`**, alinhado ao caminho shared-DB→pool-per-UF do Eixo 1, com RLS como defesa em profundidade por cima. **`pgvector` viável sem violar o Eixo 1, mas deferido ao Eixo 10** (embeddings na Plataforma de IA, §22.3.4). **Seção alterada:** §22.9 (Eixo 2 + pauta restante). **Não alteradas:** §22.1–§22.8 e demais. |
| 1.19 | 20/06/2026 | Fechado o **Eixo 3 — fila, worker e scheduling** do chat de stack (§22.9). **Fila durável + outbox transacional no próprio Postgres** (`SELECT…FOR UPDATE SKIP LOCKED`), **não broker dedicado na V1** (Emilio, 20/06) — materializa o "fila persistente desde o dia 1" (§22.3) sobre o banco do Eixo 2, por menos peça móvel (Eixo 1) e escala real (~1.500 entes, não firehose). **Quatro decisões:** (1) **outbox transacional** enfileira job/evento na **mesma transação** da escrita de domínio (commit atômico) — relay drena pro bus core↔IA (§22.3) + consumidores, idempotência `UNIQUE`+CAS do Eixo 2 torna redelivery no-op, mata o dual-write; (2) **fila sobre `SKIP LOCKED`** com retries+dead-letter como linhas; (3) **worker em pools lógicos por classe** (jobs rápidos de core isolados dos pesados/longos de áudio→ata-IA, §22.3.4); (4) **scheduler com leader-election por advisory lock** = fonte de cron única pro sweep de compliance (§22.7.7), roll-ups `LoginFalhou` (§22.5), monitoramento de prazo. **Fila atrás de porta abstraída** (swap p/ NATS/Rabbit/Kafka = detalhe de deploy se throughput exigir); contenção fila↔OLTP mitigada pela porta + caminho shared-DB→pool-per-UF. **Seção alterada:** §22.9 (Eixo 3 + pauta). **Não alteradas:** §22.1–§22.8 e demais. |
| 1.20 | 20/06/2026 | Fechado o **Eixo 4 — cache efêmero** do chat de stack (§22.9). **Valkey** (store in-memory protocolo-Redis, fork **BSD/Linux Foundation** do Redis 7.2 — escolhido sobre o Redis relicenciado SSPL/AGPL pela disciplina open-source-first do Eixo 1) como camada efêmera **escopada a três usos**, não cache-tudo (Emilio, 20/06): (1) **denylist de revogação imediata de token** — resolve o ponto deferido de §22.5.4 a favor de lista in-memory e **contra** só-TTL (eventos `MandatoCassado`/`ServidorDesligado` não toleram a janela de até 15 min); (2) **rate-limit/anti-brute-force** (`INCR`); (3) **backplane SSE** (§22.6, `PUB/SUB`) p/ fan-out entre réplicas — os três fora do OLTP por padrão de acesso. **Guardrail "nada durável"** (degrada gracioso, flush nunca perde dado; fila/lock/estado durável seguem no Postgres dos Eixos 2-3). **Backplane no Valkey, não `LISTEN/NOTIFY`** (quebra sob pooler em modo transação, limite 8KB, sem persistência). Atrás de porta → managed-BR ↔ self-hosted é swap. **Seção alterada:** §22.9 (Eixo 4 + pauta); resolve o item de revogação imediata de token de §22.5.4. **Não alteradas:** §22.1–§22.8 e demais. |
| 1.21 | 20/06/2026 | Fechado o **Eixo 5 — compute/deploy do monólito + IA** do chat de stack (§22.9). **Kubernetes self-managed, zero solução de vendor de cloud** (Emilio, 20/06 — leitura estrita do provider-neutral do Eixo 1: cloud-BR é só IaaS alugado, tudo por cima open-source self-managed → cloud→colo vira lift-and-shift). **Cinco decisões:** (1) **distro Talos ou k3s em prod** (família lean: Talos imutável/hardened colo-ideal, k3s leve; descartados managed EKS/GKE/AKS e distro pesada RKE2), **`kind` no dev local**; (2) **4 workloads/namespace** — monólito core (HA atrás de ingress open-source Traefik/nginx), satélite IA (§22.3.4), worker-pools (Eixo 3), scheduler singleton (Eixo 3); (3) **stateful self-managed** — **Postgres via operador `CloudNativePG`** (failover/PITR/backup-pro-MinIO/upgrade), **Valkey** self-managed, **MinIO** self-hosted; (4) **deploy SSE-safe sob SLA de sessão** (§16.10) — graceful drain + rolling/blue-green + **gate de janela de deploy** que evita sessão ao vivo (scheduler Eixo 3); (5) **GPU fora da fundação** (cluster V1 CPU-only; node pool GPU + sizing no Eixo 10). **Reconcilia Eixos 2 e 4:** o hosting "managed RDS/ElastiCache" da V1 **cai** → self-managed (CloudNativePG; Valkey in-cluster); as decisões de Postgres vanilla + Valkey escopado ficam intactas. **Custo assumido:** ops de stateful self-managed sob SLA entra no dimensionamento de time (§18). **Seções alteradas:** §22.9 (Eixo 5 + reconciliação dos parágrafos dos Eixos 2/4 + pauta). **Não alteradas:** §22.1–§22.8 e demais. |
| 1.22 | 20/06/2026 | Fechado o **Eixo 6 — IdP/autenticação** do chat de stack (§22.9). **Keycloak self-managed** como provedor de identidade (Emilio, 20/06) — resolve o "provedor de IdP concreto" deferido em §22.5.4; Cognito/Auth0 caem pela regra "zero vendor de cloud" (Eixo 5), "próprio" descartado (auth é risco demais pra rolar à mão). **Três decisões:** (1) **Keycloak faz authN, app faz authZ** — federação OIDC (gov.br como IdP externo), WebAuthn/passkey (primário, v1.17), TOTP, step-up nativos; autorização segue in-app (§22.5.3, sem PDP externo); subsume a lib WebAuthn server-side de §22.5.4; (2) **topologia** — instância Keycloak **fisicamente separada pro admin interno** (§22.5), usuários-fim na instância principal com escopo de tenant no app via `ente_id` (não realm-por-ente, 1.500 realms insustentável); (3) **integra** — revogação imediata via denylist no Valkey (Eixo 4), Keycloak persiste em Postgres (substrato CloudNativePG do Eixo 5), ICP-Brasil fica fora da auth (assinatura app-level, §22.5). **Custo:** serviço Java + store próprio + impedância c/ Clojure (trade aceito). **Seção alterada:** §22.9 (Eixo 6 + pauta). **Não alteradas:** §22.1–§22.8 e demais. |
| 1.23 | 20/06/2026 | Fechado o **Eixo 7 — libs web Clojure** do chat de stack (§22.9). Stack idiomática **data-driven** com **Pedestal** na camada HTTP (Emilio, 20/06 — escolhido sobre Ring/Reitit pelo **SSE async first-class** (§22.6), **interceptors-as-data** (aderente ao ethos rule-as-data) e **pedigree Nubank** (coerente c/ a tese de contratação Clojure); trade de hiring-nicho + curva de interceptors aceito; o argumento pró-Ring de Loom/virtual-threads foi pesado e perdeu pro async-first-class). **Resto da stack (compõe com Pedestal):** Malli (schema/validação data-driven), next.jdbc + HikariCP + HoneySQL (SQL-como-dado, Postgres Eixo 2), Jsonista (JSON), Integrant (lifecycle — service Pedestal/DB/Valkey/cliente Keycloak/worker-pools como componentes); Pedestal sobre Jetty, route-table de dado, Malli como interceptor de coerção, vthreads p/ trabalho bloqueante. **Seção alterada:** §22.9 (Eixo 7 + pauta). **Não alteradas:** §22.1–§22.8 e demais. |
| 1.24 | 20/06/2026 | Fechado o **Eixo 8 — frontend web** do chat de stack (§22.9). **TypeScript + React (Next.js), self-hosted** (Emilio, 20/06) — único eixo que rompe a coerência Clojure de propósito, pela lente de **risco de talento** (frontend é onde se alarga o pool React-abundante; o nicho fica no backend, onde o motor justifica). **Decisões:** (1) **Next self-hosted no cluster k8s, NÃO Vercel** (coerente c/ "zero vendor de cloud" do Eixo 5); SSR/SSG resolve SEO+a11y do portal cidadão; (2) **três superfícies, um framework** — portal (SSR/SSG), app interno, painel de sessão ao vivo (consome SSE do Pedestal via `EventSource`); design system (Lexend, 7 superfícies) em React; (3) **contrato tipado via `Malli→TS`** (schemas Malli do backend geram tipos TS — recupera o schema-shared sem CLJS). **Trade:** 2ª língua/runtime aceito por largura-de-hiring + SEO + ecossistema UX; **CLJS descartado** (dobraria o nicho onde não precisa). **Seção alterada:** §22.9 (Eixo 8 + pauta). **Não alteradas:** §22.1–§22.8 e demais. |
| 1.25 | 20/06/2026 | **Refinamento do Eixo 7 (§22.9) — libs de backend** (decisões Emilio, 20/06, auditando lib a lib). **Gestão de componentes/DI: Component (Stuart Sierra) substitui Integrant** — grafo explícito em código (records+protocols) no lugar de config-como-dado; aceita-se perder o "system-as-data" pelo modelo clássico explícito. **Banco esclarecido em três camadas não-concorrentes:** **HoneySQL** (constrói SQL como dado) → **next.jdbc** (executa → mapas) → **HikariCP** (pool); **HugSQL descartado** (over-engineering — HoneySQL cobre 100% incl. `FOR UPDATE`/`ON CONFLICT…RETURNING`/CTE; raw next.jdbc é o escape hatch; HugSQL só valeria com DBA dono de `.sql` + SQL analítico pesado). **+Migratus** (migrations). **Malli mantido** (schema-as-data; ganha de clojure.spec por erro-UX + coerção + geração de tipos TS). Só o **datasource Hikari** (+ Valkey/Keycloak/Pedestal/worker-pools) é componente stateful; next.jdbc/HoneySQL/Malli são libs puras. **Seção alterada:** §22.9 (Eixo 7, ponto 2). **Não alteradas:** §22.1–§22.8 e demais. |
| 1.26 | 20/06/2026 | Fechado o **Eixo 9 — mobile** do chat de stack (§22.9). **PWA-first na V1** (Emilio, 20/06): o frontend React/Next do Eixo 8 é responsivo + instalável (PWA) com Web Push (iOS 16.4+ / Android) — **sem app nativo separado na V1**, cobre as três superfícies no mobile via web; zero codebase nativo no §18 apertado, alinhado a "escopo diferido por default" (§15). **App nativo deferido; direção pré-alinhada = React Native + Expo** (reusa o skillset React do Eixo 8 + tipos Malli→TS) quando o vereador mobile-primário (§16.7) justificar pós-V1 — registrado, não pré-construído; Flutter/native descartados (3ª língua / 2× trabalho). **Seção alterada:** §22.9 (Eixo 9 + pauta). **Não alteradas:** §22.1–§22.8 e demais. |
| 1.27 | 20/06/2026 | Fechado o **Eixo 10 — LLM/IA infra** do chat de stack (§22.9) — **último eixo; a §22.9 fica completa (10/10).** Solução **híbrida** (Emilio, 20/06), resolvendo a tensão zero-vendor (Eixo 5) × qualidade de fronteira do copiloto (Aposta 1). **Dois baldes:** (1) **self-host** (honra zero-vendor) — **ASR Whisper-class em GPU própria** (resolve o node-pool de GPU deferido no Eixo 5; áudio nunca sai) + **embeddings open-source self-host + `pgvector`** (resolve o pgvector deferido dos Eixos 2/4); (2) **frontier LLM** (copiloto + rascunho ata-IA) via **API gerenciada atrás da porta de inferência, vendor-agnóstica** — qualquer vendor de LLM (Claude/Maritaca/etc.) é config trocável, não compromisso; swap de provedor ou virar self-host = detalhe de deploy. **Governança de dado:** input do copiloto é majoritariamente texto legislativo público; pessoal/sessão-fechada filtrado antes da porta. **Supera o placeholder "Bedrock" do Eixo 1 e resolve §22.8 item 1** (LLM/soberania parqueado). Espírito do zero-vendor preservado pela porta + self-host do grosso; letra com uma exceção estreita/consciente/swappable. **Seções alteradas:** §22.9 (Eixo 10 + tidy de coerência no parágrafo de IA do Eixo 1) e **§22.8** (item 1 — LLM/soberania — marcado resolvido pelo Eixo 10; §22.8 fica sem itens parqueados); chat de stack concluído. **Não alteradas:** §22.1–§22.7 e demais. |
