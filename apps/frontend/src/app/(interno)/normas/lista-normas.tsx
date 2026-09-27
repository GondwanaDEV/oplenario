"use client";

// A lista das normas: as da Casa e do Município (curadoria da secretaria) e as de referência federal/estadual
// (curadoria do produto). Cada uma diz qual versão vale e se há uma esperando conferência.

import Link from "next/link";
import { comToken } from "@/lib/nav";
import {
  linhaDaEmConferencia,
  linhaDaVigente,
  rotuloDaCamada,
  rotuloDaEspecie,
  type NormaResumoOut,
} from "@/lib/normas-vista";
import { useNormas } from "@/lib/use-normas";

function Cartao({ n, token }: { n: NormaResumoOut; token: string | null }) {
  return (
    <li className="normas-item">
      <p className="normas-tipo">
        {rotuloDaEspecie(n.norma.especie)} · {rotuloDaCamada(n.norma.camada)}
      </p>
      <p className="normas-titulo">{n.norma.titulo}</p>
      {n.vigente ? (
        <p className="normas-detalhe">
          <Link href={comToken(`/normas/versoes/${n.vigente.id}`, token)}>Ler o texto vigente</Link> · {linhaDaVigente(n.vigente)}
        </p>
      ) : (
        <p className="normas-detalhe">Ainda sem versão vigente: nada desta norma vale até alguém conferir.</p>
      )}
      {n.emConferencia && (
        <div className="normas-pendente">
          <p>{linhaDaEmConferencia(n.emConferencia)}</p>
          <Link className="btn btn-primaria" href={comToken(`/normas/versoes/${n.emConferencia.id}`, token)}>
            Conferir
          </Link>
        </div>
      )}
    </li>
  );
}

export function ListaNormas({ token = null }: { token?: string | null }) {
  const { estado } = useNormas(token);
  const normas = estado.fase === "pronto" ? estado.dado.normas : [];
  const daCasa = normas.filter((n) => n.norma.daCasa);
  const referencia = normas.filter((n) => !n.norma.daCasa);
  return (
    <main className="envelope normas">
      <header className="normas-cabeca">
        <div>
          <h1>Normas da Casa</h1>
          <p className="normas-sub">
            A Lei Orgânica, o Regimento Interno e as leis que o assistente consulta. O sistema quebra o texto em artigos,
            parágrafos e incisos; nada vale até uma pessoa conferir e publicar.
          </p>
        </div>
        <Link className="btn btn-primaria" href={comToken("/normas/nova", token)}>
          Importar texto
        </Link>
      </header>

      {estado.fase === "carregando" && <p role="status">Carregando…</p>}
      {estado.fase === "erro" && <p className="normas-erro" role="alert">{estado.mensagem}</p>}
      {estado.fase === "pronto" && (
        <>
          <section aria-labelledby="normas-casa">
            <h2 id="normas-casa" className="normas-secao">Da Câmara e do Município</h2>
            {daCasa.length === 0 ? (
              <p className="normas-vazio">
                Nenhuma norma importada ainda. Comece pela Lei Orgânica e pelo Regimento Interno: são as que mais aparecem
                nas perguntas sobre prazos, quórum e rito.
              </p>
            ) : (
              <ul className="normas-lista">{daCasa.map((n) => <Cartao key={n.norma.id} n={n} token={token} />)}</ul>
            )}
          </section>
          {referencia.length > 0 && (
            <section aria-labelledby="normas-ref">
              <h2 id="normas-ref" className="normas-secao">Federais e estaduais</h2>
              <ul className="normas-lista">{referencia.map((n) => <Cartao key={n.norma.id} n={n} token={token} />)}</ul>
            </section>
          )}
        </>
      )}
    </main>
  );
}
