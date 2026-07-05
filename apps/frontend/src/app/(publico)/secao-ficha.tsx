"use client";

// SecaoFicha — Task 3.2 (Fatia A2.3, Portal do Cidadão). Compõe useFicha (fetch client-side, mesmo
// DESVIO documentado em use-ficha.ts) + derivarFicha (ficha-vista, 3.1). Arquivo NOVO fora do file-list
// literal do plano — mesma razão já documentada em secao-em-tramitacao.tsx (A2.1): um
// `fetch("/api/...")` relativo não resolve em Server Component (SSR), então o fetch real mora num
// Client Component, com page.tsx (Server Component) ficando fino (params -> shell).
//
// Estados: "carregando" não mostra nada (evita flash de honesto-vazio); "erro" (ficha ausente — 404 ou
// falha de rede, `buscarPublico` colapsa os dois no mesmo `null`) vira "matéria não encontrada" sem
// afirmar qual dos dois ocorreu; "pronto" renderiza a ficha inteira. Comentários degradam ISOLADOS: um
// `null` (fetch de comentários falho) mostra o mesmo card honesto de "não foi possível carregar" — NUNCA
// esvazia a ficha inteira (Global Constraints — "degradação por seção").

import { useFicha } from "@/lib/use-ficha";
import { derivarFicha } from "@/lib/ficha-vista";
import { AzulejoFaixa } from "@/lib/charts/azulejo-faixa";
import { descreverFaixa } from "@/lib/tramitacao-vista";

function formatarData(iso: string): string {
  try {
    return new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "2-digit", year: "numeric" }).format(
      new Date(iso),
    );
  } catch {
    return iso;
  }
}

export function SecaoFicha({ ente, proposicaoId }: { ente: string; proposicaoId: string }) {
  const { ficha, comentarios, estado } = useFicha(ente, proposicaoId);

  // review A2.3 item 5: affordance de carregamento (consistência com secao-em-tramitacao.tsx) — sem
  // skeleton, só o `aria-busy` honesto para leitor de tela/testes; nenhum conteúdo visível ainda.
  if (estado === "carregando") return <div aria-busy="true" />;

  if (estado === "erro") {
    return (
      <div className="em-breve" role="status">
        <p className="em-breve-titulo">Matéria não encontrada</p>
        <p className="em-breve-motivo">
          Não encontramos esta matéria — o link pode estar incorreto, ou pode ter sido uma instabilidade
          passageira. Tente novamente em instantes ou volte à{" "}
          <a href={`/portal/casa/${ente}`}>página inicial do portal</a>.
        </p>
      </div>
    );
  }

  if (!ficha) return null; // fail-closed: nunca deveria acontecer com estado "pronto", mas nunca lança.
  const vista = derivarFicha(ficha, comentarios);

  return (
    <>
      <nav className="migalha" aria-label="Trilha">
        <a href={`/portal/casa/${ente}`}>Início</a>
        <span aria-hidden="true">›</span>
        <span>{vista.ref}</span>
      </nav>

      <div className="ficha-cab">
        <div className="linha-id">
          <span className="num">{vista.ref}</span>
          <span className="estado-chip">{vista.situacao}</span>
        </div>
        <h1>{vista.titulo}</h1>
        <p className="autoria">
          {vista.autorTexto ? (
            <>
              Autoria de <b>{vista.autorTexto}</b>
            </>
          ) : (
            "Autoria não informada."
          )}
        </p>
      </div>

      <section className="ficha-tram" aria-label="Tramitação da matéria">
        <h2>Onde este projeto está</h2>
        <AzulejoFaixa estagios={vista.estagios} rotuloAria={descreverFaixa(vista.ref, vista.estagios)} />
        <p className="permalink">
          <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true">
            <path
              d="M5.5 8.5a2.5 2.5 0 0 0 3.5 0l2-2a2.5 2.5 0 1 0-3.5-3.5l-1 1"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.3"
              strokeLinecap="round"
            />
            <path
              d="M8.5 5.5a2.5 2.5 0 0 0-3.5 0l-2 2a2.5 2.5 0 1 0 3.5 3.5l1-1"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.3"
              strokeLinecap="round"
            />
          </svg>
          {vista.permalink}
        </p>
      </section>

      {vista.normaPublicada && (
        <p className="norma-publicada">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.3} aria-hidden="true">
            <circle cx="8" cy="8" r="6.5" />
            <path d="M5.2 8.2l1.8 1.8 3.8-3.8" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <span>
            <b>Virou lei.</b> Publicada em {formatarData(vista.normaPublicada.publicadoEm)} —{" "}
            <a href={`/api/portal/casa/${ente}/legislacao/${vista.normaPublicada.normaId}/artefato`}>
              Ver a Lei {vista.normaPublicada.numero}/{vista.normaPublicada.ano} publicada — texto oficial
            </a>{" "}
            ({vista.normaPublicada.urn})
          </span>
        </p>
      )}

      <section className="secao">
        <h2>O que este projeto faz</h2>
        <div className="resumo-ia" data-ia="off">
          <div className="resumo-off">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
              <circle cx="12" cy="12" r="9" />
              <path d="M12 8h.01M11 12h1v4h1" />
            </svg>
            <p>
              <b>O resumo em linguagem simples está indisponível agora.</b> Ele é escrito com ajuda de
              IA — volta a aparecer aqui assim que o serviço reconectar. O <b>texto oficial</b> da
              proposição, com toda a tramitação, já está disponível acima.
            </p>
          </div>
        </div>
      </section>

      <section className="secao" aria-label="Participação cidadã">
        <h2>O que a população está dizendo</h2>
        <p className="part-regras">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
          </svg>
          <span>
            <b>Espaço público e moderado.</b> Os comentários ficam visíveis para todos e passam por
            moderação da Câmara antes de aparecer.
          </span>
        </p>

        {comentarios === null ? (
          <div className="em-breve" role="status">
            <p className="em-breve-titulo">Comentários</p>
            <p className="em-breve-motivo">
              Não foi possível carregar os comentários agora. Tente novamente em instantes.
            </p>
          </div>
        ) : vista.comentarios.length === 0 ? (
          <p className="em-breve-motivo">Nenhum comentário aprovado ainda nesta matéria.</p>
        ) : (
          <ul className="coment" aria-label="Comentários aprovados">
            {vista.comentarios.map((c) => (
              <li className="cmt" key={c.id}>
                <span className="av" aria-hidden="true">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
                    <path d="M12 2a5 5 0 0 0-5 5v3H6a2 2 0 0 0-2 2v8h16v-8a2 2 0 0 0-2-2h-1V7a5 5 0 0 0-5-5z" />
                  </svg>
                </span>
                <div className="corpo">
                  <span className="quando">{formatarData(c.criadoEm)}</span>
                  <p>{c.corpo}</p>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
