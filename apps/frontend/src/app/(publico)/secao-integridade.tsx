"use client";

// A ÂNCORA PÚBLICA da trilha de auditoria da Casa (ADR-0017, Eixo 5): o selo do fim de cada dia — a cabeça da cadeia
// selada naquele dia. Nenhum registro sai aqui (quem fez o quê é do controle interno); só o selo, que qualquer pessoa
// pode guardar: se alguém reescrevesse a trilha depois, o selo publicado deixaria de conferir.

import { useEffect, useState } from "react";
import { buscarPublico } from "@/lib/portal-api";
import { seloCurto, type SeloDoDia } from "@/lib/trilha-auditoria-vista";

type Estado = { fase: "carregando" } | { fase: "erro" } | { fase: "pronto"; selos: SeloDoDia[] };

const DIA = new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "2-digit", year: "numeric", timeZone: "UTC" });

export function SecaoIntegridade({ ente }: { ente: string }) {
  const [estado, setEstado] = useState<Estado>({ fase: "carregando" });
  useEffect(() => {
    let vivo = true;
    buscarPublico<{ selosDoDia: SeloDoDia[] }>(ente, "integridade").then((d) => {
      if (vivo) setEstado(d ? { fase: "pronto", selos: d.selosDoDia } : { fase: "erro" });
    });
    return () => {
      vivo = false;
    };
  }, [ente]);

  return (
    <section className="integridade" aria-labelledby="integridade-titulo">
      <h2 id="integridade-titulo">Integridade da trilha de auditoria</h2>
      <p className="sub">
        Cada ato no sistema da Câmara fica numa trilha selada, em que cada registro sela o anterior. Ao fim de cada dia,
        o selo da trilha é publicado aqui: se alguém a reescrevesse depois, o selo deixaria de conferir.
      </p>
      {estado.fase === "carregando" && <p className="estado">Carregando os selos…</p>}
      {estado.fase === "erro" && <p className="estado" role="alert">Não foi possível carregar os selos agora.</p>}
      {estado.fase === "pronto" && estado.selos.length === 0 && (
        <p className="estado">O primeiro selo sai ao fim do primeiro dia com atos registrados.</p>
      )}
      {estado.fase === "pronto" && estado.selos.length > 0 && (
        <ul className="selos" aria-label="Selos do dia">
          {estado.selos.map((s) => (
            <li key={s.dia}>
              <span className="dia">{DIA.format(new Date(`${s.dia}T00:00:00Z`))}</span>
              <span className="n">{s.seq.toLocaleString("pt-BR")} registros</span>
              <code title={s.selo}>{seloCurto(s.selo)}</code>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
