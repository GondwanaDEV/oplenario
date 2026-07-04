"use client";

// Recorte "lente jurídico" — em-breve por construção (spec §7: incidente LGPD + grant de acesso de
// suporte não existem no domínio; domínio de segurança inteiro novo, fora do escopo desta fatia).

export function LenteJuridico() {
  return (
    <section className="bloco" aria-labelledby="juridico-titulo">
      <div className="bloco-cabeca"><h2 id="juridico-titulo">Recorte jurídico</h2></div>
      <div className="bloco-corpo">
        <p className="nota-gap">
          Incidentes LGPD e grants de acesso de suporte seguem em breve — domínio de segurança ainda não
          modelado (carry documentado no spec desta fatia).
        </p>
      </div>
    </section>
  );
}
