"use client";

// ADR-0017 (adendo de 05/10/2026) — os atos da Operação iniciados cujo desfecho a corrente não registrou. Só leitura:
// o começo do ato está selado e o fim não, então o ato pode não ter acontecido ou ter acontecido sem o registro. Quem
// confere é o operador, na Câmara. Só aparece quando há item; "tudo certo" não é um cartão, é a ausência deste bloco.
// A mesma frase da trilha da Casa (`/auditoria`): "confira se o ato aconteceu".

import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import {
  avisoDeCorte, camaraDoAto, quandoIniciou, quemIniciou, toleranciaEmPalavras, tituloDosAtos,
} from "@/lib/atos-sem-desfecho-vista";
import { rotuloAcao, useAtosSemDesfecho } from "@/lib/use-operacao";

export default function AtosSemDesfecho() {
  const { token } = useAuth();
  const { atos, estado } = useAtosSemDesfecho(token);

  // sessão vencida: a página já avisa e manda entrar de novo; carregando: nada a mostrar ainda
  if (estado === "carregando" || estado === "sem-sessao") return null;
  // a conferência que não rodou não pode parecer conferência sem achados
  if (!atos) {
    return (
      <p className="op-aviso" role="status">
        Não deu para conferir agora se há atos da Operação sem desfecho registrado.
      </p>
    );
  }
  if (atos.total === 0) return null;

  const corte = avisoDeCorte(atos);
  return (
    <section className="op-fila" aria-labelledby="titulo-sem-desfecho">
      <h2 id="titulo-sem-desfecho">{tituloDosAtos(atos.total)}</h2>
      <p className="aj">
        O começo do ato está na corrente da Operação e o fim não. O ato pode não ter acontecido, ou ter acontecido e o
        registro do fim ter falhado. Só entram atos iniciados há mais de {toleranciaEmPalavras(atos.toleranciaSegundos)}.
        {corte ? ` ${corte}` : ""}
      </p>
      <ul>
        {atos.atos.map((a) => {
          const camara = camaraDoAto(a);
          return (
            <li key={a.id}>
              <b>{rotuloAcao(a.acao)}</b>
              <span>
                {camara ? `${camara} · ` : ""}{quemIniciou(a)} · {quandoIniciou(a.em)}
              </span>
              {a.enteId && (
                <Link className="op-link" href={comToken(`/operacao/casas/${a.enteId}`, token)}
                  aria-label={`Abrir ${camara ?? "a câmara"}`}>Abrir a câmara</Link>
              )}
              <span className="op-fila-nota">Confira se o ato aconteceu.</span>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
