// Layout do App Shell PÚBLICO — Portal do Cidadão (Task 0.6, Fatia A2.0). Espelha
// app/(interno)/layout.tsx, mas SEM AuthProvider/guard: nenhuma rota sob (publico)/ é autenticada
// (Global Constraints do plano — "Só leitura pública"). O grupo de rotas (publico) não aparece na URL
// (convenção Next.js App Router).
//
// SEM <TemaProvider> aqui — o root app/layout.tsx JÁ provê um único <TemaProvider> para toda a
// árvore (review A2.0: dois providers aninhados duplicava o Context sem necessidade).
//
// `superficie-publica` é uma classe de <body> no design-system (chassi.css) — mas o App Router do
// Next.js tem UM <body> só, compartilhado por TODAS as rotas (definido em app/layout.tsx); um layout de
// grupo de rotas não pode trocar a classe do <body> raiz. Por isso o conteúdo é envolvido num
// <div className="superficie-publica"> — public.css espelha, para esse container, as regras que no
// chassi.css seriam `body.superficie-publica`.

import "./public.css";

export default function LayoutPublico({ children }: { children: React.ReactNode }) {
  return <div className="superficie-publica">{children}</div>;
}
