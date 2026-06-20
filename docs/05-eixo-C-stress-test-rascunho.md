# §22.7 — Eixo C (stress-test da DSL de compliance) · RASCUNHO DE TRABALHO

> **Status: ✅ CONSOLIDADO em §22.7.5 do documento-mestre (v1.10, 20/06/2026).** Aberto em
> 20/06/2026. Artefato de trabalho do Eixo C, no mesmo papel que `docs/02` teve para o Eixo A.
> O veredito (§8) e o vocabulário derivado viraram texto canônico em **§22.7.5**; este rascunho
> fica como registro de origem (templates escritos, fontes, classificação completa). **Próximo:
> Eixo B (schema) sobre o vocabulário validado.**
>
> **Pré-requisito cumprido:** Eixo A consolidado em §22.7 (v1.9). Ver `docs/00` e `docs/03` (brief).

---

## 0. Objetivo (do brief `docs/03`)

Pegar **requisitos reais do TCE-CE da parte legislativa** e tentar expressá-los como **templates
de regra no envelope de _compliance_** da DSL fechada no Eixo A, com um propósito: **descobrir
lacunas no vocabulário**. É **stress-test**, não entrega de produto. Sucesso = validar (ou
refutar) que o vocabulário aguenta carga real **antes** de cravar o schema (Eixo B).

- **DSL expressa tudo** → vocabulário validado → segue para o Eixo B com fundação sólida.
- **DSL não expressa algo** → lacuna identificada → volta ao Eixo A (barato agora, caro depois do schema).

---

## 1. A observação que define a execução — papel DUPLO do Eixo C

A §22.7.4 deixou o **detalhe granular** da DSL (tipos, operadores, builtins, schema dos
envelopes) **parqueado** como "a transcrever da sessão de origem" — **não consolidado, não
inventado**. Isso transforma o Eixo C em mais do que um teste:

> O Eixo C não só **testa** o vocabulário do Eixo A — ele **reconstrói por engenharia reversa**
> o que esse vocabulário precisa conter. Cada requisito real do TCE-CE que se tenta expressar
> **força a aparecer** o tipo, operador, builtin e função de relação que faltam. A saída não é
> "achei N lacunas" — é a **lista granular da §22.7.4 derivada de carga real**, em vez de
> transcrita de memória. **Foi exatamente o que aconteceu** (ver §7/§8).

---

## 2. Método (brief `docs/03` §4 + metodologia `docs/01`)

1. Selecionar conjunto **pequeno e diverso** de requisitos reais — casos que exercitem partes
   diferentes da DSL (prazo, ato, transparência condicional, quórum). ✅ Feito (§3, §6).
2. Expressar cada um como template no **envelope de compliance**, **de verdade**. ✅ §7.
3. A cada template, **anotar o que a DSL precisou** e onde **faltou**. ✅ §7 (tabela por template).
4. **Classificar a lacuna:** (a) builtin; (b) função de relação; (c) expressividade no núcleo →
   volta ao Eixo A; (d) não é regra de compliance → reclassifica. ✅ §8.
5. **Fechar** com veredito. ✅ §8.

**Avaliador mínimo da DSL (brief §4, opcional):** **[REC] adiar para o Eixo B.** Escrever os 4
templates à mão já demonstrou expressividade/lacuna; um parser+type-checker executável agrega
mais quando houver schema real (Eixo B), não agora — e custa tokens sem mudar o veredito de
forma. Decisão revisável.

---

## 3. Conjunto de stress mirado + o que a previsão acertou

| Requisito (parte legislativa) | Parte da DSL estressada | Lacuna prevista | Veredito (§7/§8) |
|---|---|---|---|
| **Prazo de remessa ao TCE-CE** | builtins data/prazo + `prazo_dominio_ativo` | data-corrente, dia-útil, "janela de prazo" | ✅ confirmado + prazo é **multi-fonte** (deslizante por Ofício Circular) |
| **Produção/numeração/publicação de ato** | funções de relação + envelope | função "ato publicado em ≤N dias" | ✅ função sim; **numeração reclassificada (tipo d)**; **domínio = regimento, não TCE** |
| **Transparência condicional** | lógica condicional + estado de publicação | funções de relação Transparência; instante/duração | ✅ confirmado; **`aplica_quando` validado** por dispensa ≤10k hab |
| **Quórum/maioria por matéria** | agregadores de plenário | força decisão de envelope | ✅ **resolvido: NÃO é compliance — é guard de plenário (§8 S4)** |

---

## 4. Pendência parqueada que este eixo resolve de quebra

§22.7.4: *"Confirmar se as regras de §22.6 (quórum, votação por matéria, tempos de tribuna) usam o
envelope de tramitação, o de compliance, ou ganham tratamento próprio."* → **RESOLVIDO em §8 (S4):
usam o envelope de guard de tramitação/plenário, não o de compliance.** O núcleo (tipos,
operadores, `arredonda_cima`) é compartilhado; o **envelope** é o de guard.

---

## 5. Envelope de compliance — v0 de trabalho (resultado do stress em §8)

Hipótese de trabalho derivada das decisões estruturais do Eixo A (forma A2). **O stress-test em
§7 confirmou a forma e refinou 3 campos** (ver §8): `dominio` virou taxonomia em camadas, `prazo`
é expressão multi-fonte, `severidade` discrimina peso regulatório real.

```yaml
# ENVELOPE DE COMPLIANCE v0 — hipótese de trabalho do Eixo C
template: <id_estavel>
contexto: compliance
dominio: <regime>                  # REFINADO (§8 S2): federal | tce_estadual | regimento_tenant
descricao: <texto humano>
parametros: { <nome>: <Tipo> }     # entidade(s) de domínio sobre as quais a regra fala
aplica_quando: <expr booleana>     # gatilho condicional (validado: populacao(ente) > 10000)
exige: <expr booleana>             # o que precisa ser verdade para estar conforme
prazo:                             # opcional — ausente = obrigação permanente/contínua
  janela: <expr de prazo>          # REFINADO (§8 S3): multi-fonte (calendário de domínio | evento+dia-útil | param tenant)
  a_partir_de: <expr de instante>
severidade: bloqueante | aviso     # bloqueante = pode custar janela/transferência (referente real, §8)
referencia_normativa: <citação>
```

---

## 6. Requisitos reais (sourced) — VARREDURA CONCLUÍDA (20/06/2026)

4 agentes, disciplina `[FATO]`/`[INF]`/`[GAP]`. Condensado para o que alimenta template. Fontes
completas nos relatórios de origem (transcritos no histórico da sessão).

### 6.1 Remessa ao TCE-CE (pesquisa 1)

| Req | Periodicidade | Prazo | Origem | Canal | Norma | Sev |
|---|---|---|---|---|---|---|
| SIM mensal (balancete+folha) | mensal | dia 30 do mês seguinte `[INF média; GAP-texto]` | fim da competência | SIMWeb | IN 04/2019 + Lei 12.160/1993 art.40§3º | bloqueante |
| RREO | bimestral (6×) | +30 dias `[FATO]` | fim do bimestre | Siconfi/STN | LRF art.52 | bloqueante |
| RGF | quadrimestral (3×) | +30 dias `[FATO]` | fim do quadrimestre | Siconfi/STN | LRF arts.54-55 | bloqueante |
| PCS anual (Mesa) | anual | 10/abr `[INF]` (ou 29/jun via Ágora `[GAP]`) | fim do exercício | Ágora/e-TCE | IN 01/2025 + Port. 51/2026 | bloqueante |
| Licitações/contratos | por evento | **1º dia útil** pós-publicação `[FATO]` | publicação edital/extrato | Portal Licitações | IN 04/2015-TCM art.5º | bloqueante |
| Atos de pessoal (admissão) | por evento | 10 dias `[FATO]` | edição do ato | e-TCE | IN 01/2024 + Lei 12.160 art.38§2º | bloqueante |

- **Sanção transversal `[FATO]`:** multa pessoal ao **Presidente** (Lei 12.509/1995 art.62, IX: 1–10% de teto-base R$30k corrigido) + contas irregulares → inelegibilidade (LC 64/90). Jurisdição: TCE-CE absorveu o TCM-CE (fusão 2017); normas "TCM" seguem vigentes.
- **Prazo é deslizante `[INF, load-bearing]`:** TCE-CE remaneja prazos por **Ofício Circular** (OC 13/2025, 05/2026, 16/2026). Prazo é **dado configurável com override por circular**, não constante.
- **`[GAP-7]`:** **não há IN do TCE-CE obrigando remessa proativa de leis/resoluções como arquivo** — obrigação é disponibilizar para fiscalização. O que a câmara remete é contábil/fiscal/pessoal/licitação, **não o ato legislativo em si.**

### 6.2 Atos legislativos: publicação (pesquisa 2)

- **Publicação = condição de eficácia `[FATO]`** (LINDB art.1º; CF art.37; LAI art.8º §2º obriga site p/ município >10k hab). Numeração **não** é exigida por norma federal cogente.
- **Prazo de publicação = 5 dias úteis** (RI Fortaleza art.45, I, "h") `[FATO]` — **é de Regimento Interno, varia por município, NÃO é federal** → `[GAP]` não generalizável.
- **Promulgação por inércia `[FATO]`:** 15 dias úteis (veto) → 48h (prefeito) → 48h (Presidente da Câmara) — LOM Fortaleza art.53.
- **Numeração `[FATO]`:** sem norma cogente; padrão empírico (série contínua p/ atos primários, anual p/ portarias); `numero` é **string** (SAPL); erro de numeração é sanável, **não nulidade**.
- **TCE-CE NÃO fiscaliza publicação de ato legislativo stricto sensu `[FATO]`** — só transparência fiscal (LC 131), licitações, pessoal, PCS. **Correção de premissa do brief:** e-Sfinge é do **TCE-SC**, não CE; no Ceará é o SIM.
- **Veículo oficial `[FATO]`:** config por tenant; site só vale p/ ato normativo se a lei municipal o instituir (TCE-SC Prejulgado 1315: internet é complementar, não substituta). Ceará: "Diário dos Municípios Cearenses"/APRECE-SIGPub `[GAP: IN reconhecendo APRECE não localizada]`.

### 6.3 Transparência ativa (pesquisa 3)

- **Tempo real `[FATO]` = 1º dia útil subsequente ao registro contábil** (Dec. 10.540/2020 art.2º IX; LRF art.48-A; LC 131/2009) — **não** "24h corridas".
- **Itens permanentes LAI art.8º §1º `[FATO]`:** estrutura, repasses, despesas, licitações/contratos, programas, FAQ — **dever contínuo, sem prazo**. Requisitos de portal (§3º): busca, exportação aberta, API.
- **Dispensa `[FATO]`:** município **≤ 10.000 hab** dispensado de publicar na internet (LAI art.8º §4º).
- **Subsídio nominal de vereador `[FATO]`** (CF art.39§6 + LAI art.8º§1º III + STF SS 3.902). Diárias: regime geral de tempo real, `[GAP]` sem prazo especial.
- **Sanção dominante `[FATO]`:** **bloqueio de transferências voluntárias** (LC 131 → LRF art.73-C, via CAUC) + multa pessoal. Num ciclo: 53 câmaras flagradas, 22 por atraso no TR.
- **Índices `[FATO]`:** ITM (TCE-CE, avalia portais, câmara separada de prefeitura) e PNTP/Selo (Atricon: Diamante/Ouro/Prata). São **medição/score**, não regra dura — Legislativo é o pior público de transparência do país (~55%). _Bônus comercial: alimentar o portal sobe de faixa no Selo → argumento p/ presidente/jurídico (`04`/`07`)._

### 6.4 Quórum e maiorias (pesquisa 4)

- **Três maiorias `[FATO]`:** simples (denominador = **presentes**, CF art.47); absoluta (denominador = **total de membros**, CF art.47/69); 2/3 (denominador = **membros**, CF art.29; DL 201/67 art.5 p/ cassação). Arredondamento **sempre p/ cima**.
- **Mapa matéria→maioria `[FATO]`:** lei ordinária = simples; lei complementar = absoluta; emenda à LOM = 2/3 em **2 turnos**; rejeição de veto = absoluta; cassação prefeito/vereador = 2/3.
- **Threshold é configurável `[FATO]`:** ADI 7205/STF trocou 2/3→3/5 p/ emenda à LO **do DF** (só DF; municípios seguem 2/3). Valida Invariante 4.
- **Três limiares sequenciais na sessão `[FATO]`:** instalação (1/3) → ordem do dia (absoluta presente) → votação (simples). Verificações distintas.

### 6.5 GAPs que importam para CONTEÚDO (não bloqueiam o veredito de FORMA)

Texto exato do prazo SIM (IN 04/2019, PDF escaneado); prazo definitivo PCS sob IN 01/2025;
jurisprudência do TCE-CE (atrás de login); APRECE como veículo oficial. **Nenhum bloqueia a
validação de forma** — a forma depende de o prazo ser *expressável*, não do valor exato. Importam
ao popular conteúdo no Eixo B; confirmar com especialista em regimento.

---

## 7. Os templates escritos + vocabulário que cada um FORÇOU

Forma A2: núcleo de expressão (booleano/valor, sem efeitos) + envelope de compliance. Identificadores
de função são resolvidos pelo **registry** (Eixo A dec. 3); o type-checker do **save time** (dec. 2)
valida assinaturas. Marca: `[a]` builtin · `[b]` função de relação (contexto dono) · `[c]` núcleo/tipo.

### T1 — Remessa mensal SIM (caso "prazo deslizante")

```yaml
template: remessa_mensal_sim
contexto: compliance
dominio: tce_estadual            # TCE-CE
parametros: { competencia: Competencia }
aplica_quando: verdadeiro                                  # toda câmara, toda competência
exige: remessa_enviada(ente, "SIM", competencia)
prazo:
  janela: prazo_vigente("TCE-CE", "SIM_mensal", competencia)   # calendário de domínio c/ override
  a_partir_de: fim_de(competencia)
severidade: bloqueante
referencia_normativa: "IN TCE-CE 04/2019; Lei 12.160/1993 art.40 §3º"
```

| Vocabulário exigido | Tipo | Estado |
|---|---|---|
| tipo `Competencia` (período mês/ano) | `[c]` | **novo** — não estava no núcleo provável |
| `fim_de(Competencia) -> Data` | `[a]` | novo |
| `prazo_vigente(dominio, tipo, competencia) -> Data` | `[a]`/`[c]` | novo — **lê calendário de domínio com override por Ofício Circular**; é a materialização do `prazo_dominio_ativo` (§22.4.3 disc.6) |
| `remessa_enviada(ente, sistema, competencia) -> bool` | `[b]` | novo — contexto dono "Remessa/Compliance-tracking" |
| `hoje()` / `agora()` (relógio injetado p/ comparar com a janela) | `[a]` | novo — determinístico, como instante em §22.6 |

### T2 — Transparência tempo real, condicional por porte (caso "`aplica_quando`")

```yaml
template: transparencia_tempo_real_despesa
contexto: compliance
dominio: federal                 # LC 131 — federal, medida pelo TCE-CE (não autoria do TCE)
parametros: { despesa: AtoDespesa }
aplica_quando: populacao(ente) > 10000                    # ≤10k dispensado de internet (LAI 8º§4º)
exige: publicada_no_portal(despesa)
prazo:
  janela: proximo_dia_util(data_registro_contabil(despesa))
  a_partir_de: data_registro_contabil(despesa)
severidade: bloqueante           # bloqueio de transferências (LRF 73-C)
referencia_normativa: "LC 131/2009; LRF art.48-A; Decreto 10.540/2020 art.2º IX"
```

| Vocabulário exigido | Tipo | Estado |
|---|---|---|
| `populacao(ente) -> Inteiro` | `[b]` | novo — contexto Cadastros/Ente; **prova que `aplica_quando` funciona** |
| operador `>` sobre inteiro; literal inteiro | `[c]` | núcleo (confirmado) |
| `proximo_dia_util(Data) -> Data` | `[a]` | novo — **depende de calendário de feriados (nacional+municipal) = mais dado de domínio** |
| `data_registro_contabil(despesa) -> Data` | `[b]` | novo — contexto Execução/Transparência |
| `publicada_no_portal(despesa) -> bool` | `[b]` | novo — contexto Transparência |
| tipo `AtoDespesa` (entidade de domínio como parâmetro) | `[c]` | confirma parâmetros tipados |

✅ **Encaixa limpo.** Valida o envelope para "prazo por evento + aplicabilidade condicional".

### T3 — Publicação de ato legislativo (caso "domínio ≠ TCE / param por tenant")

```yaml
template: publicacao_ato_legislativo
contexto: compliance
dominio: regimento_tenant        # <<< NEM TCE-CE, NEM federal: regimento da casa
parametros: { ato: AtoLegislativo }
aplica_quando: ato.tipo in { resolucao, decreto_legislativo, ato_mesa }
exige: publicado(ato)
prazo:
  janela: soma_dias_uteis(data_promulgacao(ato), parametro_tenant("prazo_publicacao_ato_dias"))
  a_partir_de: data_promulgacao(ato)
severidade: aviso                # publicação condiciona eficácia, não validade; TCE-CE não fiscaliza
referencia_normativa: "Regimento Interno (ex.: CMF art.45 I h = 5 dias úteis)"
```

| Vocabulário exigido | Tipo | Estado |
|---|---|---|
| `dominio: regimento_tenant` (3ª camada de domínio) | `[c]` | **descoberta — ver §8 S2** |
| `parametro_tenant(chave) -> valor` | `[a]`/`[c]` | **novo — acessa config por tenant, distinto de calendário de domínio** |
| operador `in` + literal de conjunto `{…}` | `[c]` | **novo operador + tipo conjunto/enum** |
| acesso a campo `ato.tipo` (record/registro) | `[c]` | confirma registros no sistema de tipos |
| `soma_dias_uteis(Data, Inteiro) -> Data` | `[a]` | novo |
| `publicado(ato)`, `data_promulgacao(ato)` | `[b]` | novo — contexto Atos Legislativos |

⚠️ **Encaixa, mas reclassifica o domínio** (regimento, não TCE) e a **severidade** (aviso). A
**numeração** que o brief §3 listava junto **não é regra de compliance** → reclassificada (tipo d, §8).

### T4 — Quórum/maioria por matéria (caso "força a decisão de envelope")

```yaml
# TENTATIVA deliberada de expressar quórum COMO compliance — esperado: NÃO encaixa
template: maioria_emenda_lom
contexto: compliance
dominio: ???                     # nem prazo, nem regime de remessa
parametros: { votacao: Votacao }
aplica_quando: votacao.materia == emenda_lom
exige: votos_favoraveis(votacao) >= arredonda_cima( fracao(2,3) * membros_da_casa(ente) )
prazo: ???                       # <<< QUÓRUM NÃO TEM PRAZO. o bloco temporal fica inerte.
severidade: bloqueante
```

| Vocabulário exigido | Tipo | Estado |
|---|---|---|
| `arredonda_cima(x) -> Inteiro` (ceil) | `[a]` | novo — **obrigatório** (erro "metade mais um" quebra em N ímpar) |
| `fracao(num, den)` + aritmética **exata/racional** | `[a]`/`[c]` | novo — `2/3 * N` **não pode ser float** (ceil de 0.666·N erra) |
| tipo `Maioria/Limiar` = `(fração, base ∈ {presentes, membros})` | `[c]` | **novo tipo composto — o denominador é a armadilha** |
| `membros_da_casa(ente)`, `votos_favoraveis(votacao)` | `[b]` | contexto Plenário (§22.6 — já tem `presentes_plenario`) |

❌ **NÃO encaixa no envelope de compliance** — e isso é o resultado, não a falha (ver §8 S4). O
núcleo (ceil, fração, tipo Maioria, funções de plenário) é **reaproveitável**; o **envelope**
errado é o de compliance. Quórum é **guard de ação em runtime**, não obrigação-com-prazo.

---

## 8. Lacunas classificadas + achados estruturais + VEREDITO

### 8.1 Lacunas de vocabulário (§22.7.4 — agora derivada de carga real, não de memória)

**`[a]` Builtins** (biblioteca da DSL — dec. 6):
- `hoje()` / `agora()` — relógio injetado, determinístico.
- `fim_de(Competencia) -> Data`.
- `proximo_dia_util(Data) -> Data` e `soma_dias_uteis(Data, Inteiro) -> Data` — **dependem de um calendário de feriados (nacional + municipal), que é dado de domínio.**
- `arredonda_cima(x) -> Inteiro` (ceil).
- `fracao(num, den)` com **aritmética exata** (não float).
- `prazo_vigente(dominio, tipo, chave) -> Data` — lê calendário de domínio com override (fronteira com `[c]`).
- `parametro_tenant(chave) -> valor` — lê config por tenant (fronteira com `[c]`).

**`[b]` Funções de relação** (expostas pelo contexto dono — dec. 3):
- Cadastros/Ente: `populacao(ente)`, `membros_da_casa(ente)`.
- Remessa-tracking: `remessa_enviada(ente, sistema, competencia)`.
- Transparência/Execução: `publicada_no_portal(despesa)`, `data_registro_contabil(despesa)`.
- Atos Legislativos: `publicado(ato)`, `data_promulgacao(ato)`.
- Plenário (§22.6): `votos_favoraveis(votacao)` (junto dos já existentes `presentes_plenario` etc.).

**`[c]` Núcleo / sistema de tipos** (dec. 4/5 — preenche o parqueado, **não reabre a forma A2**):
- Tipos: `Competencia` (período), `Data`/`Instante`/`Duracao` (temporais — confirmar contra o núcleo provável), `Maioria/Limiar` (composto com base de cálculo), `Conjunto`/enum, registros (`ato.tipo`).
- Operadores: `in` (pertinência a conjunto) — novo; comparações e lógicos confirmados.
- Aritmética exata/racional para frações (evita erro de arredondamento em maioria).

**`[d]` Reclassificações** (não são regra de compliance):
- **Numeração de atos** → invariante de integridade de dados (`unicidade(tipo, numero, ano, camara_id)`, `numero` = string). Vai para o **schema (Eixo B)**, não para o motor de regras.
- **Índices ITM / PNTP-Selo** → medição/score, não regra dura bloqueante. Se entrarem, é como conceito "índice" separado, não template de compliance.

### 8.2 Achados ESTRUTURAIS (maiores que vocabulário)

- **S1 — A semântica que DEFINE o envelope de compliance é "obrigação temporal":** um estado
  asserido que precisa valer **até um prazo** (ou **continuamente**, quando `prazo` é ausente).
  Distinta de **guard** (tramitação: "esta ação é válida agora?") e de **permissão** (autorização).
  Confirmada por T1–T3; refutada-por-tentativa em T4. **Implicação:** o motor de compliance tem
  comportamento de **monitoramento de prazo** (sabe *quando* a obrigação vence), não só avaliação
  booleana pontual. → **isso é comportamento de motor, candidato a um dos "+5 eixos", não vocabulário.**
- **S2 — `dominio` é taxonomia em CAMADAS, não "qual TCE":** apareceram **três regimes numa só
  UF** — `federal` (LC 131, LAI), `tce_estadual` (INs do TCE-CE), `regimento_tenant` (prazo de
  publicação da casa). O Invariante 4 ("outros 26 TCEs = dados") segue válido, mas o modelo de
  `dominio` é mais rico que "27 tribunais": é regime regulatório em camadas, com parâmetros tanto
  globais-de-domínio quanto **por tenant**.
- **S3 — `prazo` é expressão MULTI-FONTE:** resolve por (i) calendário de domínio com override por
  circular (`prazo_vigente`), (ii) evento + dia-útil (`proximo_dia_util`), (iii) param por tenant +
  dia-útil (`parametro_tenant` + `soma_dias_uteis`). Generaliza o `prazo_dominio_ativo` (§22.4.3
  disc.6) **e** exige dependência de calendário de feriados como dado.
- **S4 — RESOLVE a pendência parqueada de §22.7.4:** **quórum/votação/tempos de tribuna (§22.6)
  usam o envelope de GUARD de tramitação/plenário, NÃO o de compliance.** Núcleo compartilhado
  (forma A2 funcionando: um núcleo, múltiplos envelopes); envelopes distintos por semântica.

### 8.3 VEREDITO

✅ **A FORMA A2 SOBREVIVEU AO STRESS-TEST.** O envelope de compliance expressa as obrigações
reais de compliance temporal (T1–T3) de forma limpa. **Nenhuma decisão estrutural do Eixo A
precisa reabrir** — as descobertas **preenchem** a §22.7.4 (tipos, operadores, builtins, schema do
envelope) com vocabulário **justificado por requisito real**, que era o papel-duplo do eixo (§1).

**Não houve lacuna de tipo (c) que derrube a forma.** As adições ao núcleo são **extensões**
(novo tipo composto, operador `in`, aritmética exata), não contradições da gramática A2.

**Roteamento das descobertas:**
- Vocabulário (§8.1) → **alimenta o Eixo B** (schema das tabelas de template/regra) e a
  transcrição de §22.7.4.
- S1 (motor monitora prazo) → **abre/alimenta um dos "+5 eixos"** (comportamento do motor:
  avaliação/agendamento/auditoria), não o schema.
- S2/S3 (domínio em camadas, prazo multi-fonte) → **refinam o schema do envelope no Eixo B**.
- S4 → **consolida em §22.7.4** (quórum usa envelope de guard) — fecha a pendência.
- Reclassificações (§8.1 d) → numeração vai para o schema legislativo (§22.4/Eixo B); índices ficam fora do motor de regras.

**Próximo passo proposto:** consolidar §22.7 (Eixo C) no documento-mestre — veredito + vocabulário
derivado + S1–S4 + resolução da pendência — com **bump v1.10**; então **abrir o Eixo B** (schema)
sobre vocabulário validado. _Aguarda "Confirmo" (protocolo `docs/01`)._
