"use client";

import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api-fetch";
import { camelizarChaves } from "@/lib/boundary";
import {
  deslocarMes,
  dinheiro,
  linhaDeRevisao,
  mensagemDeErroIaCasa,
  percentual,
  porCapacidade,
  rotuloDoMes,
  situacaoDaCota,
} from "@/lib/ia-casa-vista";
import type { PainelIAOut } from "@/lib/contrato-paineis.gen";

type Carga = { mes: string | null; fase: "carregando" } | { mes: string | null; fase: "pronto"; dado: PainelIAOut }
  | { mes: string | null; fase: "erro"; mensagem: string };

function usePainelIA(token: string | null, mes: string | null) {
  const [carga, setCarga] = useState<Carga>({ mes: null, fase: "carregando" });
  useEffect(() => {
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch(`/api/paineis/ia${mes ? `?mes=${mes}` : ""}`, { token: token ?? undefined, cache: "no-store" });
        if (!vivo) return;
        if (!r.ok) return setCarga({ mes, fase: "erro", mensagem: mensagemDeErroIaCasa(r.status) });
        const dado = camelizarChaves(await r.json()) as PainelIAOut;
        if (vivo) setCarga({ mes, fase: "pronto", dado });
      } catch {
        if (vivo) setCarga({ mes, fase: "erro", mensagem: mensagemDeErroIaCasa(0) });
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token, mes]);
  return carga.mes === mes ? carga : ({ mes, fase: "carregando" } as Carga);
}

function Medidor({ p }: { p: PainelIAOut }) {
  const pct = percentual(p);
  if (pct === null || !p.orcamento) return null;
  const teto = Number(p.orcamento.mensal) > 0 ? (Number(p.orcamento.tetoDuro) / Number(p.orcamento.mensal)) * 100 : 100;
  const escala = Math.max(teto, 100);
  const largura = Math.min(pct, escala) / escala;
  return (
    <div className="ia-medidor" role="img"
      aria-label={`${pct}% do orçamento do mês usado`}>
      <div className={`ia-medidor-barra ia-medidor-${pct >= 100 ? "alerta" : pct >= 80 ? "aviso" : "ok"}`}
        style={{ width: `${largura * 100}%` }} />
      <span className="ia-marca" style={{ left: `${(80 / escala) * 100}%` }} aria-hidden="true" title="80%" />
      <span className="ia-marca ia-marca-100" style={{ left: `${(100 / escala) * 100}%` }} aria-hidden="true" title="orçamento" />
    </div>
  );
}

function Consumo({ p }: { p: PainelIAOut }) {
  const s = situacaoDaCota(p);
  const pct = percentual(p);
  return (
    <section className={`ia-cartao ia-consumo ia-tom-${s.tom}`} aria-labelledby="ia-consumo-titulo">
      <h2 id="ia-consumo-titulo">{s.titulo}</h2>
      {p.consumoDisponivel && p.gasto !== null && (
        <p className="ia-valor">
          <b>{dinheiro(p.gasto, p.moeda ?? p.orcamento?.moeda)}</b>
          {p.orcamento ? (
            <> de {dinheiro(p.orcamento.mensal, p.orcamento.moeda)} no mês{pct !== null && <> · {pct}%</>}</>
          ) : (
            <> no mês</>
          )}
        </p>
      )}
      <Medidor p={p} />
      <p className="ia-detalhe">{s.detalhe}</p>
      {p.orcamento && (
        <p className="ia-nota">
          Orçamento mensal {dinheiro(p.orcamento.mensal, p.orcamento.moeda)}; teto {dinheiro(p.orcamento.tetoDuro, p.orcamento.moeda)}.
          Os valores seguem o plano da Casa.
        </p>
      )}
      {p.parcial && (
        <p className="ia-nota">Algumas execuções usaram um modelo sem preço na tabela: o gasto real é maior que o mostrado.</p>
      )}
    </section>
  );
}

function Capacidades({ p }: { p: PainelIAOut }) {
  const linhas = porCapacidade(p.porCapacidade);
  return (
    <section className="ia-cartao" aria-labelledby="ia-cap-titulo">
      <h2 id="ia-cap-titulo">O que a IA fez</h2>
      {!p.consumoDisponivel ? (
        <p className="ia-detalhe">Aparece quando a IA voltar.</p>
      ) : linhas.length === 0 ? (
        <p className="ia-detalhe">Nenhum uso da IA neste mês.</p>
      ) : (
        <ul className="ia-lista">
          {linhas.map((l) => (
            <li key={l.nome}>
              <p className="ia-linha-titulo">
                <b>{l.nome}</b>
                <span>{dinheiro(l.custo, p.moeda ?? p.orcamento?.moeda)}</span>
              </p>
              <p className="ia-linha-detalhe">
                {l.execucoes} {l.execucoes === 1 ? "uso" : "usos"}
                {l.naoRodaram > 0 && ` · ${l.naoRodaram} não ${l.naoRodaram === 1 ? "rodou" : "rodaram"}`}
                {l.segundoPlano && " · a IA faz sozinha"}
              </p>
              {linhaDeRevisao(l) && <p className="ia-linha-detalhe">{linhaDeRevisao(l)}</p>}
              {l.errosReportados > 0 && (
                <p className="ia-linha-detalhe ia-erros">
                  {l.errosReportados} {l.errosReportados === 1 ? "erro reportado" : "erros reportados"}
                </p>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function contar(n: number, um: string, varios: string): string {
  return `${n} ${n === 1 ? um : varios}`;
}

function Desfechos({ p }: { p: PainelIAOut }) {
  const n = p.notasTecnicas;
  const q = p.propostas;
  return (
    <section className="ia-cartao" aria-labelledby="ia-desf-titulo">
      <h2 id="ia-desf-titulo">O que a Casa fez com o que a IA entregou</h2>
      <dl className="ia-desfechos">
        <div>
          <dt>Notas técnicas da conferência</dt>
          <dd>
            {contar(n.aproveitadas, "aproveitada", "aproveitadas")} · {contar(n.descartadas, "descartada", "descartadas")} ·{" "}
            {n.pendentes} na fila
          </dd>
        </div>
        <div>
          <dt>Propostas do assistente</dt>
          <dd>
            {contar(q.confirmadas, "confirmada", "confirmadas")} · {contar(q.recusadas, "recusada", "recusadas")} ·{" "}
            {contar(q.expiradas, "expirou", "expiraram")} · {q.aguardando} esperando
          </dd>
        </div>
      </dl>
    </section>
  );
}

/** O mês corrente da Casa (America/Fortaleza), 'AAAA-MM' — não há mês seguinte a ele. */
function mesCorrente(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "America/Fortaleza", year: "numeric", month: "2-digit" })
    .format(new Date())
    .slice(0, 7);
}

export function PainelIA({ token = null }: { token?: string | null }) {
  const [mes, setMes] = useState<string | null>(null);
  const carga = usePainelIA(token, mes);
  const atual = carga.fase === "pronto" ? carga.dado.mes : mes;
  return (
    <main className="envelope ia">
      <header className="ia-cabeca">
        <h1>IA da Casa</h1>
        <p className="ia-sub">
          Quanto a IA custou no mês, o que ela fez e o que a Casa fez com o que ela entregou. Só números: nenhum texto
          das execuções aparece aqui.
        </p>
      </header>
      {atual && (
        <nav className="ia-meses" aria-label="Escolher o mês">
          <button type="button" className="btn btn-fantasma" aria-label="Mês anterior" onClick={() => setMes(deslocarMes(atual, -1))}>
            ← Anterior
          </button>
          <b aria-live="polite">{rotuloDoMes(atual)}</b>
          <button type="button" className="btn btn-fantasma" aria-label="Mês seguinte" disabled={atual >= mesCorrente()} onClick={() => setMes(deslocarMes(atual, 1))}>
            Seguinte →
          </button>
        </nav>
      )}
      {carga.fase === "carregando" && <p role="status">Carregando…</p>}
      {carga.fase === "erro" && <p className="ia-erro" role="alert">{carga.mensagem}</p>}
      {carga.fase === "pronto" && (
        <>
          <Consumo p={carga.dado} />
          <Capacidades p={carga.dado} />
          <Desfechos p={carga.dado} />
        </>
      )}
    </main>
  );
}
