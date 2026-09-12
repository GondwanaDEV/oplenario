"use client";

// Dashboard da Mesa (§16.11, item 11.4) — assembly da rota /paineis/mesa (Task B9). Une useAuth (App
// Shell, Task B1) + useMesa (Task B4, já camelizado — B7b) + derivarMesaVista (view-model puro, Task
// B5/B6/B7) + as 7 seções (Tasks B6/B7/B8). Vive sob app/(interno)/, cujo layout.tsx JÁ renderiza
// <AuthProvider>/<TemaProvider> como ancestrais — chamar useAuth() aqui é o uso pretendido do contrato de
// composição (comentário em src/lib/auth.tsx), não uma violação dele.
//
// `ator` fixo ("Sérgio Lopes"/"Presidente da Mesa") é PLACEHOLDER de exibição — não há rota de identidade
// do ator logado nesta fatia (o token dev não carrega nome/papel humano, só claims técnicas). Aceitável
// para o dashboard read-only desta fatia; vira carry para quando a Onda D (auth real) entrar.

import { useAuth } from "@/lib/auth";
import { useMesa } from "@/lib/use-mesa";
import { derivarMesaVista } from "@/lib/mesa-vista";
import { TopoInterno } from "../../topo";
import { SaudeInstitucional } from "./saude-institucional";
import { OQueVence } from "./o-que-vence";
import { PipelineLegislativo } from "./pipeline-legislativo";
import { DespachosDaMesa } from "./despachos-da-mesa";
import { OrgulhoInstitucional } from "./orgulho-institucional";
import { ProximaSessaoRail } from "./proxima-sessao-rail";
import { LenteJuridico } from "./lente-juridico";
import "./mesa.css";

export default function PaginaDashboardMesa() {
  const { token } = useAuth();
  const {
    mesa, tramitacaoItens, pendenciasItens, pendenciasTotal, sliSessoes, relatoresPendentes,
    relatoresPendentesTruncado, estado,
  } = useMesa(token);
  const vista = derivarMesaVista({
    mesa, tramitacaoItens, pendenciasItens, pendenciasTotal, sliSessoes, relatoresPendentes,
    relatoresPendentesTruncado,
  });

  if (estado === "erro") {
    return (
      <main className="tela-estado">
        <h1>Não foi possível carregar o Dashboard da Mesa</h1>
        <p>Tente novamente em instantes.</p>
      </main>
    );
  }
  if (estado === "carregando") {
    return (
      <main className="tela-estado">
        <h1>Carregando…</h1>
      </main>
    );
  }
  return (
    <>
      <TopoInterno area="Painéis da Mesa" ator={{ nome: "Sérgio Lopes", papel: "Presidente da Mesa" }} />
      <main className="envelope">
        <SaudeInstitucional vista={vista.saude} />
        <div className="cockpit">
          <div className="coluna">
            <OQueVence vista={vista.oQueVence} />
            <DespachosDaMesa vista={vista.despachos} />
          </div>
          <ProximaSessaoRail sliSessoes={sliSessoes} />
        </div>
        <PipelineLegislativo vista={vista.pipeline} />
        <OrgulhoInstitucional vista={vista.orgulho} />
        <LenteJuridico />
      </main>
    </>
  );
}
