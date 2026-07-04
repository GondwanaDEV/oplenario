"use client";

// Hero "saúde institucional" — porta .saude/.placar de paineis-mesa.html. Mapeamento fino do view-model
// (mesa-vista.ts); sem lógica própria aqui.

import type { MesaVista } from "@/lib/mesa-vista";

export function SaudeInstitucional({ vista }: { vista: MesaVista["saude"] }) {
  if (vista.estado === "indisponivel") {
    return (
      <section className="saude saude-indisponivel" aria-labelledby="saude-titulo">
        <div className="saude-corpo">
          <p className="eyebrow">Saúde institucional · prestação de contas da Mesa</p>
          <h1 id="saude-titulo">Painel de compliance indisponível no momento.</h1>
          <p className="saude-sub">Os demais painéis abaixo seguem carregando normalmente.</p>
        </div>
      </section>
    );
  }
  const { resumo } = vista;
  const emDia = resumo.cumprida + resumo.dispensada + resumo.cancelada;
  const vencidas = resumo.vencida;
  return (
    <section className="saude" aria-labelledby="saude-titulo">
      <div className="saude-corpo">
        <p className="eyebrow">Saúde institucional · prestação de contas da Mesa</p>
        <h1 id="saude-titulo">
          {vencidas === 0 ? (
            <>A Casa está em dia com o <em>TCE-CE</em>.</>
          ) : (
            <>{vencidas} obrigação(ões) venceu(ram) o prazo no <em>TCE-CE</em>.</>
          )}
        </h1>
        <div className="placar">
          <div className="ob-col ob-dia">
            <p className="rotulo">Em dia</p>
            <p className="n">{emDia}</p>
            <p className="est">conformes</p>
          </div>
          <div className="ob-col ob-vencer">
            <p className="rotulo">Pendentes</p>
            <p className="n">{resumo.pendente}</p>
          </div>
          <div className="ob-col ob-risco">
            <p className="rotulo">Vencidas</p>
            <p className="n">{vencidas}</p>
          </div>
        </div>
      </div>
    </section>
  );
}
