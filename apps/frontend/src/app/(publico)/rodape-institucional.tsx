// Rodapé institucional do Portal do Cidadão (Task 0.6, Fatia A2.0). Porte de
// portal-cidadao.html:666-701 — a marca "O Plenário" recua ao `rodape-fim` (a Casa lidera, a
// plataforma é rodapé; mesmo princípio white-label de BarraInstitucional).
//
// DESVIO da tela-fonte: o endereço físico e o contato do Encarregado/DPO no HTML original são dado de
// DEMONSTRAÇÃO (Fortaleza específico) — sem uma rota pública real para o endereço institucional nesta
// fatia (e o contato do DPO já tem rota própria, `/encarregado`, wired na Fatia A2.2), fabricar esses
// dados aqui violaria a Global Constraint "sem dado falso". A coluna de privacidade aponta para o
// balcão LGPD (Task 2.2) em vez de cravar um e-mail.
//
// DESCARTADO pela mesma regra (revisão da Onda E): o selo `Acessível · eMAG / WCAG AA` que vinha
// verbatim de portal-cidadao.html:673. É uma declaração pública de CONFORMIDADE, e conformidade é
// resultado de auditoria — não há no repositório laudo, auditoria nem gate automatizado de e-MAG/WCAG
// que a sustente (as ocorrências de "eMAG" são plano e requisito: docs/11, produto/13, produto/15,
// produto/16 — nenhuma é um resultado). Um cidadão com deficiência, ou um órgão de controle avaliando
// a Casa sob o Decreto 5.296, leria o selo como "esta superfície foi auditada e está conforme". O selo
// volta quando existir laudo ou gate citável — e aí dizendo o que de fato se mediu.

export function RodapeInstitucional({ nomeCasa }: { nomeCasa: string }) {
  const ano = new Date().getFullYear();
  return (
    <footer className="rodape">
      <div className="envelope">
        <div className="rodape-grade">
          <div className="rodape-casa">
            <b>{nomeCasa}</b>
          </div>
          <div>
            <h5>Cidadão</h5>
            <ul>
              <li>
                <a href="#destaque">Acompanhar proposições</a>
              </li>
              <li>
                <a href="#balcoes">Acesso à informação (e-SIC)</a>
              </li>
              <li>
                <a href="#balcoes">Ouvidoria e Carta de Serviços</a>
              </li>
              <li>
                <a href="#balcoes">Os meus dados pessoais (LGPD)</a>
              </li>
            </ul>
          </div>
          <div>
            <h5>Privacidade e dados</h5>
            <p style={{ fontSize: "var(--t-13)", color: "var(--texto-2)", margin: 0, lineHeight: 1.5 }}>
              O contato do Encarregado de Dados (DPO) está disponível no balcão{" "}
              <a href="#balcoes">Os meus dados pessoais (LGPD)</a>.
            </p>
          </div>
        </div>
        <div className="rodape-fim">
          <span>
            © {ano} {nomeCasa}. Informações de IA são assistivas e revisadas; o ato oficial sempre
            prevalece.
          </span>
          <span className="plataforma">
            <svg viewBox="0 0 40 40" aria-hidden="true">
              <circle cx="20" cy="20" r="19" fill="none" stroke="var(--linha)" />
              <path
                d="M9 26 A12 12 0 0 1 31 26"
                fill="none"
                stroke="var(--marca)"
                strokeWidth="2.4"
                strokeLinecap="round"
              />
              <path
                d="M13 26 A8 8 0 0 1 27 26"
                fill="none"
                stroke="var(--cobalto)"
                strokeWidth="2.4"
                strokeLinecap="round"
              />
              <rect x="18.4" y="10" width="3.2" height="6" rx="1.2" fill="var(--telha)" />
            </svg>
            Plataforma <b>O&nbsp;Plenário</b>
          </span>
        </div>
      </div>
    </footer>
  );
}
