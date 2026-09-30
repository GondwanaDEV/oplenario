"use client";

// "Designar relator" (ADR-0019, Eixo 6) — para o parecer de comissão que ainda não tem relator, na aba Pareceres da ficha.
// A secretaria registra a determinação do presidente da comissão escolhendo o vereador. Só a secretaria vê o botão.

import { useState } from "react";
import { useVereadores } from "@/lib/use-vereadores";
import { designarRelator } from "@/lib/use-comissoes";
import "./juridico-ficha.css";

function Formulario({ parecerId, comissao, token, aoDesignar, aoCancelar }: {
  parecerId: string;
  comissao: string;
  token: string | null;
  aoDesignar: () => void;
  aoCancelar: () => void;
}) {
  const vereadores = useVereadores(token);
  const [relatorId, setRelatorId] = useState("");
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  async function enviar() {
    if (!relatorId || enviando) return;
    setEnviando(true);
    setErro(null);
    const r = await designarRelator(token, parecerId, relatorId);
    setEnviando(false);
    if (r.ok) aoDesignar();
    else setErro(r.mensagem);
  }

  return (
    <div className="jf-relator-form">
      <label htmlFor={`jf-relator-${parecerId}`}>Relator de {comissao}</label>
      {vereadores.estado === "carregando" && <p role="status" className="jf-dica">Carregando os vereadores…</p>}
      {vereadores.estado === "erro" && <p role="alert" className="jf-erro">Não foi possível carregar os vereadores agora.</p>}
      {vereadores.estado === "pronto" && (
        <select id={`jf-relator-${parecerId}`} value={relatorId} onChange={(e) => setRelatorId(e.target.value)}>
          <option value="">Escolha o vereador…</option>
          {vereadores.dados.map((v) => (
            <option key={v.id} value={v.id}>{v.nomeParlamentar ?? v.nome}</option>
          ))}
        </select>
      )}
      {erro && <p role="alert" className="jf-erro">{erro}</p>}
      <div className="jf-acoes">
        <button type="button" className="btn btn-primaria btn-mini" disabled={!relatorId || enviando} onClick={enviar}>
          {enviando ? "Designando…" : "Designar"}
        </button>
        <button type="button" className="btn btn-fantasma btn-mini" disabled={enviando} onClick={aoCancelar}>Cancelar</button>
      </div>
    </div>
  );
}

export function DesignarRelator({ parecerId, comissao, token, aoDesignar }: {
  parecerId: string;
  comissao: string;
  token: string | null;
  aoDesignar: () => void;
}) {
  const [aberto, setAberto] = useState(false);
  if (!aberto) {
    return (
      <button type="button" className="btn btn-contorno btn-mini" aria-label={`Designar relator para ${comissao}`} onClick={() => setAberto(true)}>
        Designar relator
      </button>
    );
  }
  return (
    <Formulario parecerId={parecerId} comissao={comissao} token={token} aoCancelar={() => setAberto(false)}
      aoDesignar={() => { setAberto(false); aoDesignar(); }} />
  );
}
