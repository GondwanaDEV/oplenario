"use client";

// As VOTAÇÕES no portal do cidadão: o que a Câmara votou em sessão pública, o resultado em palavras e — quando a
// votação foi nominal — o voto de cada vereador, pelo nome parlamentar. O backend só entrega votação ENCERRADA de
// sessão pública e não secreta; na votação secreta há só o resultado, nunca quem votou como. Sem percentual nem
// ranking de vereador. A lista tem o total e as páginas explícitos: nada é cortado em silêncio.

import { useEffect, useState } from "react";
import type { VotacaoDetalheOut, VotacaoPublicaOut, VotacoesPublicasOut } from "@/lib/contrato-portal.gen";
import { buscarPublicoComConsulta } from "@/lib/portal-api";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import { nomeTipoSessao } from "@/lib/rotulos-sessao";
import { formatarData, formatarHora } from "@/lib/formatar-data";
import {
  nomeModalidade,
  nomeObjeto,
  nomeQuorum,
  nomeVoto,
  resultadoEmPalavras,
} from "@/lib/votacoes-publicas-vista";
import "./votacoes-publicas.css";

type Carga<T> = { fase: "carregando" } | { fase: "pronto"; dado: T } | { fase: "erro" };
type Pedido = { segmentos: string[]; consulta: Record<string, string> };

function useBusca<T>(pedido: Pedido | null): Carga<T> {
  const chave = pedido ? JSON.stringify(pedido) : null;
  const [r, setR] = useState<{ de: string | null; carga: Carga<T> }>({ de: null, carga: { fase: "carregando" } });
  useEffect(() => {
    if (!chave) return;
    let vivo = true;
    (async () => {
      const p = JSON.parse(chave) as Pedido;
      const d = await buscarPublicoComConsulta<T>(p.segmentos, p.consulta);
      if (vivo) setR({ de: chave, carga: d ? { fase: "pronto", dado: d } : { fase: "erro" } });
    })();
    return () => {
      vivo = false;
    };
  }, [chave]);
  return r.de === chave ? r.carga : { fase: "carregando" };
}

type Cabecalho = Pick<VotacaoPublicaOut, "materia" | "objetoTipo">;

function titulo(v: Cabecalho): string {
  return v.materia
    ? formatarNumeroProposicao(v.materia.tipo, v.materia.sequencial, v.materia.ano)
    : nomeObjeto(v.objetoTipo);
}

function tituloSessao(s: VotacaoPublicaOut["sessao"]): string {
  return `${s.numeroSequencial}ª Sessão ${nomeTipoSessao(s.tipoSessao).replace(/^./, (c) => c.toUpperCase())}`;
}

function quando(iso: string): string {
  return `${formatarData(iso)}, ${formatarHora(iso)}`;
}

const POR_PAGINA_PADRAO = 20;

export function VotacoesPublicas({ ente, votacao, pagina }: { ente: string; votacao: string | null; pagina: number }) {
  const lista = useBusca<VotacoesPublicasOut>({
    segmentos: [ente, "votacoes"],
    consulta: pagina > 1 ? { pagina: String(pagina) } : {},
  });
  const base = `/portal/casa/${encodeURIComponent(ente)}/votacoes`;
  return (
    <div className="votacoes-publicas">
      <div className="pg-cab">
        <span className="rotulo-secao">Votações em plenário</span>
        <h1>Votações</h1>
        <p className="sub">
          O que a Câmara já votou em sessões públicas, com o resultado. Nas votações nominais aparece o voto de cada
          vereador; na votação secreta, só o resultado. Sessões secretas não aparecem aqui.
        </p>
      </div>

      {votacao && <VotacaoAberta ente={ente} votacaoId={votacao} />}

      <h2 className="secao-tit">Votações encerradas</h2>
      {lista.fase === "carregando" && <p className="estado">Carregando…</p>}
      {lista.fase === "erro" && (
        <p className="estado" role="alert">
          Não foi possível carregar as votações agora. Tente novamente em instantes.
        </p>
      )}
      {lista.fase === "pronto" && lista.dado.votacoes.length === 0 && (
        <p className="estado">
          {lista.dado.total === 0
            ? "Nenhuma votação encerrada em sessão pública por enquanto."
            : "Esta página não tem votações. Volte para a primeira."}
        </p>
      )}
      {lista.fase === "pronto" && lista.dado.votacoes.length > 0 && (
        <ul className="vp-lista" aria-label="Votações encerradas">
          {lista.dado.votacoes.map((v) => (
            <li key={v.votacaoId} className={`vp-${v.resultado}${v.votacaoId === votacao ? " atual" : ""}`}>
              <a href={`${base}?votacao=${encodeURIComponent(v.votacaoId)}${pagina > 1 ? `&pagina=${pagina}` : ""}`}>
                <b className="vp-titulo">{titulo(v)}</b>
                {v.materia && <span className="vp-ementa">{v.materia.ementa}</span>}
                <span className="vp-resultado">{resultadoEmPalavras(v)}</span>
                <span className="vp-meta">
                  {formatarData(v.encerradaEm)} · {tituloSessao(v.sessao)} · {nomeModalidade(v.modalidade)}
                </span>
              </a>
            </li>
          ))}
        </ul>
      )}
      {lista.fase === "pronto" && lista.dado.total > 0 && (
        <Paginas base={base} votacao={votacao} dado={lista.dado} />
      )}
    </div>
  );
}

function Paginas({ base, votacao, dado }: { base: string; votacao: string | null; dado: VotacoesPublicasOut }) {
  const porPagina = dado.porPagina || POR_PAGINA_PADRAO;
  const paginas = Math.max(1, Math.ceil(dado.total / porPagina));
  const de = dado.votacoes.length ? (dado.pagina - 1) * porPagina + 1 : 0;
  const ate = dado.votacoes.length ? de + dado.votacoes.length - 1 : 0;
  const href = (p: number) => {
    const q = new URLSearchParams();
    if (votacao) q.set("votacao", votacao);
    if (p > 1) q.set("pagina", String(p));
    const s = q.toString();
    return s ? `${base}?${s}` : base;
  };
  return (
    <nav className="vp-paginas" aria-label="Páginas de votações">
      <span>
        {de > 0 ? `Mostrando ${de} a ${ate} de ${dado.total}` : `${dado.total} no total`} · página {dado.pagina} de{" "}
        {paginas}
      </span>
      {dado.pagina > 1 && <a href={href(dado.pagina - 1)}>Página anterior</a>}
      {dado.pagina < paginas && <a href={href(dado.pagina + 1)}>Próxima página</a>}
    </nav>
  );
}

function VotacaoAberta({ ente, votacaoId }: { ente: string; votacaoId: string }) {
  const carga = useBusca<VotacaoDetalheOut>({ segmentos: [ente, "votacoes", votacaoId], consulta: {} });
  if (carga.fase === "carregando") return <p className="estado">Carregando a votação…</p>;
  if (carga.fase === "erro") return <p className="estado" role="alert">Votação não encontrada.</p>;
  const v = carga.dado;
  const nome = titulo(v);
  return (
    <section className={`vp-aberta vp-${v.resultado}`} aria-label={`Votação de ${nome}`}>
      <h2>{nome}</h2>
      {v.materia && <span className="vp-ementa">{v.materia.ementa}</span>}
      <p className="vp-resultado">{resultadoEmPalavras(v)}</p>
      <p className="vp-meta">
        {quando(v.encerradaEm)} · {tituloSessao(v.sessao)} · {nomeModalidade(v.modalidade)}
      </p>
      <p>
        Para aprovar: {nomeQuorum(v.quorumTipo)}
        {v.placar?.baseMembros != null ? `, sobre ${v.placar.baseMembros} membros` : ""}.
      </p>
      {v.materia && (
        <a className="vp-ficha" href={`/portal/casa/${encodeURIComponent(ente)}/materias/${encodeURIComponent(v.materia.proposicaoId)}`}>
          Ver a matéria
        </a>
      )}
      {v.modalidade === "secreta" && (
        <p className="vp-nota">Votação secreta: o resultado é público, o voto de cada vereador não.</p>
      )}
      {v.modalidade === "simbolica" && (
        <p className="vp-nota">Votação simbólica: não há registro do voto de cada vereador.</p>
      )}
      {v.modalidade === "nominal" && v.votos.length === 0 && (
        <p className="vp-nota">Nenhum voto individual foi registrado nesta votação.</p>
      )}
      {v.votos.length > 0 && (
        <table className="vp-votos">
          <caption>Voto de cada vereador ({v.votos.length})</caption>
          <thead>
            <tr>
              <th scope="col">Vereador</th>
              <th scope="col">Voto</th>
            </tr>
          </thead>
          <tbody>
            {v.votos.map((o) => (
              <tr key={o.vereadorId}>
                <th scope="row">{o.vereador}</th>
                <td>{nomeVoto(o.voto)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
