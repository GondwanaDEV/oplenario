# mobile — O Plenário (Flutter)

> **Esqueleto de intenção.** O scaffold real (`flutter create`) entra quando o track mobile começar.
> **Diferido** por enquanto: a V1 é **PWA-first** (o `frontend` Next responsivo + Web Push cobre o
> celular). O app nativo entra quando houver requisito de cliente validado (régua das 4 perguntas, §15).

## Stack

- **Flutter / Dart** — decisão do Daouda Traore (2026-06-27). **Substitui** a nota anterior do §22.9
  eixo 9 ("RN + Expo diferido"): o mobile nativo, quando vier, é Flutter. *(Atualizar §22.9 na SSOT.)*
- Consome a **mesma API** do backend (contrato Malli → tipos), os **mesmos eventos SSE** do painel ao
  vivo, e respeita a identidade visual da "República Luminosa" (`../../produto/design-system/`).

## Públicos prováveis do nativo (quando destravar)

- **App do vereador** (Aposta 2) — votar, ler pauta, assinar (2 toques), acompanhar tramitação. As
  telas-fonte já existem no design-system (`vereador-app.html`, `assinatura-2-toques.html`).
- Push nativo, biometria, offline-first — o que justifica sair do PWA.

## Por que diferido (não pré-construído)
§22.9 eixo 9 + §15: PWA-first entrega o celular sem o custo de uma segunda base de código nativa antes
de haver tração. O dir existe para fixar a forma do monorepo; o código vem demand-pulled.
