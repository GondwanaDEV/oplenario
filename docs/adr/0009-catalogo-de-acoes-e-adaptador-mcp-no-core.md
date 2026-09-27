# ADR-0009 — Catálogo de ações no core: uma entrada por ação de domínio, `diplomat/catalogo.clj` e lint de rotas

- **Status:** Aceito · 2026-09-27
- **Decisor:** Daouda Traore (CTO) — execução da fatia **B.1** do plano da Track IA (`docs/26`), sob o "Confirmo"
  dado com o merge do PR #38.
- **Fonte canônica:** `docs/25` Eixo 2 (catálogo de ações, decisão B), 4.4 (classificação fail-closed), 5.1 (o
  satélite chama o core pelo mesmo servidor MCP, que mora no core como adaptador de entrada); §22.11 do
  doc-mestre; §22.10 (monólito modular); ADR-0001 (silhueta de módulo). Em conflito, a SSOT prevalece.
- **Aplica-se a:** `apps/backend` — `kernel/catalogo.clj` (formato), `<módulo>/diplomat/catalogo.clj` (entradas),
  `oplenario/catalogo.clj` (host: agregação e conjuntos por público), `resources/catalogo/` (lint).

## Contexto

As rotas HTTP do core têm forma de tela: a mesma operação aparece duplicada por papel e há rotas que só servem uma
tela. O Eixo 2 decidiu que o agente não vê rotas: vê **ações de domínio** declaradas num catálogo, cada uma com
descrição para o agente, esquemas, classe do ato e papéis. O Eixo 5.1 decidiu que esse catálogo é servido por um
**servidor MCP no core**, mais um adaptador de entrada ao lado do HTTP. Faltava decidir onde a entrada mora na
silhueta do ADR-0001, qual é o formato e como o CI impede que rota nova nasça fora do catálogo.

## Decisão

1. **Formato no kernel** (`oplenario.kernel.catalogo`). Uma entrada é um mapa fechado:
   `:nome` (snake_case, único, estável — é o nome da ferramenta), `:descricao` (português, escrita para o agente:
   quando usar e o que devolve; mínimo de 40 caracteres), `:classe` (`:leitura` | `:rascunho` | `:ato`),
   `:papeis` (conjunto não vazio), `:entrada`/`:saida` (Malli; a saída é **o mesmo `wire/out` da tela**), `:rotas`
   (route-names das rotas HTTP que são a mesma ação) e `:executar` (`(fn [deps ator entrada] -> saída | nil)`).
   `entrada` valida o formato na carga — catálogo mal formado não sobe.
2. **Classe ausente = `:ato`** (Eixo 4.4). Quem esquece de classificar nunca ganha uma leitura livre.
3. **A execução é uma só** (`kernel.catalogo/executar`): (a) o ator precisa ter **algum** papel da entrada
   (`:autorizacao/negado`); (b) a entrada chega como JSON e é decodificada e validada contra o esquema
   (`:validacao/invalido`, com o que faltou); (c) roda o `:executar`, que chama **o mesmo controller** da rota —
   o `policy.check` do recurso continua lá dentro; (d) a saída é conferida contra o esquema: divergência é bug de
   servidor e nunca chega ao agente. `nil` = não encontrado.
4. **Cada módulo declara as entradas que são suas em `diplomat/catalogo.clj`.** É uma peça nova da silhueta do
   ADR-0001, no `diplomat/` porque é borda (como `http/in.clj`): pode usar `controllers`, `adapters` e `wire` do
   próprio módulo e nada de outro módulo (o import-lint §22.10 já cobre).
5. **O host agrega** (`oplenario.catalogo`): junta as entradas dos módulos, valida nomes únicos e a conversão para
   JSON Schema na carga, e define os **conjuntos por público** (`:secretaria`, `:vereador`; `:cidadao` quando
   houver ferramenta para ele). Conjunto = o que um agente daquele público pode oferecer; expõe **menos** do que a
   permissão da pessoa (Eixo 3), e a ferramenta ainda exige o papel dela a cada chamada. Ferramenta fora do
   conjunto não existe para aquele público (`:validacao/ferramenta-desconhecida`).
6. **Lint de rotas no CI** (`catalogo_lint_test`). Toda rota montada por `rotas/montar` precisa estar (a) nas
   `:rotas` de uma entrada, (b) na **lista-base congelada** `resources/catalogo/rotas-base.edn` — as rotas que já
   existiam quando o catálogo nasceu, menos as cobertas — ou (c) em `resources/catalogo/fora-do-catalogo.edn`, com
   categoria (`:so-tela` ou `:servico`) e motivo. A lista-base **só diminui**: rota que ganha entrada sai dela,
   rota removida sai dela, e rota nova nunca entra nela. Entrada que aponta para rota inexistente também falha.
7. **Primeiras entradas** (migração do Eixo 2, começando pelas consultas do legislativo):
   `situacao_da_materia` e `tramitacao_da_materia` (legislativo) e `pauta_da_sessao` (sessões), todas
   `:leitura`, para `secretario` e `vereador`. A matéria é apontada pelo id **ou** pela identificação que uma
   pessoa fala (espécie + número + ano) — para isso o repositório do legislativo ganhou
   `buscar-proposicao-por-numero`. Sem sessão informada, a pauta é a da sessão em curso ou da próxima agendada,
   entre as que o ator pode ver.
8. **O servidor MCP e o laço do agente não entram aqui.** O adaptador MCP (B.3) só chama
   `oplenario.catalogo/ferramentas` e `executar!`; o ator que ele passa é o delegado da B.2.

## Enforcement

- `catalogo_lint_test` (rota nova sem entrada nem motivo = CI vermelho; lista-base só diminui; entrada aponta para
  rota que existe; exceção tem categoria e motivo).
- `kernel.catalogo/entrada` e `validar-catalogo!` na carga do namespace: formato, nome único e conversão para JSON
  Schema — o servidor não sobe com catálogo quebrado.
- `arquitetura_test` (import-lint §22.10) vale para `diplomat/catalogo.clj` como para qualquer arquivo do módulo.

## Consequências

- Uma ação tem **um** controller servindo a tela e o agente; a ferramenta nunca tem autorização própria mais larga
  que a rota.
- Toda rota nova passa a exigir uma decisão explícita: é ação do catálogo, ou é só-tela/serviço com motivo.
- As ~160 rotas da lista-base continuam fora do alcance do agente até alguém precisar delas — é a migração
  gradual do Eixo 2, não uma dívida escondida.
- O esquema de saída é o do `wire/out`, então mudança de contrato da tela muda a ferramenta junto (é o ponto).

## Alternativas descartadas

- **Uma ferramenta por rota, gerada** (Eixo 2 opção A): ~200 ferramentas com forma de tela e duplicadas por papel.
- **Registro central de entradas no host** (sem `diplomat/catalogo.clj`): o host passaria a conhecer controllers e
  wires de todos os módulos; a entrada fica longe do código que ela descreve.
- **Metadado na própria rota** (`:catalogo` no vetor da rota): amarra a ação à forma de tela e não cobre ação
  sem rota (as de agente que ainda não têm tela).
