"use client";

// "O que a Casa entregou" — porta .orgulho-corpo de paineis-mesa.html. presencaMedia/esicPercentual/
// totalTramitacao são reais; transmissaoAoVivo fica em-breve (nenhuma rota rastreia "transmitida" hoje).

import type { MesaVista } from "@/lib/mesa-vista";

export function OrgulhoInstitucional({ vista }: { vista: MesaVista["orgulho"] }) {
  return (
    <section className="bloco" aria-labelledby="orgulho-titulo">
      <div className="bloco-cabeca"><h2 id="orgulho-titulo">O que a Casa entregou</h2></div>
      <div className="bloco-corpo">
        <div className="orgulho-corpo">
          <div className="org-stat">
            <p className="n">{vista.totalTramitacao ?? "—"}</p>
            <p className="rot">Proposições em tramitação</p>
          </div>
          <div className="org-stat">
            <p className="n">{vista.presencaMedia !== null ? `${vista.presencaMedia}%` : "—"}</p>
            <p className="rot">Presença média nas sessões</p>
          </div>
          <div className="org-stat">
            <p className="n">{vista.esicPercentual !== null ? `${vista.esicPercentual}%` : "—"}</p>
            <p className="rot">Pedidos de informação respondidos no prazo (LAI)</p>
          </div>
          <div className="org-stat">
            <p className="rot">Sessões transmitidas ao vivo — em breve.</p>
          </div>
        </div>
      </div>
    </section>
  );
}
