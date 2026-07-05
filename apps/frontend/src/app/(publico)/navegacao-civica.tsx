// NavegacaoCivica — a navegação cívica de fechamento (Task 2.3, Fatia A2.2). Porte de
// portal-cidadao.html:623-662 (6 cartões: Sessões, Transparência, Ouvidoria, Dados abertos, Agenda
// pública, Carta de Serviços).
//
// NENHUM dos 6 destinos tem rota pública própria ainda nesta fatia (Legislação/A2.4 é a próxima
// candidata; as demais nem isso) — em vez de linkar para uma rota que 404a silenciosamente, os 6 usam
// <EmBreve> (Global Constraints "sem dado falso" + "prefira em-breve honesto a link morto").
//
// DESVIO da tela-fonte: dois cartões (Ouvidoria, Agenda) tinham um `.prazo-tag` com um NÚMERO — o de
// Ouvidoria é uma constante LEGAL (Lei 13.460, resposta em até 30 dias, mesmo racional do prazo de 20
// dias da LAI no balcão e-SIC: regulação, não dado fabricado por instância) e entra em prosa no motivo;
// o de Agenda ("Próxima: 16ª Ordinária · qui, 14h") é uma INSTÂNCIA fabricada sem rota que a sustente —
// removido, sem substituto (nada a inventar). Nenhum componente aqui usa hook/estado -> Server
// Component (sem "use client"), mesmo quando composto dentro do page.tsx.

import { EmBreve } from "@/lib/em-breve";

const CARTOES = [
  {
    titulo: "Sessões",
    motivo: "Assistir ao vivo, rever com legendas e ler as atas oficiais chega numa fatia futura (rota pública de sessões ainda não existe).",
  },
  {
    titulo: "Transparência",
    motivo: "Salários, diárias, contratos e a execução do orçamento da Câmara chegam numa fatia futura de transparência fiscal.",
  },
  {
    titulo: "Ouvidoria",
    motivo: "Reclamação, denúncia, elogio ou sugestão, com resposta em até 30 dias (prorrogável, Lei 13.460) — chega numa fatia futura; ainda sem rota pública.",
  },
  {
    titulo: "Dados abertos",
    motivo: "Baixar os dados da Câmara em formato aberto chega numa fatia futura de dados abertos.",
  },
  {
    titulo: "Agenda pública",
    motivo: "As próximas sessões, audiências públicas e prazos abertos chegam numa fatia futura — ainda sem rota pública de agenda.",
  },
  {
    titulo: "Carta de Serviços",
    motivo: "O que a Câmara oferece ao cidadão, com requisitos, canais e prazos de cada serviço (Lei 13.460) chega numa fatia futura.",
  },
];

export function NavegacaoCivica() {
  return (
    <section className="secao" id="civico" aria-labelledby="civico-titulo">
      <div className="secao-cabeca">
        <h2 id="civico-titulo">Tudo o que a Câmara publica</h2>
      </div>
      <nav className="civico" aria-label="Serviços e transparência">
        {CARTOES.map((c) => (
          <EmBreve key={c.titulo} titulo={c.titulo} motivo={c.motivo} />
        ))}
      </nav>
    </section>
  );
}
