"use client";

// CENTRAL DA CASA (docs/23 Fatia 3) — o /inicio do operador da Casa (papel `secretario`). Porta
// `produto/design-system/o-plenario/telas/central-da-casa.html`: a trilha da sessão em foco (peças de azulejo do
// chassi, UMA ação por etapa), a fila de trabalho (cada item sai sozinho quando o ato é concluído — sem botão
// "feito") e as próximas sessões com a prontidão da pauta.
//
// `CentralDaCasa` faz o IO (useCentral); `PainelCentral` só desenha a vista já derivada (central-vista.ts) —
// testável sem providers, mesmo split de ConteudoInicio/PainelInicio.

import Link from "next/link";
import type { ReactNode } from "react";
import { useCentral } from "@/lib/use-central";
import { comToken } from "@/lib/nav";
import { ATALHOS_SECRETARIA } from "@/lib/inicio-vista";
import type { CentralVista, EtapaTrilha, FocoSessao, IconeFila, ItemFila } from "@/lib/central-vista";
import "./central.css";

const ESTADO_SR: Record<EtapaTrilha["estado"], string> = { feita: "concluída", atual: "etapa atual", pendente: "pendente" };
const GRAVIDADE: Record<ItemFila["gravidade"], string> = { legal: "Legal", reg: "Regimental", adm: "Administrativa" };

function Icone({ tipo }: { tipo: IconeFila | "pronta" }) {
  const p = { width: 20, height: 20, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 2, "aria-hidden": true } as const;
  switch (tipo) {
    case "prazo":
      return <svg {...p}><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></svg>;
    case "tce":
      return <svg {...p}><path d="M3 21h18M5 21V8l7-5 7 5v13M9 21v-6h6v6" /></svg>;
    case "folha":
      return <svg {...p}><path d="M14 3v4a1 1 0 0 0 1 1h4" /><path d="M17 21H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7l5 5v11a2 2 0 0 1-2 2Z" /><path d="M9 13h6M9 17h4" /></svg>;
    case "justificativa":
      return <svg {...p}><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="m17 11 2 2 4-4" /></svg>;
    case "moderacao":
      return <svg {...p}><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" /></svg>;
    case "pronta":
      return <svg {...p}><path d="M9 11l3 3L22 4" /><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" /></svg>;
  }
}

function Nota({ children, icone = "ok" }: { children: ReactNode; icone?: "ok" | "pronta" }) {
  return (
    <p className="cc-nota">
      {icone === "pronta" ? (
        <Icone tipo="pronta" />
      ) : (
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg>
      )}
      <span>{children}</span>
    </p>
  );
}

function Trilha({ etapas }: { etapas: EtapaTrilha[] }) {
  return (
    <ol className="cc-trilha" aria-label="Trilha da sessão">
      {etapas.map((e) => (
        <li key={e.chave} className={`cc-etapa cc-etapa--${e.estado}`} aria-current={e.estado === "atual" ? "step" : undefined}>
          <span className={`az-peca ${e.estado === "feita" ? `az-${e.cor} az-feita` : e.estado === "atual" ? "az-curso" : "az-pend"}`} aria-hidden="true" />
          <span>
            <b>{e.rotulo}</b>
            {e.detalhe && <small>{e.detalhe}</small>}
            <span className="sr-only"> — {ESTADO_SR[e.estado]}</span>
          </span>
        </li>
      ))}
    </ol>
  );
}

function Foco({ foco, token }: { foco: FocoSessao; token: string | null }) {
  return (
    <section className="cc-foco" aria-labelledby="cc-foco-tit">
      <div className="cc-foco-cab">
        <div>
          {foco.aoVivo ? <span className="cc-selo-vivo">{foco.chamada}</span> : <span className="eyebrow">{foco.chamada}</span>}
          <h2 id="cc-foco-tit">{foco.titulo}</h2>
        </div>
        <span className="cc-quando">{foco.quando}</span>
      </div>
      <Trilha etapas={foco.etapas} />
      <div className="cc-foco-acao">
        <p className="cc-porque">{foco.porque}</p>
        <Link className="btn btn-primaria" href={comToken(foco.principal.href, token)}>{foco.principal.rotulo}</Link>
        {foco.secundarias.length > 0 && (
          <div className="cc-sec">
            {foco.secundarias.map((a) => (
              <Link key={a.href} className="btn btn-contorno btn-mini" href={comToken(a.href, token)}>{a.rotulo}</Link>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}

function BlocoFoco({ vista, token }: { vista: CentralVista; token: string | null }) {
  const f = vista.foco;
  if (f.situacao === "sessao") return <Foco foco={f.foco} token={token} />;
  if (f.situacao === "carregando") {
    return (
      <section className="cc-foco cc-foco--estado" aria-busy="true" aria-label="Sessão em foco">
        <p className="cc-porque">Carregando as sessões…</p>
      </section>
    );
  }
  if (f.situacao === "erro") {
    return (
      <section className="cc-foco cc-foco--estado" aria-label="Sessão em foco">
        <h2 className="cc-estado-tit">Não foi possível carregar as sessões</h2>
        <p className="cc-porque">Recarregue a página. Se persistir, a plataforma pode estar indisponível.</p>
      </section>
    );
  }
  return (
    <section className="cc-foco cc-foco--estado" aria-labelledby="cc-foco-tit">
      <h2 id="cc-foco-tit" className="cc-estado-tit">Nenhuma sessão agendada</h2>
      <p className="cc-porque">A Casa não tem sessão marcada no momento.</p>
      <Link className="btn btn-primaria" href={comToken(f.acao.href, token)}>{f.acao.rotulo}</Link>
    </section>
  );
}

function LinhaFila({ item, token }: { item: ItemFila; token: string | null }) {
  return (
    <li className={`cc-item cc-item--${item.gravidade}`}>
      <span className={`cc-item-ic cc-item-ic--${item.gravidade}`}><Icone tipo={item.icone} /></span>
      <div className="cc-item-mid">
        <h3>{item.titulo}</h3>
        <p className="cc-ctx">
          <span className={`cc-gchip cc-gchip--${item.gravidade}`}>{GRAVIDADE[item.gravidade]}</span>
          {item.prazo && <span className={item.prazo.atrasado ? "cc-prazo cc-prazo--atrasado" : "cc-prazo"}>{item.prazo.texto}</span>}
          {item.contexto}
        </p>
      </div>
      <Link className="btn btn-contorno btn-mini" href={comToken(item.acao.href, token)}>{item.acao.rotulo}</Link>
    </li>
  );
}

export function PainelCentral({ vista, token }: { vista: CentralVista; token: string | null }) {
  const { fila } = vista;
  const totalFila = fila.itens.length + fila.legaisOcultos;
  return (
    <main className="envelope cc">
      <div className="cc-cab">
        <span className="eyebrow">Central da Casa · {vista.hoje}</span>
        <h1>{vista.saudacao}</h1>
        {vista.lede.length > 0 && (
          <p className="cc-lede">
            {vista.lede.map((t, i) => (t.forte ? <b key={i}>{t.texto}</b> : <span key={i}>{t.texto}</span>))}
          </p>
        )}
      </div>

      <BlocoFoco vista={vista} token={token} />

      <div className="cc-grade">
        <section className="cc-col" aria-labelledby="cc-fila-tit" aria-busy={fila.carregando}>
          <h2 id="cc-fila-tit">
            Fila de trabalho {!fila.carregando && <span className="cc-qt">· {totalFila}</span>}
          </h2>
          {fila.indisponiveis.length > 0 && (
            <p role="status" className="cc-aviso">Não foi possível carregar: {fila.indisponiveis.join("; ")}.</p>
          )}
          {fila.itens.length > 0 && (
            <ul className="cc-fila">
              {fila.itens.map((i) => <LinhaFila key={i.id} item={i} token={token} />)}
            </ul>
          )}
          {fila.legaisOcultos > 0 && (
            <p className="cc-mais">
              E mais {fila.legaisOcultos} {fila.legaisOcultos === 1 ? "prazo legal" : "prazos legais"} em aberto —{" "}
              <Link href={comToken("/paineis/mesa", token)}>ver todos nos Painéis da Mesa</Link>.
            </p>
          )}
          {fila.carregando && fila.itens.length === 0 && <p className="cc-vazio">Carregando a fila…</p>}
          {!fila.carregando && fila.indisponiveis.length === 0 && totalFila === 0 && (
            <p className="cc-vazio">Nada pendente. A fila se enche sozinha quando algo depender de você.</p>
          )}
          {totalFila > 0 && (
            <Nota>
              Cada item <b>sai sozinho</b> quando o ato é concluído — folha gerada, justificativa decidida, comentário
              moderado. Não há botão “feito”.
            </Nota>
          )}
        </section>

        <section className="cc-col" aria-labelledby="cc-prox-tit">
          <h2 id="cc-prox-tit">
            Próximas sessões <span className="cc-qt">· {vista.proximas.length}</span>
          </h2>
          {vista.proximas.length > 0 ? (
            <ul className="cc-proximas">
              {vista.proximas.map((p) => (
                <li key={p.sessaoId}>
                  <span className="cc-qual">{p.titulo}</span>
                  <span className="cc-data">{p.quando}</span>
                  <div className="cc-pront">
                    <span className={`cc-pchip cc-pchip--${p.pauta.tom}`}>{p.pauta.texto}</span>
                    <Link className="cc-link" href={comToken(p.acao.href, token)} aria-label={`${p.acao.rotulo} da ${p.titulo}`}>
                      {p.acao.rotulo}
                    </Link>
                  </div>
                </li>
              ))}
            </ul>
          ) : (
            <p className="cc-vazio">Nenhuma outra sessão agendada.</p>
          )}
          {vista.prontasParaPauta != null && vista.prontasParaPauta > 0 && (
            <Nota icone="pronta">
              <b>{vista.prontasParaPauta} {vista.prontasParaPauta === 1 ? "matéria está pronta" : "matérias estão prontas"}</b> para
              pauta no <Link href={comToken("/tramitacao", token)}>quadro de tramitação</Link>.
            </Nota>
          )}
        </section>
      </div>

      <section className="cc-areas" aria-labelledby="cc-areas-tit">
        <h2 id="cc-areas-tit">Áreas de trabalho</h2>
        <nav aria-label="Áreas de trabalho">
          {ATALHOS_SECRETARIA.map((a) => (
            <Link key={a.href} href={comToken(a.href, token)} title={a.descricao}>{a.rotulo}</Link>
          ))}
        </nav>
      </section>
    </main>
  );
}

export function CentralDaCasa({ token }: { token: string | null }) {
  const vista = useCentral(token);
  return <PainelCentral vista={vista} token={token} />;
}
