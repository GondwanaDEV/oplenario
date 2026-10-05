"use client";

// "Votações desta matéria" na ficha PÚBLICA da matéria: liga a ficha ao portal de votações. A seção só existe quando há
// ao menos uma votação encerrada dessa matéria em sessão pública (o servidor só devolve essas: sessão secreta nunca
// sai) — um link que leva a uma lista vazia seria pior do que não ter link. Falha de rede, resposta estranha ou nenhuma
// votação: a seção some, e a ficha segue inteira (cada seção degrada sozinha). A contagem é a do servidor (`total`),
// nunca a do que veio na página.

import { useEffect, useState } from "react";
import type { VotacaoPublicaOut, VotacoesPublicasOut } from "@/lib/contrato-portal.gen";
import { formatarData } from "@/lib/formatar-data";
import { buscarPublicoComConsulta } from "@/lib/portal-api";
import "./votacoes-da-materia.css";

export type VotacoesDaMateriaDados = {
  total: number;
  // a votação encerrada MAIS RECENTE desta matéria em sessão pública (o servidor ordena da mais recente), ou nada
  ultima: VotacaoPublicaOut | null;
};

const NADA: VotacoesDaMateriaDados = { total: 0, ultima: null };

function ultimaValida(v: VotacaoPublicaOut | undefined): VotacaoPublicaOut | null {
  // só o que dá para afirmar ao cidadão sem adivinhar: resultado conhecido e data do encerramento
  if (!v || (v.resultado !== "aprovada" && v.resultado !== "rejeitada")) return null;
  return typeof v.encerradaEm === "string" && typeof v.votacaoId === "string" ? v : null;
}

export function useVotacoesDaMateria(ente: string, proposicaoId: string): VotacoesDaMateriaDados {
  // o resultado guarda DE QUAL matéria veio: trocar de matéria esconde o anterior sem setState síncrono no efeito
  const [res, setRes] = useState<{ chave: string } & VotacoesDaMateriaDados>({ chave: "", ...NADA });
  const chave = `${ente}\u0000${proposicaoId}`;
  useEffect(() => {
    let vivo = true;
    (async () => {
      const r = await buscarPublicoComConsulta<VotacoesPublicasOut>([ente, "votacoes"], { materia: proposicaoId });
      if (!vivo) return;
      const ok = r && Array.isArray(r.votacoes) && r.votacoes.length > 0 && Number.isInteger(r.total);
      setRes(ok ? { chave, total: r.total, ultima: ultimaValida(r.votacoes[0]) } : { chave, ...NADA });
    })();
    return () => {
      vivo = false;
    };
  }, [ente, proposicaoId, chave]);
  return res.chave === chave ? { total: res.total, ultima: res.ultima } : NADA;
}

// O ATO do plenário no topo da ficha (achado 18 da retriagem): o `estado` da matéria é o do rito da Casa, e encerrar
// a votação não o move — a ficha dizia só "Aguardando pauta" de um projeto que o plenário já aprovou. Aqui vai o que
// aconteceu, com a data e o link para a votação; o selo de estado fica como está (numa matéria de dois turnos,
// "Aguardando pauta" depois do 1º turno é verdade). Sem votação pública encerrada, nada aparece.
export function UltimaVotacaoEmPlenario({ ente, ultima }: { ente: string; ultima: VotacaoPublicaOut | null }) {
  if (!ultima) return null;
  const objeto = ultima.objetoTipo === "redacao_final" ? "a redação final foi" : "a matéria foi";
  const resultado = ultima.resultado === "aprovada" ? "aprovada" : "rejeitada";
  return (
    <p className="ultima-votacao" role="status">
      <span>
        <b>Última votação em plenário:</b> {objeto} {resultado} em {formatarData(ultima.encerradaEm)}.{" "}
        <a href={`/portal/casa/${encodeURIComponent(ente)}/votacoes/${encodeURIComponent(ultima.votacaoId)}`}>
          Ver a votação
        </a>
      </span>
    </p>
  );
}

export function VotacoesDaMateria({
  ente,
  proposicaoId,
  dados,
}: {
  ente: string;
  proposicaoId: string;
  // a ficha já buscou (uma busca só para o ato e para esta seção); sem `dados`, a seção busca sozinha
  dados?: VotacoesDaMateriaDados;
}) {
  if (dados) return <VotacoesDaMateriaVista ente={ente} proposicaoId={proposicaoId} total={dados.total} />;
  return <VotacoesDaMateriaQueBusca ente={ente} proposicaoId={proposicaoId} />;
}

function VotacoesDaMateriaQueBusca({ ente, proposicaoId }: { ente: string; proposicaoId: string }) {
  const { total } = useVotacoesDaMateria(ente, proposicaoId);
  return <VotacoesDaMateriaVista ente={ente} proposicaoId={proposicaoId} total={total} />;
}

function VotacoesDaMateriaVista({ ente, proposicaoId, total }: { ente: string; proposicaoId: string; total: number }) {
  if (total < 1) return null;
  return (
    <section className="secao vm" aria-labelledby="vm-titulo">
      <h2 id="vm-titulo">Votações desta matéria</h2>
      <p className="vm-texto">
        {total === 1
          ? "A Câmara votou esta matéria em sessão pública 1 vez."
          : `A Câmara votou esta matéria em sessão pública ${total} vezes.`}{" "}
        Veja o resultado de cada votação e, nas nominais, o voto de cada vereador.
      </p>
      <a
        className="btn btn-contorno"
        href={`/portal/casa/${encodeURIComponent(ente)}/votacoes?materia=${encodeURIComponent(proposicaoId)}`}
      >
        Ver as votações desta matéria
      </a>
    </section>
  );
}
