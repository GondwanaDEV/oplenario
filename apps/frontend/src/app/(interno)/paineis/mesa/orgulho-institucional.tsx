"use client";

// "O que a Casa entregou" — porta .orgulho-corpo de paineis-mesa.html. presencaMedia/esicPercentual/
// totalTramitacao são reais; transmissaoAoVivo fica em-breve (nenhuma rota rastreia "transmitida" hoje).

import Link from "next/link";
import { contar } from "@/lib/mesa-vista";
import type { MesaVista } from "@/lib/mesa-vista";
import { comToken } from "@/lib/nav";

export function OrgulhoInstitucional({ vista, token = null }: { vista: MesaVista["orgulho"]; token?: string | null }) {
  return (
    <section className="bloco" aria-labelledby="orgulho-titulo">
      <div className="bloco-cabeca"><h2 id="orgulho-titulo">O que a Casa entregou</h2></div>
      <div className="bloco-corpo">
        <div className="orgulho-corpo">
          <div className="org-stat">
            <p className="n">{vista.totalTramitacao ?? "—"}</p>
            <p className="rot">Proposições em tramitação</p>
            {vista.totalTramitacao !== null && (
              <Link className="ver-mais" href={comToken("/proposicoes", token)}>Ver as proposições</Link>
            )}
          </div>
          <div className="org-stat">
            <p className="n">{vista.presencaMedia !== null ? `${vista.presencaMedia}%` : "—"}</p>
            <p className="rot">
              Presença média nas sessões
              {vista.presencaMedia !== null && vista.presencaSessoes !== null && (
                <> · em {contar(vista.presencaSessoes, "sessão", "sessões")}</>
              )}
            </p>
          </div>
          <div className="org-stat">
            <p className="n">{vista.esicPercentual !== null ? `${vista.esicPercentual}%` : "—"}</p>
            <p className="rot">
              Pedidos de informação respondidos no prazo (LAI)
              {vista.esicPercentual !== null && vista.esicEncerrados !== null && (
                <> · de {contar(vista.esicEncerrados, "pedido encerrado", "pedidos encerrados")}</>
              )}
            </p>
            {vista.esicPercentual !== null && (
              <Link className="ver-mais" href={comToken("/atendimento?aba=esic", token)}>Ver os pedidos de e-SIC</Link>
            )}
          </div>
          <div className="org-stat">
            <p className="rot">Sessões transmitidas ao vivo — em breve.</p>
          </div>
        </div>
      </div>
    </section>
  );
}
