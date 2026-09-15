"use client";

// Recorte "lente jurídico" — em-breve por construção (spec §7: incidente LGPD + grant de acesso de
// suporte não existem no domínio; domínio de segurança inteiro novo, fora do escopo desta fatia).

export function LenteJuridico() {
  return (
    <section className="bloco" aria-labelledby="juridico-titulo">
      <div className="bloco-cabeca"><h2 id="juridico-titulo">Recorte jurídico</h2></div>
      <div className="bloco-corpo">
        <p className="nota-gap">
          O registro de incidentes de LGPD e a concessão de acesso de suporte chegam numa próxima entrega.
        </p>
      </div>
    </section>
  );
}
