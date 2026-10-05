"use client";

// Coluna lateral do Dashboard da Mesa — porta .rail de paineis-mesa.html. Duas coisas, nesta ordem:
//   1. a sessão EM CURSO (ou suspensa) agora, com a porta para a condução — antes só existia a próxima, e a
//      Mesa abria o painel no meio de uma sessão sem vê-la;
//   2. a próxima sessão AGENDADA (de sliSessoes, já disponível), sem quórum-de-ciência/checklist (carry —
//      ciência de convocação não existe no domínio ainda, spec §7).
// Data e hora saem no fuso da CASA (America/Fortaleza), como o resto do app (`diaLocal`/`horaLocal`):
// `toLocaleString` sem `timeZone` mostrava o relógio do navegador — e, no render do servidor (UTC), o dia
// errado para sessões noturnas.

import Link from "next/link";
import { diaLocal, horaLocal } from "@/lib/calendario-vista";
import { formatarDataSimples } from "@/lib/formatar-data";
import { comToken } from "@/lib/nav";
import type { SliSessaoOut } from "@/lib/use-mesa";

/** `2026-09-20T01:30:00Z` -> `19/09/2026 às 22h30` (relógio da Casa). Instante inválido sai cru, nunca lança. */
function dataEHoraDaCasa(iso: string): string {
  const dia = diaLocal(iso);
  if (dia === null) return iso;
  const hora = horaLocal(iso);
  return hora ? `${formatarDataSimples(dia)} às ${hora}` : formatarDataSimples(dia);
}

const ms = (iso?: string | null): number => {
  const t = iso ? new Date(iso).getTime() : NaN;
  return Number.isNaN(t) ? -Infinity : t;
};

/** Em curso antes de suspensa; dentro do grupo, a iniciada mais recentemente primeiro (a lista do servidor
 *  vem da mais antiga para a mais nova — ordem do dashboard para achar sessão travada, não a desta coluna). */
function sessoesAndando(lista: SliSessaoOut[]): SliSessaoOut[] {
  return lista
    .filter((s) => s.situacao === "em_curso" || s.situacao === "suspensa")
    .sort((a, b) => {
      const ga = a.situacao === "em_curso" ? 0 : 1;
      const gb = b.situacao === "em_curso" ? 0 : 1;
      return ga - gb || ms(b.abertaEm) - ms(a.abertaEm);
    });
}

/** A agendada mais próxima NO FUTURO; sem nenhuma futura, a mais próxima de hoje. Sem data vai por último. A
 *  lista do servidor não vem por data da sessão, então o primeiro `agendada` dela não é "a próxima". */
function proximaAgendada(lista: SliSessaoOut[], agora: Date): SliSessaoOut | undefined {
  const agendadas = lista.filter((s) => s.situacao === "agendada");
  const comData = agendadas.filter((s) => ms(s.agendadaPara) !== -Infinity);
  const futuras = comData
    .filter((s) => ms(s.agendadaPara) >= agora.getTime())
    .sort((a, b) => ms(a.agendadaPara) - ms(b.agendadaPara));
  if (futuras.length > 0) return futuras[0];
  const passadas = comData.sort((a, b) => ms(b.agendadaPara) - ms(a.agendadaPara));
  return passadas[0] ?? agendadas[0];
}

/**
 * Achado da revisão adversarial (fatia "truncamento-familia" sitio a, conserto): este é o SEGUNDO
 * consumidor de GET /paineis/sli/sessoes (o primeiro é /pauta-convocacao, que já avisa via
 * `avisoCorteSessoes`) — mas ele só recebia `sliSessoes`, nunca `sliSessoesTotal`. Quando a sessão
 * "agendada" caía fora do corte, a busca `.find` não achava nada e o rail AFIRMAVA "Nenhuma sessão
 * agendada." — o contrário do que o servidor sabe. `sliSessoesTotal` chega como `null` só enquanto o
 * fetch ainda não resolveu (mesmo idioma dos outros campos de `useMesa`), nunca "sem corte".
 */
export function ProximaSessaoRail({
  sliSessoes,
  sliSessoesTotal = null,
  token = null,
  agora,
}: {
  sliSessoes: SliSessaoOut[] | null;
  sliSessoesTotal?: number | null;
  /** Token dev, preservado no link para a condução (mesmo idioma de `comToken` nas outras telas). */
  token?: string | null;
  /** Relógio injetável só para o teste; em produção, o instante da renderização. */
  agora?: Date;
}) {
  const lista = sliSessoes ?? [];
  const andando = sessoesAndando(lista);
  const proxima = proximaAgendada(lista, agora ?? new Date());
  const truncado = sliSessoesTotal != null && sliSessoesTotal > lista.length;
  return (
    <aside className="rail" aria-label="Sessões">
      {andando.length > 0 && (
        <section className="bloco" aria-labelledby="em-curso-titulo">
          <div className="bloco-cabeca"><h2 id="em-curso-titulo">Em curso agora</h2></div>
          <div className="bloco-corpo">
            <ul className="fila">
              {andando.map((s) => (
                <li key={s.sessaoId} className="fila-item">
                  <div className="fila-txt">
                    {s.situacao === "suspensa" && <p><b>Sessão suspensa</b></p>}
                    <p>
                      {s.abertaEm
                        ? `Aberta em ${dataEHoraDaCasa(s.abertaEm)}.`
                        : "Hora de abertura não registrada."}
                    </p>
                  </div>
                  <Link className="btn btn-primaria" href={comToken(`/sessoes/${encodeURIComponent(s.sessaoId)}/conduzir`, token)}>
                    Conduzir a sessão
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        </section>
      )}
      <section className="bloco" aria-labelledby="proxima-titulo">
        <div className="bloco-cabeca"><h2 id="proxima-titulo">Próxima sessão</h2></div>
        <div className="bloco-corpo">
          {proxima ? (
            <>
              <p>Agendada para {proxima.agendadaPara ? dataEHoraDaCasa(proxima.agendadaPara) : "data a definir"}.</p>
              <p>
                <Link className="btn btn-contorno" href={comToken(`/sessoes/${encodeURIComponent(proxima.sessaoId)}/conduzir`, token)}>
                  Abrir a sessão
                </Link>
              </p>
              <p className="nota-gap">O registro de ciência da convocação e o checklist de prontidão da sessão chegam numa próxima entrega.</p>
            </>
          ) : truncado ? (
            <p role="status" className="aviso-corte">
              Mostrando <b>{lista.length} de {sliSessoesTotal}</b> sessões — pode haver uma sessão
              agendada fora desta lista.{" "}
              <Link href={comToken("/calendario", token)}>Veja todas as sessões no calendário.</Link>
            </p>
          ) : (
            <p>Nenhuma sessão agendada.</p>
          )}
        </div>
      </section>
    </aside>
  );
}
