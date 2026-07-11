"use client";

// SeletorModelo — passo 1 de 3 da aba "Gerar documento" (Onda B Slice 6). Porte de expediente.html:340-374
// (`.modelos`/`.modelo`, toggle single-select via `aria-pressed`). Um botão por DocumentoModeloOut REAL
// (GET /legislativo/documento-modelos) — o mockup mostra um botão por TIPO fixo (Ofício/Certidão/
// Requerimento/Convite), mas o backend modela por MODELO nomeado (`chave`/`nome`), não por tipo; um tenant
// pode ter 0, 1 ou vários modelos ativos do mesmo tipo. "Mala-direta" é a ÚNICA exceção do mockup que
// sobrevive como afordance ESTÁTICA sempre desabilitada — não há suporte a múltiplos destinatários nesta
// fatia (dados = um mapa chave->valor único por documento), então o botão nunca fica clicável,
// independente do que o backend devolver.

import type { DocumentoModeloOut } from "@/lib/contrato-legislativo.gen";

const ICONE_POR_TIPO: Record<string, string> = {
  oficio: "M4 2h5l3 3v9H4z M9 2v3h3M6 9h4M6 11.5h4",
  certidao: "M2.5 3h11v10H2.5z M5 6.5h6M5 9h4",
  requerimento_administrativo: "M3 2.5h10v11l-5-2.5-5 2.5z",
  convite: "M2.5 3.5h11v8H2.5z M3 4.5l5 3.5 5-3.5",
  mala_direta: "M5 5a2 2 0 1 0 4 0a2 2 0 1 0-4 0 M11 11a2 2 0 1 0 4 0a2 2 0 1 0-4 0 M7 5h5M4 7v5h5",
  outro: "M4 2h9v11H4z M9 2v3h3M6 9h4M6 11.5h3",
};

function iconePara(tipo: string): string {
  return ICONE_POR_TIPO[tipo] ?? ICONE_POR_TIPO.outro;
}

export function SeletorModelo({
  modelos,
  selecionadoId,
  aoSelecionar,
  desabilitado,
}: {
  modelos: DocumentoModeloOut[];
  selecionadoId: string | null;
  aoSelecionar: (id: string) => void;
  desabilitado?: boolean;
}) {
  return (
    <div className="modelos" role="group" aria-label="Tipo de documento">
      {modelos.map((m) => (
        <button
          key={m.id}
          type="button"
          className="modelo"
          aria-pressed={m.id === selecionadoId}
          disabled={desabilitado}
          onClick={() => aoSelecionar(m.id)}
        >
          <span className="ico" aria-hidden="true">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4">
              <path d={iconePara(m.tipoDocumento)} strokeLinejoin="round" strokeLinecap="round" />
            </svg>
          </span>
          {m.nome}
          <span className="marca-sel" aria-hidden="true">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8">
              <path d="M3 8.5l3 3 7-8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </span>
        </button>
      ))}
      <button
        type="button"
        className="modelo larga"
        aria-pressed="false"
        disabled
        title="Mala-direta ainda não é suportada nesta fatia — exige vários destinatários do cadastro por documento."
      >
        <span className="ico" aria-hidden="true">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.3">
            <path d={ICONE_POR_TIPO.mala_direta} strokeLinecap="round" />
          </svg>
        </span>
        Mala-direta <span className="modelo-desc">— um ofício para vários destinatários (ainda não suportado)</span>
      </button>
    </div>
  );
}
