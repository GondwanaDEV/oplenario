"use client";

// SecaoPerfilVereador — Onda E fatia 2 (Task 6, Portal do Cidadão). Compõe usePerfilVereador (fetch
// client-side, mesmo DESVIO de SSR documentado no hook) + derivarPerfil (perfil-vereador-vista). Mesmo
// split de secao-ficha.tsx: page.tsx (Server Component) fica fina, o fetch real mora aqui.
//
// ESTE ARQUIVO NÃO ESCREVE COPY DE PRESENÇA. Nenhuma frase, nenhum rótulo de truncamento e nenhuma
// declaração de recorte nascem no JSX — todos vêm da vista, onde são constantes travadas por teste contra
// `docs/14-nota-metodologia-presenca.md`. O JSX só decide ONDE cada texto aparece.
//
// O que saiu do design (`produto/design-system/o-plenario/telas/perfil-vereador-publico.html`) e por quê:
//   • "Acompanhar" — subscrição é autenticada e consent-gated, e o IdP gov.br não existe; botão que não
//     faz nada quebra GUIDELINES §2.
//   • Partido / Bloco / número da lei / "N comissões" / "na legislatura atual" — não existem no contrato
//     (ou seriam falsos: a query não filtra por legislatura, e um contador de comissões contaria a Mesa
//     duas vezes porque `comissoes: string[]` não traz `tipo`).
//   • "Agenda pública" — não há campo no contrato nem rota pública de agenda por vereador; os 3 eventos do
//     HTML são fabricados. Nem `<EmBreve>`: aquilo é para superfície com backend planejado, e aqui não há
//     nem plano.
//   • Card de "96% presença" — percentual é proibido pelo §9 da nota, e um `.num-card` de um número não
//     comporta os quatro estados. Virou a seção `.perfil-presenca`, redesenhada.

import { usePerfilVereador } from "@/lib/use-perfil-vereador";
import { derivarPerfil } from "@/lib/perfil-vereador-vista";
import { AzulejoMini } from "@/lib/charts/azulejo-mini";

const ICONE_INFO = (
  <svg
    width="14"
    height="14"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={2}
    aria-hidden="true"
  >
    <circle cx="12" cy="12" r="9" />
    <path d="M12 8h.01M11 12h1v4h1" />
  </svg>
);

export function SecaoPerfilVereador({ ente, vereadorId }: { ente: string; vereadorId: string }) {
  const { perfil, estado } = usePerfilVereador(ente, vereadorId);

  // sem skeleton, só o `aria-busy` honesto (consistência com secao-ficha.tsx): nada visível ainda.
  if (estado === "carregando") return <div aria-busy="true" />;

  if (estado === "erro") {
    return (
      <div className="em-breve" role="status">
        <p className="em-breve-titulo">Vereador não encontrado</p>
        <p className="em-breve-motivo">
          Não encontramos este perfil — o link pode estar incorreto, ou pode ter sido uma instabilidade
          passageira. Tente novamente em instantes ou volte à{" "}
          <a href={`/portal/casa/${ente}`}>página inicial do portal</a>.
        </p>
      </div>
    );
  }

  if (!perfil) return null; // fail-closed: nunca deveria acontecer com estado "pronto", mas nunca lança.
  const vista = derivarPerfil(perfil, ente);
  const { identidade, presenca, autoria, votos } = vista;

  return (
    <>
      {/* trilha honesta: o pai de um perfil NÃO é a lista de matérias, e não existe índice público de
          vereadores — a única subida verdadeira é a home do portal. */}
      <nav className="migalha" aria-label="Trilha">
        <a href={`/portal/casa/${ente}`}>Início</a>
        <span aria-hidden="true">›</span>
        <span>{identidade.nome}</span>
      </nav>

      <div className="perfil-cab">
        {/* 12 peças de azulejo — decoração pura, nenhuma carrega estado. */}
        <div className="cinta" aria-hidden="true">
          <i />
          <i />
          <i />
          <i />
          <i />
          <i />
          <i />
          <i />
          <i />
          <i />
          <i />
          <i />
        </div>
        <div className="perfil-corpo">
          <span className="perfil-foto" aria-hidden="true">
            {identidade.iniciais}
          </span>
          <div className="perfil-id">
            <h1>{identidade.nome}</h1>
            {identidade.nomeSecundario && <p className="nome-civil">{identidade.nomeSecundario}</p>}
            {identidade.papel && <p className="papel">{identidade.papel}</p>}
            <div className="perfil-tags">
              {identidade.cargoMesa && <span className="ptag mesa">{identidade.cargoMesa}</span>}
              {identidade.comissoes.map((c) => (
                <span className="ptag" key={c}>
                  {c}
                </span>
              ))}
            </div>
            {identidade.comissoesVazio && <p className="nota-secao">{identidade.comissoesVazio}</p>}
          </div>
        </div>
      </div>

      {/* três cards, e nenhum deles é de presença: o número de presença não cabe num card de um número
          só (quatro estados) e percentual é proibido. */}
      <div className="nums">
        <div className="num-card">
          <b>{autoria.materiasTotal}</b>
          <span>matérias de autoria</span>
        </div>
        <div className="num-card">
          <b>{autoria.normasDeAutoria}</b>
          <span>viraram lei</span>
        </div>
        <div className="num-card">
          <b>{autoria.votosTotal}</b>
          <span>votos nominais</span>
        </div>
      </div>

      <section className="secao perfil-secao perfil-presenca" aria-labelledby="presenca-titulo">
        <h2 id="presenca-titulo">Presença em sessões</h2>

        {presenca.tipo === "sem-janela" && (
          <div className="presenca-bloco">
            <p className="presenca-titulo">
              <b>{presenca.titulo}</b>
            </p>
            <p className="presenca-texto">{presenca.texto}</p>
          </div>
        )}

        {presenca.tipo === "sem-sessao" && (
          <div className="presenca-bloco">
            <p className="presenca-texto">{presenca.texto}</p>
          </div>
        )}

        {presenca.tipo === "fracao" && (
          <div className="presenca-bloco">
            {/* os dois números aparecem SEMPRE juntos, ou nenhum dos dois. Nunca um percentual. */}
            <p className="presenca-fracao">
              <b>{presenca.presente}</b>
              <span> de </span>
              <b>{presenca.total}</b>
            </p>
            <p className="presenca-frase">
              {presenca.frase.map((t, i) =>
                t.forte ? <b key={i}>{t.texto}</b> : <span key={i}>{t.texto}</span>,
              )}
            </p>
            {presenca.ressalva && <p className="nota-secao">{presenca.ressalva}</p>}
          </div>
        )}

        <p className="nota-secao">
          {ICONE_INFO}
          {presenca.marco}
        </p>
        {"ata" in presenca && (
          <p className="nota-secao">
            {ICONE_INFO}
            {presenca.ata}
          </p>
        )}
      </section>

      <section className="secao perfil-secao" aria-labelledby="autoria-titulo">
        <h2 id="autoria-titulo">Matérias de autoria</h2>
        {autoria.linhas.length > 0 && (
          <div className="mlista">
            {autoria.linhas.map((l) => (
              <a className="mrow" href={l.href} key={l.proposicaoId}>
                <span className="num">{l.ref}</span>
                <span className="tit">
                  <b>{l.ementa}</b>
                </span>
                <AzulejoMini estagios={l.estagios} rotuloAria={l.rotuloAria} />
              </a>
            ))}
          </div>
        )}
        {autoria.vazio && <p className="em-breve-motivo">{autoria.vazio}</p>}
        {autoria.truncamento && (
          <p className="nota-secao">
            {ICONE_INFO}
            {autoria.truncamento}
          </p>
        )}
        <p className="nota-secao">
          {ICONE_INFO}
          {autoria.recorteAcervo}
        </p>
      </section>

      <section className="secao perfil-secao" aria-labelledby="votos-titulo">
        <h2 id="votos-titulo">
          Como votou <span className="h-nota">· votações públicas recentes</span>
        </h2>
        {votos.linhas.length > 0 && (
          <div className="votos">
            {votos.linhas.map((v) => (
              <div className="voto" key={v.votacaoId}>
                <span className="tit">
                  {v.ementa ?? v.rotulo}
                  <span>
                    {v.rotulo} · {v.quando}
                  </span>
                </span>
                {/* chip do chassi: cor NUNCA é o único sinal — o rótulo textual vai junto. */}
                <span className={`chip ${v.votoClasse}`}>{v.votoRotulo}</span>
              </div>
            ))}
          </div>
        )}
        {votos.vazio && <p className="em-breve-motivo">{votos.vazio}</p>}
        {votos.truncamento && (
          <p className="nota-secao">
            {ICONE_INFO}
            {votos.truncamento}
          </p>
        )}
        <p className="nota-secao">
          {ICONE_INFO}
          Apenas votações abertas e nominais são individualizadas. Votações secretas, quando previstas no
          regimento, não mostram o voto de cada vereador.
        </p>
      </section>

      <section className="secao perfil-secao" aria-labelledby="contato-titulo">
        <h2 id="contato-titulo">Contato institucional</h2>
        <div className="contato">
          <svg
            width="18"
            height="18"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth={2}
            aria-hidden="true"
          >
            <path d="M4 4h16v16H4z" />
            <path d="m4 6 8 6 8-6" />
          </svg>
          {/* a Ouvidoria é citada sem link: ela não tem rota pública nesta fatia, e um link morto é pior
              que a menção. O e-SIC linka a âncora REAL do balcão na home do portal. */}
          <span>
            Para tratar de assuntos da Câmara, use os canais oficiais: o <b>Protocolo Geral</b> da Casa ou a{" "}
            <b>Ouvidoria</b>. Pedidos de informação pública seguem pelo{" "}
            <a href={`/portal/casa/${ente}#esic-titulo`}>e-SIC</a>.
          </span>
        </div>
      </section>
    </>
  );
}
