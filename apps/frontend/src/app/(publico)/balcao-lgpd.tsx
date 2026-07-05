"use client";

// BalcaoLgpd — o balcão LGPD + Encarregado/DPO (Task 2.2, Fatia A2.2). Porte de
// portal-cidadao.html:586-618. Os 5 "direitos" (acessar/corrigir/eliminar/portabilidade/revogar) são
// fluxos de ESCRITA que exigem identificação (gov.br) — fora do escopo "só leitura pública" desta fatia
// (Global Constraints) -> botões inertes + <EmBreve> honesto explicando o porquê, em vez de fingir que
// funcionam. O bloco do Encarregado/DPO é o único dado REAL desta seção (useEncarregado, Task 2.2) —
// degrada isolado (sem card fabricado) se o fetch falhar, nunca derruba o balcão inteiro.
//
// Prazo LGPD: a tela-fonte já evita cravar um número de dias (ao contrário do prazo da LAI, que É uma
// constante legal exata) — mantido honesto, sem inventar um total aqui também.

import { useEncarregado } from "@/lib/use-encarregado";
import { EmBreve } from "@/lib/em-breve";

const DIREITOS = [
  { rotulo: "Acessar meus dados" },
  { rotulo: "Corrigir um dado" },
  { rotulo: "Eliminar meus dados" },
  { rotulo: "Com quem foram compartilhados" },
  { rotulo: "Revogar um consentimento que dei", larga: true },
];

export function BalcaoLgpd({ ente }: { ente: string }) {
  const { encarregado, estado } = useEncarregado(ente);

  return (
    <article className="balcao balcao-lgpd" aria-labelledby="lgpd-titulo">
      <p className="eyebrow">LGPD · Lei Geral de Proteção de Dados</p>
      <h3 id="lgpd-titulo">Os seus dados pessoais</h3>
      <p className="lead">
        Aqui você acessa, corrige ou pede a eliminação dos <b>dados pessoais que a Câmara tem sobre
        você</b>.
      </p>

      <p className="fronteira">
        <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
          <circle cx="8" cy="8" r="6.5" fill="none" stroke="currentColor" strokeWidth="1.3" />
          <path d="M8 7.4v3.2M8 5.2h.01" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
        <span>
          Procura uma informação pública da Câmara — um contrato, um gasto, uma lei? Esse é o e-SIC, no
          balcão ao lado. Aqui é só sobre os <b>seus próprios dados</b>.
        </span>
      </p>

      <ul className="direitos" aria-describedby="lgpd-direitos-embreve">
        {DIREITOS.map((d) => (
          <li key={d.rotulo} className={d.larga ? "larga" : undefined}>
            <button className="direito-btn" type="button" disabled aria-disabled="true">
              {d.rotulo}
            </button>
          </li>
        ))}
      </ul>
      <div id="lgpd-direitos-embreve">
        <EmBreve
          titulo="Exercer um direito da LGPD"
          motivo="Pedir para acessar, corrigir, eliminar ou revogar um consentimento exige identificação formal (Entrar com gov.br) — chega numa fatia futura de autenticação."
        />
      </div>

      <p className="prazo-lgpd">
        <svg width="15" height="15" viewBox="0 0 15 15" aria-hidden="true">
          <circle cx="7.5" cy="7.5" r="6" fill="none" stroke="currentColor" strokeWidth="1.2" />
          <path d="M7.5 4v3.7l2.3 1.4" stroke="currentColor" strokeWidth="1.2" fill="none" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        A Câmara responde aos pedidos sobre os seus dados dentro do prazo legal.
      </p>

      {estado === "pronto" && encarregado && (
        <div className="encarregado">
          <span className="av" aria-hidden="true">
            {iniciais(encarregado.nome)}
          </span>
          <div className="quem">
            <span className="rotulo">{encarregado.rotulo}</span>
            <b className="nome">{encarregado.nome}</b>
            <a href={`mailto:${encarregado.email}`}>{encarregado.email}</a>
          </div>
        </div>
      )}
      {estado === "erro" && (
        <EmBreve
          titulo="Contato do Encarregado de Dados"
          motivo="Não foi possível carregar o contato do Encarregado (DPO) agora. Tente novamente em instantes."
        />
      )}
    </article>
  );
}

function iniciais(nome: string): string {
  const partes = nome.trim().split(/\s+/).filter(Boolean);
  if (partes.length === 0) return "?";
  const primeira = partes[0][0];
  const ultima = partes.length > 1 ? partes[partes.length - 1][0] : "";
  return (primeira + ultima).toUpperCase();
}
