"use client";

// "Próxima sessão" — porta .rail de paineis-mesa.html. Mostra a próxima sessão AGENDADA (de sliSessoes,
// já disponível) sem quórum-de-ciência/checklist (carry — ciência de convocação não existe no domínio
// ainda, spec §7).

import type { SliSessaoOut } from "@/lib/use-mesa";

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
}: {
  sliSessoes: SliSessaoOut[] | null;
  sliSessoesTotal?: number | null;
}) {
  const lista = sliSessoes ?? [];
  const proxima = lista.find((s) => s.situacao === "agendada");
  const truncado = sliSessoesTotal != null && sliSessoesTotal > lista.length;
  return (
    <aside className="rail" aria-label="Próxima sessão">
      <section className="bloco" aria-labelledby="proxima-titulo">
        <div className="bloco-cabeca"><h2 id="proxima-titulo">Próxima sessão</h2></div>
        <div className="bloco-corpo">
          {proxima ? (
            <>
              <p>Agendada para {proxima.agendadaPara ? new Date(proxima.agendadaPara).toLocaleString("pt-BR") : "data a definir"}.</p>
              <p className="nota-gap">Quórum de ciência e checklist de prontidão seguem em breve (carry — ciência de convocação ainda não existe no domínio).</p>
            </>
          ) : truncado ? (
            <p role="status" className="aviso-corte">
              Mostrando <b>{lista.length} de {sliSessoesTotal}</b> sessões — pode haver uma sessão
              agendada fora desta lista. Confira o painel de sessões completo.
            </p>
          ) : (
            <p>Nenhuma sessão agendada.</p>
          )}
        </div>
      </section>
    </aside>
  );
}
