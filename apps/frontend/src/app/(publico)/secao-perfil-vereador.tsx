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

import type { ReactNode } from "react";
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

/** Forma do chip de voto, além da cor e do rótulo textual — é o SVG do `.vchip` da tela-fonte
 *  (`perfil-vereador-publico.html`), que o `.chip` do chassi já prevê (`.chip svg { flex: 0 0 auto }`).
 *  Por que ele volta: sob `(publico)/` a folha `proposicoes.css` NÃO é carregada, e é lá que mora o
 *  `.chip::before` (o quadradinho 7×7 que dá forma ao chip nas telas internas) — sem o SVG, este é o único
 *  chip do sistema sem NENHUM sinal de forma, e em P&B os três votos viram a mesma pílula.
 *  Chaveado pelo VOTO cru (não pela classe): fora do trio, nenhum ícone — fail-closed, nunca um ✓ chutado. */
const ICONE_VOTO: Record<string, ReactNode> = {
  sim: (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} aria-hidden="true">
      <path d="M5 12l5 5L20 6" />
    </svg>
  ),
  nao: (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} aria-hidden="true">
      <path d="M6 6l12 12M18 6L6 18" />
    </svg>
  ),
  abstencao: (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.4} aria-hidden="true">
      <path d="M6 12h12" />
    </svg>
  ),
};

export function SecaoPerfilVereador({ ente, vereadorId }: { ente: string; vereadorId: string }) {
  const { perfil, estado } = usePerfilVereador(ente, vereadorId);

  // NENHUMA string de destino nasce crua no JSX: `ente` vem do path param JÁ DECODIFICADO pelo Next, e a
  // vista codifica o dela (`encodeURIComponent`) desde a Task 5. Três hrefs escritos aqui interpolavam o
  // valor cru — na mesma árvore, o link da matéria saía `%2F` e a trilha saía com `/` de verdade. O ramo
  // que mais importa é o de ERRO logo abaixo: é justamente ele que renderiza quando o `ente` é malformado
  // (o backend coage para UUID e devolve 400), e ali um `../../` cru resolveria para FORA do portal.
  const hrefPortal = `/portal/casa/${encodeURIComponent(ente)}`;

  // sem skeleton (consistência com secao-ficha.tsx): nada VISÍVEL ainda. Mas `aria-busy` num <div> vazio
  // não anuncia coisa nenhuma — é atributo de estado, e sem região viva nem nome acessível o leitor de tela
  // encontra um <main> mudo. A região viva entra aqui, com texto em `.sr-only`, e o ramo de erro reusa o
  // MESMO nó raiz (<div role="status">) para que a troca seja uma MUTAÇÃO dentro de região preexistente —
  // região criada junto com o conteúdo costuma não ser anunciada por NVDA/JAWS.
  if (estado === "carregando") {
    return (
      <div role="status" aria-live="polite" aria-busy="true">
        <span className="sr-only">Carregando o perfil do vereador…</span>
      </div>
    );
  }

  if (estado === "erro") {
    return (
      <div className="em-breve" role="status" aria-live="polite">
        {/* o título NÃO decide entre as duas causas: `buscarPublico` colapsa 404 e falha de rede no mesmo
            `null` e a borda NÃO PODE afirmar qual ocorreu (é o que o hook documenta). "Vereador não
            encontrado" afirmava a inexistência de uma pessoa que pode existir e estar em exercício — e o
            título é o elemento de maior peso visual, lido isolado. */}
        <p className="em-breve-titulo">Não foi possível exibir este perfil</p>
        <p className="em-breve-motivo">
          Não encontramos este perfil — o link pode estar incorreto, ou pode ter sido uma instabilidade
          passageira. Tente novamente em instantes ou volte à{" "}
          <a href={hrefPortal}>página inicial do portal</a>.
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
        <a href={hrefPortal}>Início</a>
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
            {/* LISTA rotulada, não `<span>` soltos: a relação "isto é o cargo na Mesa e as comissões que
                esta pessoa integra" existia só na diagramação (WCAG 1.3.1), e o leitor de tela recebia os
                nomes crus encostados no texto da legislatura — enquanto o estado VAZIO ganhava uma frase
                completa. O rótulo do grupo vem da vista, como toda copy desta tela. */}
            {(identidade.cargoMesa || identidade.comissoes.length > 0) && (
              <ul className="perfil-tags" aria-label={identidade.comissoesRotulo}>
                {identidade.cargoMesa && <li className="ptag mesa">{identidade.cargoMesa}</li>}
                {identidade.comissoes.map((c) => (
                  <li className="ptag" key={c}>
                    {c}
                  </li>
                ))}
              </ul>
            )}
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
      {/* a declaração de recorte fica AQUI, no mesmo bloco dos números que ela qualifica — não duas seções
          abaixo. Os dois primeiros contadores sofrem o MESMO corte da lista (`autor_id IS NOT NULL`, sem
          replay na mig 0063): sem esta linha, "0 · matérias de autoria" em 28px lê como "esta pessoa não é
          autora de nada", que é falso para todo mandato anterior ao deploy. */}
      <p className="nota-secao">
        {ICONE_INFO}
        {autoria.recorteNumeros}
      </p>

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
                {/* título = ementa; quando a matéria não foi projetada o título JÁ é o rótulo de fallback,
                    e a sublinha vira só a data (a vista resolve isso — repetir a frase era o defeito). */}
                <span className="tit">
                  {v.ementa ?? v.rotulo}
                  <span>{v.subtitulo}</span>
                </span>
                {/* chip do chassi: cor NUNCA é o único sinal — forma (SVG) + rótulo textual vão junto. */}
                <span className={`chip ${v.votoClasse}`}>
                  {ICONE_VOTO[v.voto]}
                  {v.votoRotulo}
                </span>
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
            <a href={`${hrefPortal}#esic-titulo`}>e-SIC</a>.
          </span>
        </div>
      </section>
    </>
  );
}
