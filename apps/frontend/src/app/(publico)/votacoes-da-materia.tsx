"use client";

// "Votações desta matéria" na ficha PÚBLICA da matéria: liga a ficha ao portal de votações. A seção só existe quando há
// ao menos uma votação encerrada dessa matéria em sessão pública (o servidor só devolve essas: sessão secreta nunca
// sai) — um link que leva a uma lista vazia seria pior do que não ter link. Falha de rede, resposta estranha ou nenhuma
// votação: a seção some, e a ficha segue inteira (cada seção degrada sozinha). A contagem é a do servidor (`total`),
// nunca a do que veio na página.

import { useEffect, useState } from "react";
import type { VotacoesPublicasOut } from "@/lib/contrato-portal.gen";
import { buscarPublicoComConsulta } from "@/lib/portal-api";
import "./votacoes-da-materia.css";

export function useVotacoesDaMateria(ente: string, proposicaoId: string): number {
  // o resultado guarda DE QUAL matéria veio: trocar de matéria esconde o anterior sem setState síncrono no efeito
  const [res, setRes] = useState<{ chave: string; total: number }>({ chave: "", total: 0 });
  const chave = `${ente}\u0000${proposicaoId}`;
  useEffect(() => {
    let vivo = true;
    (async () => {
      const r = await buscarPublicoComConsulta<VotacoesPublicasOut>([ente, "votacoes"], { materia: proposicaoId });
      if (!vivo) return;
      const total = r && Array.isArray(r.votacoes) && r.votacoes.length > 0 && Number.isInteger(r.total) ? r.total : 0;
      setRes({ chave, total });
    })();
    return () => {
      vivo = false;
    };
  }, [ente, proposicaoId, chave]);
  return res.chave === chave ? res.total : 0;
}

export function VotacoesDaMateria({ ente, proposicaoId }: { ente: string; proposicaoId: string }) {
  const total = useVotacoesDaMateria(ente, proposicaoId);
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
