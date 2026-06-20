# Próxima sessão — §22.7 Eixo C: templates TCE-CE como stress-test da DSL

Brief para a próxima sessão de discovery. Pré-requisito: ter fechado o descompasso do Eixo A
(consolidar `docs/02` no documento-mestre + bump v1.9) antes de abrir este eixo. Ver `docs/00-estado-e-roadmap.md`.

---

## 1. Objetivo do Eixo C

Pegar **requisitos reais de compliance do TCE-CE** e **expressá-los como templates de regra
na DSL fechada no Eixo A**, com um propósito claro: **descobrir lacunas**. O Eixo C é um
**stress-test**, não uma entrega de produto — o sucesso é validar (ou refutar) que o
vocabulário da DSL aguenta carga real **antes** de cravar o schema das tabelas (Eixo B).

Resultados possíveis e o que cada um significa:

- **A DSL expressa tudo** → o vocabulário está validado; segue para o Eixo B (schema) com
  fundação sólida.
- **A DSL não expressa algo** → lacuna identificada; **volta ao Eixo A** para corrigir o
  vocabulário (custo baixo agora, custo alto se descoberto depois do schema).

---

## 2. Por que C antes de B (a inversão deliberada)

Decisão registrada do Emilio: **inverter Eixo C à frente do Eixo B**. A lógica é
custo-de-erro. Modelar o schema das tabelas de template/regra (Eixo B) sobre uma DSL ainda
não validada contra requisitos reais é construir sobre fundação não testada. O stress-test
de templates reais é barato e revela cedo se a forma da DSL está certa. Schema vem depois,
sobre o que sobreviveu ao stress-test.

---

## 3. Matéria-prima do stress-test: o que o TCE-CE exige da parte legislativa

O documento-mestre já registra o **recorte correto** do que produzimos para o TCE-CE — não é
o universo contábil/administrativo (isso é delegado ao sistema da câmara, §17), mas os
**dados e atos da parte legislativa**. Candidatos a virar templates de regra no stress-test
(confirmar o conjunto real com material do TCE-CE / especialista em regimento):

- **Atos legislativos** (portarias, resoluções legislativas, decretos legislativos) —
  produção, numeração, publicação obrigatória.
- **Prazos de envio ao TCE-CE** — janelas regulatórias de submissão (candidato natural à
  generalização do padrão "prazo de domínio" de §22.4.3 disciplina 6, hoje em
  `proposicao_prazo_ativo`, generalizável para `prazo_dominio_ativo`).
- **Diárias de vereadores** — dado legislativo consumido pela folha existente (§17).
- **Transparência obrigatória** — o que a LAI/portal precisa expor e em que prazo.
- **Regras de quórum/votação por matéria** — já decididas como configuração no motor (§22.6);
  conferir se entram aqui como regras de compliance ou se ficam no envelope de plenário.

> ⚠️ O conteúdo regulatório concreto do TCE-CE é **domínio**, não engenharia. O Claude não
> deve inventar os requisitos. Trazer o material real (instruções normativas do TCE-CE,
> layouts de envio, prazos) e, idealmente, validar com o especialista em regimento. O que o
> discovery faz é **modelar a forma**: dado o requisito real X, ele se expressa na DSL? como?

---

## 4. Como rodar o eixo (método)

Seguindo o método eixo-a-eixo de `docs/01-metodologia.md`, mas com a particularidade de ser
um stress-test:

1. **Selecionar um pequeno conjunto de requisitos reais e diversos do TCE-CE** — escolher
   propositalmente casos que exercitem partes diferentes da DSL (um de prazo, um de
   produção/numeração de ato, um de transparência condicional, um de quórum, etc.).
2. **Expressar cada um como template de regra** no envelope de **compliance** (item 1.7 de
   `docs/02`). Escrever o template na DSL, de verdade.
3. **A cada template, anotar o que a DSL precisou** — que tipos, operadores, builtins e
   funções de relação foram exigidos. Marcar onde **faltou** vocabulário.
4. **Classificar as lacunas:** (a) falta builtin → adiciona builtin; (b) falta função de
   relação → o contexto dono expõe; (c) falta expressividade no núcleo → decisão séria, volta
   ao Eixo A; (d) o requisito não é regra de compliance e sim outra coisa → reclassifica.
5. **Fechar o eixo** com veredito: DSL validada (segue para B) ou lista de correções de
   vocabulário (volta a A).

**Onde o Claude Code agrega aqui:** dá para **prototipar um avaliador mínimo da DSL** (parser
+ type-checker do save time + execução das expressões) e **rodar os templates-exemplo contra
ele**. Isso transforma "acho que a DSL expressa" em "expressa, testei". É opcional, mas é o
tipo de coisa que o Claude Code faz bem e que valida a forma com mais rigor que a conversa pura.

---

## 5. Critério de pronto do Eixo C

- Um conjunto representativo de requisitos reais do TCE-CE foi expresso (ou se mostrou
  inexprimível) na DSL.
- Toda lacuna encontrada foi classificada e roteada (corrige DSL / expõe função / reclassifica).
- Veredito explícito: **vocabulário validado → abre Eixo B (schema)**, ou **vocabulário a
  corrigir → reabre pontos do Eixo A**.
- Consolidação no documento-mestre como §22.7 (Eixo C) + bump de versão.

---

## 6. Prompt de abertura sugerido para a sessão

> *"Documento-mestre em dia (Eixo A de §22.7 consolidado, v1.9). Vamos para o **Eixo C de
> §22.7: stress-test da DSL de compliance com templates reais do TCE-CE**, deliberadamente
> antes do Eixo B (schema). Objetivo é descobrir lacunas no vocabulário fechado no Eixo A
> antes de cravar schema. Tenho [material do TCE-CE / lista de requisitos] — vamos expressar
> cada requisito como template de regra na DSL e anotar onde falta vocabulário. Se ajudar,
> prototipa um avaliador mínimo da DSL para rodar os templates contra ele."*

(Ajustar o trecho entre colchetes ao material que você tiver em mãos na hora.)
