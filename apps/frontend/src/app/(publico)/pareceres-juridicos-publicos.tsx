"use client";

// "Pareceres jurídicos" na ficha PÚBLICA da matéria (ADR-0019, Eixo 4). A Lei de Acesso permite reservar o parecer até a
// decisão sobre a matéria (art. 7º §3º); depois dela ele é público. Por isso o servidor só devolve pareceres quando a
// matéria já foi deliberada — ou quando a Casa escolheu publicar ao assinar (parâmetro do administrador, ADR-0019
// fatia 2a); a origem do rascunho (nota técnica da IA) nunca vai ao portal. Antes disso a lista vem vazia, e a seção NÃO aparece (não há o que dizer, e dizer "ainda
// não há" contaria que existe um pedido reservado). Falha de rede ou resposta estranha também não derruba a ficha: a
// seção some, como as demais degradam por seção. A consulta avulsa (sem matéria) nunca vai ao portal.

import { useEffect, useState } from "react";
import { buscarPublico } from "@/lib/portal-api";
import { blocoDeAssinatura, numeroDoParecer, rotuloConclusao } from "@/lib/juridico-vista";
import type { ParecerJuridicoPublicoOut, PareceresJuridicosPublicosOut } from "@/lib/contrato-juridico.gen";
import "./pareceres-juridicos-publicos.css";

export function usePareceresJuridicosPublicos(ente: string, proposicaoId: string): ParecerJuridicoPublicoOut[] {
  // o resultado guarda DE QUAL matéria veio: trocar de matéria esconde o anterior sem setState síncrono no efeito
  const [res, setRes] = useState<{ chave: string; itens: ParecerJuridicoPublicoOut[] }>({ chave: "", itens: [] });
  const chave = `${ente}\u0000${proposicaoId}`;
  useEffect(() => {
    let vivo = true;
    (async () => {
      const r = await buscarPublico<PareceresJuridicosPublicosOut>(ente, "materias", proposicaoId, "pareceres-juridicos");
      if (!vivo) return;
      setRes({ chave, itens: Array.isArray(r?.pareceres) ? r.pareceres : [] });
    })();
    return () => {
      vivo = false;
    };
  }, [ente, proposicaoId, chave]);
  return res.chave === chave ? res.itens : [];
}

export function PareceresJuridicosPublicos({ ente, proposicaoId }: { ente: string; proposicaoId: string }) {
  const itens = usePareceresJuridicosPublicos(ente, proposicaoId);
  if (itens.length === 0) return null;
  return (
    <section className="secao pj" aria-labelledby="pj-titulo">
      <h2 id="pj-titulo">Pareceres jurídicos</h2>
      <p className="pj-aviso">
        Parecer jurídico é opinativo: orienta a Câmara, mas não decide a matéria. Estes pareceres foram assinados por
        advogados da Casa.
      </p>
      <ul className="pj-lista">
        {itens.map((p, i) => {
          const numero = numeroDoParecer(p) ?? "Parecer jurídico";
          const ass = blocoDeAssinatura(p.assinatura);
          return (
            <li key={`${p.numero ?? "s"}-${p.ano ?? i}-${i}`} className="pj-item">
              <article aria-label={numero}>
                <header className="pj-cab">
                  <h3>{numero}</h3>
                  <span className="pj-conclusao">{rotuloConclusao(p.conclusao)}</span>
                </header>
                <h4>Relatório</h4>
                <p className="pj-texto">{p.relatorio}</p>
                <h4>Fundamentação</h4>
                <p className="pj-texto">{p.fundamentacao}</p>
                <footer className="pj-assinatura">
                  <b>{ass.nome}</b>
                  <span>{ass.registro}</span>
                  <span>{ass.quando}</span>
                  {ass.carimbo && (
                    <span className="pj-carimbo" title={ass.carimbo.aviso ?? undefined}>
                      {ass.carimbo.rotulo}: <code>{ass.carimbo.digest}</code>
                    </span>
                  )}
                  {ass.carimbo?.aviso && <span className="pj-carimbo-aviso">{ass.carimbo.aviso}</span>}
                </footer>
              </article>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
