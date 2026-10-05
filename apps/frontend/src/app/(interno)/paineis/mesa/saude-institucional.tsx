"use client";

// Hero "saúde institucional" — porta .saude/.placar de paineis-mesa.html. Mapeamento fino do view-model
// (mesa-vista.ts); sem lógica própria aqui.
//
// `vista.truncamento` (fatia "painel não mente") NÃO é renderizado aqui, deliberadamente: o placar deste
// card (emDia/pendente/vencida) vem de `resumo`, que o backend calcula por COUNT GROUP BY SEM teto
// (`compliance/db/obrigacao.clj`, `resumo-por-estado`) — os números aqui SEMPRE são completos. O teto de
// 100 (e o `truncamento` derivado dele) atinge só a LISTA `emAberto`, que este componente nem consome.
// Acender um aviso de corte sobre um placar que não está cortado seria o erro oposto ao que esta fatia
// existe para consertar — fingir INCOMPLETUDE onde há completude. O aviso real mora em OQueVence, onde a
// lista truncada de fato aparece na tela.

import type { MesaVista, RemessasVista } from "@/lib/mesa-vista";
import { rotularEstadoRemessa, rotularObrigacao } from "@/lib/rotulos-compliance";

// As remessas ao TCE (ciclo: preparação -> validada -> enviada -> aceita/rejeitada). O card de compliance já as
// trazia (`remessasRecentes`) e nenhuma tela as lia: "em dia com o TCE" sem dizer se a remessa do mês saiu.
// A rejeitada diz em TEXTO que pede nova versão (a remessa rejeitada não reabre: a correção é uma versão nova).
function RemessasAoTce({ remessas }: { remessas: RemessasVista }) {
  return (
    <div className="remessas">
      <h2 id="remessas-titulo" className="remessas-titulo">Remessas ao TCE</h2>
      {remessas.itens.length === 0 ? (
        <p className="remessas-vazio">Nenhuma remessa gerada ainda.</p>
      ) : (
        <ul className="remessas-lista" aria-labelledby="remessas-titulo">
          {remessas.itens.map((r) => (
            <li key={r.id} className={r.estado === "rejeitada" ? "remessa remessa-atencao" : "remessa"}>
              <span className="remessa-nome">{rotularObrigacao(r.templateChave)}</span>
              <span className="remessa-meta">competência {r.competencia} · versão {r.versao}</span>
              <span className="remessa-estado">{rotularEstadoRemessa(r.estado)}</span>
              {r.estado === "rejeitada" && <span className="remessa-acao">precisa de nova versão</span>}
            </li>
          ))}
        </ul>
      )}
      {remessas.truncado && (
        <p role="status" className="aviso-corte">
          Mostrando <b>{remessas.itens.length} de {remessas.total}</b> remessas — as mais antigas ficam fora desta lista.
        </p>
      )}
    </div>
  );
}

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
  const { resumo, remessas } = vista;
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
        {remessas && <RemessasAoTce remessas={remessas} />}
      </div>
    </section>
  );
}
