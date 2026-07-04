"use client";

// "Próxima sessão" — porta .rail de paineis-mesa.html. Mostra a próxima sessão AGENDADA (de sliSessoes,
// já disponível) sem quórum-de-ciência/checklist (carry — ciência de convocação não existe no domínio
// ainda, spec §7).

import type { SliSessaoOut } from "@/lib/use-mesa";

export function ProximaSessaoRail({ sliSessoes }: { sliSessoes: SliSessaoOut[] | null }) {
  const proxima = (sliSessoes ?? []).find((s) => s.situacao === "agendada");
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
          ) : (
            <p>Nenhuma sessão agendada.</p>
          )}
        </div>
      </section>
    </aside>
  );
}
