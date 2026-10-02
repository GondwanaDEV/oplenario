"use client";

// A PAUTA OFICIAL no portal do cidadão (ADR-0019 fatia 3, Eixo 7): a pauta de cada sessão pública COMO A MESA A
// PUBLICOU — a versão congelada, com o número e a data da publicação —, nunca a pauta viva que a secretaria ainda
// edita. Sessão sem publicação aparece como "pauta ainda não publicada", honesto. O backend só entrega sessões
// públicas e não secretas; as republicações ficam no histórico, nenhuma versão é apagada.

import { useEffect, useState } from "react";
import type { PautaOficialOut, PautasPublicasOut, SessaoPautaPublicaOut } from "@/lib/contrato-sessoes.gen";
import { buscarPublico } from "@/lib/portal-api";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import { quando, seloDaPublicacao } from "@/lib/publicacao-pauta-vista";
import { nomeFase, nomeTipoSessao } from "@/lib/rotulos-sessao";
import { formatarData, formatarHora } from "@/lib/formatar-data";
import "./pautas-oficiais.css";

type Carga<T> = { fase: "carregando" } | { fase: "pronto"; dado: T } | { fase: "erro" };

function useBusca<T>(segmentos: string[] | null): Carga<T> {
  const chave = segmentos?.join("/") ?? null;
  const [r, setR] = useState<{ de: string | null; carga: Carga<T> }>({ de: null, carga: { fase: "carregando" } });
  useEffect(() => {
    if (!chave) return;
    let vivo = true;
    (async () => {
      const d = await buscarPublico<T>(...chave.split("/"));
      if (vivo) setR({ de: chave, carga: d ? { fase: "pronto", dado: d } : { fase: "erro" } });
    })();
    return () => {
      vivo = false;
    };
  }, [chave]);
  return r.de === chave ? r.carga : { fase: "carregando" };
}

function tituloSessao(s: SessaoPautaPublicaOut): string {
  return `${s.numeroSequencial}ª Sessão ${nomeTipoSessao(s.tipoSessao).replace(/^./, (c) => c.toUpperCase())}`;
}

function dataSessao(s: SessaoPautaPublicaOut): string | null {
  const d = s.abertaEm ?? s.agendadaPara;
  return d ? `${formatarData(d)}, ${formatarHora(d)}` : null;
}

export function PautasOficiais({ ente, sessao }: { ente: string; sessao: string | null }) {
  const lista = useBusca<PautasPublicasOut>([ente, "pautas"]);
  const aberta = sessao ?? (lista.fase === "pronto" ? (lista.dado.sessoes.find((s) => s.pautaOficial)?.sessaoId ?? null) : null);
  return (
    <div className="pautas-oficiais">
      <div className="pg-cab">
        <span className="rotulo-secao">Pautas das sessões</span>
        <h1>Pauta oficial</h1>
        <p className="sub">
          O que vai ser apreciado em cada sessão, como a Mesa publicou. Uma mudança na pauta vira nova publicação, com a
          data e o motivo — nenhuma versão é apagada.
        </p>
      </div>

      {aberta && <PautaAberta ente={ente} sessaoId={aberta} />}

      <h2 className="secao-tit">Sessões</h2>
      {lista.fase === "carregando" && <p className="estado">Carregando…</p>}
      {lista.fase === "erro" && (
        <p className="estado" role="alert">
          Não foi possível carregar as pautas agora. Tente novamente em instantes.
        </p>
      )}
      {lista.fase === "pronto" && lista.dado.sessoes.length === 0 && (
        <p className="estado">Nenhuma sessão pública com pauta por enquanto.</p>
      )}
      {lista.fase === "pronto" && lista.dado.sessoes.length > 0 && (
        <ul className="po-lista" aria-label="Sessões e suas pautas">
          {lista.dado.sessoes.map((s) => (
            <li key={s.sessaoId} className={s.sessaoId === aberta ? "atual" : undefined}>
              <a href={`/portal/casa/${encodeURIComponent(ente)}/pautas?sessao=${encodeURIComponent(s.sessaoId)}`}>
                <b>{tituloSessao(s)}</b>
                {dataSessao(s) && <span> · {dataSessao(s)}</span>}
              </a>
              <span className={`po-selo${s.pautaOficial ? " po-selo-ok" : ""}`}>
                {s.pautaOficial ? `${seloDaPublicacao(s.pautaOficial)} · ${s.pautaOficial.itens} ${s.pautaOficial.itens === 1 ? "item" : "itens"}` : "Pauta ainda não publicada"}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function PautaAberta({ ente, sessaoId }: { ente: string; sessaoId: string }) {
  const carga = useBusca<PautaOficialOut>([ente, "pautas", sessaoId]);
  if (carga.fase === "carregando") return <p className="estado">Carregando a pauta…</p>;
  if (carga.fase === "erro") return <p className="estado" role="alert">Pauta não encontrada.</p>;
  const { sessao, vigente, versoes } = carga.dado;
  return (
    <section className="po-aberta" aria-label={`Pauta da ${tituloSessao(sessao)}`}>
      <h2>{tituloSessao(sessao)}</h2>
      {dataSessao(sessao) && <p className="po-data">{dataSessao(sessao)}</p>}
      {!vigente ? (
        <p className="estado">A pauta desta sessão ainda não foi publicada.</p>
      ) : (
        <>
          <p className="po-selo po-selo-ok">
            Pauta oficial · versão {vigente.versao}, publicada em {quando(vigente.publicadaEm)}
            {vigente.justificativa ? ` · ${vigente.justificativa}` : ""}
          </p>
          <ol className="po-itens">
            {vigente.itens.map((it) => (
              <li key={it.id}>
                <span className="po-fase">{nomeFase(it.fase)}</span>
                {it.proposicaoId ? (
                  <a href={`/portal/casa/${encodeURIComponent(ente)}/materias/${encodeURIComponent(it.proposicaoId)}`}>
                    <b>
                      {it.proposicao
                        ? formatarNumeroProposicao(it.proposicao.tipo, it.proposicao.sequencial, it.proposicao.ano)
                        : "Matéria"}
                    </b>
                    {it.proposicao && <span> — {it.proposicao.ementa}</span>}
                  </a>
                ) : (
                  <span>{it.textoDescricao}</span>
                )}
              </li>
            ))}
          </ol>
          {versoes.length > 1 && (
            <details className="po-historico">
              <summary>Publicações anteriores ({versoes.length - 1})</summary>
              <ol>
                {versoes.slice(1).map((v) => (
                  <li key={v.versao}>
                    {seloDaPublicacao(v)}
                    {v.justificativa ? ` · ${v.justificativa}` : ""}
                  </li>
                ))}
              </ol>
            </details>
          )}
        </>
      )}
    </section>
  );
}
