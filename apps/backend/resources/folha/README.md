# `resources/folha/` — a folha de estilo da Folha de Presença

`folha.css` é a **ÚNICA** fonte de estilo da Folha de Presença (Etapa 5, `sessoes`). O adapter HTML
(`sessoes.components.serializador-folha`) lê este arquivo do classpath e o inlina, literal, dentro de
um `<style>` no HTML canônico que produz — nunca `<link>`. O renderizador de PDF da Fatia 3 consome o
**mesmo arquivo**, sem edição.

## Por que este CSS não importa `produto/design-system/`

O design system (`chassi.css`, `tokens.css`, `componentes.css`) é escrito para telas de aplicativo
renderizadas por um navegador real: usa `flexbox`, `grid`, `var()` (custom properties) e
`color-mix()`. A Folha de Presença é renderizada em PDF por `openhtmltopdf`, um motor CSS 2.1 que
**não é um navegador** — não suporta nenhum dos quatro recursos acima, não roda JavaScript, e não
resolve `@import` nem `<link>` para folha externa alguma (o artefato tem de ser autocontido: um HTML
gerado em 2026 precisa renderizar igual em 2030, sem depender de rede nem de rota viva).

Importar o design system aqui produziria um HTML que passa no navegador do desenvolvedor e falha —
silenciosamente, sem erro, só com layout quebrado — no motor que realmente conta: o que gera o PDF que
vai para a Mesa, o jurídico e o arquivo da Câmara.

Por isso este CSS:

- não usa `flex` nem `grid` — layout é `block` / `inline-block` / `table` (e nunca `float`: float
  atravessando quebra de página em `openhtmltopdf` é fonte conhecida de deriva de layout);
- não usa `var()` nem `color-mix()` — toda cor é hex literal, repetida onde precisa;
- reusa os **valores** de tinta do design system (`#1B2A20`, `#586B5E`) porque são a mesma Casa, não
  os **mecanismos** dele.

Qualquer mudança visual da folha se faz **neste arquivo**, nunca portando uma classe do design system
para cá — o teste seria válido no navegador e mentiroso no PDF.

## Fontes

Duas famílias (serifa + mono), pilha de fallback genérico nesta fatia (`Georgia, "Times New Roman",
serif` / `"Courier New", monospace`). O embutimento em `@font-face` base64 é da Fatia 3 (PDF) — o
ponto exato de inserção está marcado em comentário no topo de `folha.css`.
