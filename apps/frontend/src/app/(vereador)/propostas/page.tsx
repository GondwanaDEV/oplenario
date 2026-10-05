"use client";

// As propostas de ato que esperam o vereador (Faixa B / B.6, ADR-0012): o que o assistente preparou e ainda não
// foi feito. Cada uma abre a tela onde a pessoa lê, assina/confirma ou recusa.

import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { usePropostas } from "@/lib/use-propostas";
import { avisoDeOrigem, validadeDaProposta } from "@/lib/propostas-vista";
import { comToken } from "@/lib/nav";
import "./propostas.css";

export default function PaginaPropostas() {
  const { token } = useAuth();
  const estado = usePropostas(token);
  return (
    <div className="propostas">
      <h1 className="propostas-titulo">Propostas do assistente</h1>
      <p className="propostas-sub">
        O que a Clara preparou a seu pedido. Nada disso foi feito: cada uma espera você ler e decidir.
      </p>
      {estado.fase === "carregando" && <p role="status">Carregando…</p>}
      {estado.fase === "erro" && <p role="alert">{estado.mensagem}</p>}
      {estado.fase === "pronto" &&
        (estado.dado.length === 0 ? (
          <p className="vazio">Nenhuma proposta esperando você.</p>
        ) : (
          <ul className="propostas-lista">
            {estado.dado.map((p) => (
              <li key={p.id} className="propostas-item">
                <Link href={comToken(`/propostas/${p.id}`, token)}>
                  <b>{p.titulo}</b>
                  <span className="prop-validade">{validadeDaProposta(p)}</span>
                  {avisoDeOrigem(p) && <span className="prop-aviso-origem">Leu conteúdo de fora da Casa</span>}
                </Link>
              </li>
            ))}
          </ul>
        ))}
    </div>
  );
}
