# 17 · O rito do autógrafo — o que os regimentos realmente dizem

> **Pesquisa de domínio, 10/09/2026.** Motivada pelos achados T3-A1 e T3-A2 (ledger Fase 11): o sistema
> deixava um único papel `secretario` fabricar a aprovação e emitir o autógrafo, e o autógrafo levava o
> texto vigente na geração em vez do deliberado.
>
> **Todo dispositivo citado aqui foi extraído do documento original e conferido à mão.** A primeira
> extração automática do regimento de Mossoró devolveu os artigos 177, 178 e 179 com texto literal — os
> três eram **inventados**. Nenhuma citação abaixo veio de resumo automático.

---

## 1. Por que isto importa

O autógrafo é o documento que a Câmara envia ao Prefeito para sanção ou veto. É ato jurídico numerado.
Errar quem o emite, ou o que ele carrega, não é bug de dado — é fabricar um ato que não aconteceu, ou
mandar ao Executivo um texto que o plenário não leu.

A pergunta que abriu a pesquisa foi de processo, não de engenharia: **numa câmara real, quem encerra a
votação pode ser a mesma pessoa que emite o autógrafo?**

## 2. A resposta curta

**Não.** E o rito é mais repartido do que a pergunta supunha: são **três atos com donos distintos**.

| Ato | Dono, no regimento |
|---|---|
| Proclamar o resultado da votação | **Presidente** |
| Elaborar o autógrafo | **órgão administrativo** (em Fortaleza, a COGEL) |
| Assinar / encaminhar à sanção | **Presidente ou Mesa Diretora**, conforme a casa |

Hoje o sistema funde os três num único `POST` com papel `secretario`.

## 3. O que varia entre casas — e por que isso é config, não código

| Casa | O que destrava o autógrafo | Quem elabora | Quem assina/encaminha |
|---|---|---|---|
| **Fortaleza — Res. 1.670/2020** (vigente) | aprovação da **Redação Final em Plenário** | **COGEL** | Presidente ou Mesa, *"conforme o caso"* |
| **Fortaleza — Res. 1.589/2008** (anterior) | Redação Final votada **pela Comissão de Constituição e Justiça** | Coordenadoria-Geral Legislativa | Presidente |
| **Mossoró/RN** | aprovação do projeto | — | **Mesa, colegiadamente**, 10 dias úteis |
| **AL-CE — Res. 751/2022** | aprovação da redação final | Mesa expede | Mesa |

**A própria Fortaleza mudou o modelo entre 2008 e 2020** — a Redação Final saiu da comissão e voltou ao
Plenário. Uma casa só, dois modelos em doze anos. Isso encerra a discussão config-vs-código e confirma o
**Invariante 4**: o rito é dado do tenant, nunca branch no motor.

## 4. Os dispositivos, literais

### Fortaleza — Resolução nº 1.670/2020 (regimento vigente)

**Competências do Presidente**, alínea "i":

> *"anunciar a Ordem do Dia e submeter à discussão e à votação a matéria dela constante, bem como
> **proclamar o resultado das votações**"*

**Art. 180, §1º** — a origem do autógrafo:

> *"Aprovada a Redação Final, a matéria será enviada para a **Coordenadoria-Geral de Assuntos
> Legislativos (COGEL)** para elaboração dos autógrafos destinados à sanção do Prefeito ou à promulgação
> do Presidente ou da Mesa Diretora, conforme o caso."*

`autógrafo` aparece **uma única vez** em todo o regimento de 2020 — quem assina não está lá, está na Lei
Orgânica. **`[GAP]`: não consegui abrir a Lei Orgânica de Fortaleza (403 no leismunicipais).**

### Fortaleza — Resolução nº 1.589/2008 (anterior, para contraste)

**Competências do Presidente**, alínea "e": *"encaminhar projetos de lei à sanção, pelo chefe do Poder
Executivo"*.

**Art. 177** (red. da Res. 1.635/2014):

> *"As redações finais serão **votadas pela Comissão de Constituição, Justiça e Legislação
> Participativa**, sendo enviadas em seguida à Coordenadoria-Geral Legislativa para as providências de
> sanção e promulgação."*

### Mossoró/RN

**Art. 23, XXII** — atribuição da **Mesa**: *"assinar os autógrafos dos projetos de lei destinados á
sanção e promulgação pelo chefe do Executivo"*.
**Art. 23, §3º**: *"A recusa injustificada de assinatura dos autógrafos destinados à sanção, ensejará o
processo de destituição do membro faltoso."*
**Art. 24**: *"As decisões da Mesa serão tomadas de forma colegiada."*

**Art. 255**: autógrafo enviado ao Prefeito em **10 dias úteis**.
**§1º**: *"os autógrafos de projetos de lei, antes de serem remetidos ao Prefeito, serão registrados em
livro próprio e arquivados na Secretaria Legislativa, levando a **assinatura dos membros da Mesa**"*.
**§3º**: sem sanção em **15 dias úteis**, considera-se sancionado, e a promulgação pelo Presidente é
obrigatória em **48 horas**; se ele não o fizer, cabe ao Vice-Presidente em igual prazo.

**Art. 25, "m"** — competência do Presidente: *"anunciar o resultado da votação"*.
**Art. 249** — o contra-poder da votação simbólica:

> *"Se algum Vereador tiver dúvida quanto ao resultado da **votação simbólica, proclamada pelo
> Presidente**, poderá requerer verificação nominal de votação. §1º O requerimento de verificação nominal
> será de imediato e necessariamente atendido."*

### AL-CE — Resolução nº 751/2022 (estadual, modelo de muitas casas do CE)

**Art. 268, §1º** — o dispositivo mais importante desta pesquisa:

> *"Quando, após aprovação da redação final e **até a expedição do autógrafo**, se verificar inexatidão
> do texto, a **Mesa Diretora** procederá à respectiva correção, da qual **dará conhecimento ao
> Plenário**, não havendo impugnação, considerar-se-á aceita a correção; em caso contrário, proceder-se-á
> à discussão da impugnação para decisão final do Plenário."*

Mossoró tem cláusula equivalente. **É padrão entre casas.**

## 5. O que isto corrige no que já foi construído

### 5.1 A exclusão de `redacao_final` está ERRADA para o beachhead

`db/votacao.clj/aprovada-em-votacao?` só aceita `objeto_tipo = 'proposicao'`, e a docstring declarava a
exclusão de `redacao_final` como *"conservadora de propósito"*. **Não é.** Em Fortaleza, a aprovação da
Redação Final é exatamente o ato que destrava o autógrafo — a regra atual acerta em Mossoró e erra na
casa-alvo.

**Correção pendente:** aceitar aprovação de `proposicao` **ou** de `redacao_final` — a união dos dois
modelos, correta nas duas casas e ainda assim mais restritiva que o estado anterior à guarda.

### 5.2 O pin do texto votado é rígido demais — ledger `T3-A3`

O conserto do T3-A2 amarra o autógrafo à versão congelada na abertura da votação. Isso fecha a
fabricação, mas **bloqueia um ato que o regimento prevê**: a correção de inexatidão pela Mesa entre a
aprovação e a expedição (Art. 268 §1º da AL-CE, cláusula equivalente em Mossoró).

Não gera autógrafo errado — impede um fluxo real. O conserto certo **não é afrouxar o pin**: é tornar a
correção um ato auditável próprio (autoria da Mesa + ciência ao Plenário) e o autógrafo levar *texto
votado + correções registradas*. O domínio já tem onde: `logic/origens-versao` inclui `"redacao_final"`.

### 5.3 O A1 deixa de ser política de segurança e vira espelho do rito

A pergunta não é "que papel emite o autógrafo", é **"a operação é uma ou são duas"**. No regimento são
duas: elaborar (administrativo) e encaminhar à sanção (Presidente/Mesa). O sistema funde as duas.

A peça já existe e não precisa ser construída: o catálogo de fatos traz `é_presidente_da_mesa`,
`é_secretario_da_mesa` e `quem_exerce_presidencia`, com implementação real em
`cadastros/relacoes/cadastro.clj:58`; e `legislativo/logic.clj:92` já registra que essa política *"em F2
vira expressão da DSL avaliada pelo mesmo motor (disciplina 5)"*. O seam foi previsto na arquitetura.

### 5.4 A votação simbólica tem resposta regimental, não técnica

O achado T3-A1 nota que `simbolica` aceita o `resultado` vindo do corpo. O regimento não proíbe a
aclamação — garante o **direito de verificação nominal**, atendido *"de imediato e necessariamente"*
(Mossoró, Art. 249). A mitigação de produto é expor esse direito, não bloquear a modalidade.

## 6. `[GAP]` desta pesquisa

- **Lei Orgânica de Fortaleza** — 403 no leismunicipais; é onde deve estar quem assina o autógrafo na
  capital. É a lacuna mais relevante, porque Fortaleza é o beachhead.
- **Sobral, Caucaia, Juazeiro do Norte** — conexão recusada nos três domínios `*.ce.leg.br`. Sem amostra
  do interior do Ceará.
- **Prazos de sanção/veto por LOM** seguem `[GAP]` aberto do projeto (Mossoró: 15 dias úteis; Fortaleza
  não verificado).

## 7. Fontes

- [Regimento Interno · CM Fortaleza · Res. 1.670/2020](https://sapl.fortaleza.ce.leg.br/ta/3697/text)
- [Regimento Interno · CM Fortaleza · Res. 1.589/2008](https://sapl.fortaleza.ce.leg.br/ta/3015/text)
- [Regimento Interno · AL-CE · Res. 751/2022](https://www.al.ce.gov.br/index.php/download-file/278860)
- [Regimento Interno · CM Mossoró/RN](https://www.mossoro.rn.leg.br/legislacao/regimento-interno/REGIMENTO%20INTERNO.pdf)
