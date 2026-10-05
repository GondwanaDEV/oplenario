"use client";

// LEIS E NORMAS no portal do cidadão (GET /portal/casa/{ente}/legislacao): a lista do acervo publicado — a lei como foi
// promulgada e publicada — e a ficha de cada norma. Só o que o backend entrega: tipo, número, ano, ementa, data e
// veículo da publicação, o identificador oficial (URN LexML) e o link para a matéria de origem. O texto da norma vem do
// artefato de publicação (download), não da ficha. Filtro por tipo/ano/número, que a rota suporta; a rota não pagina
// e corta em um teto no servidor, e por isso a lista diz quando o acervo é maior do que mostra (o total vem sem teto).
// Não há rota de escrita para promulgar/publicar norma ainda: Casa sem norma publicada é o estado vazio.

import { useEffect, useState } from "react";
import type { NormaOut, NormasOut } from "@/lib/contrato-portal.gen";
import { formatarData } from "@/lib/formatar-data";
import { TIPOS_DE_NORMA, consultaDoFiltro, filtroAtivo, rotuloDoTipo, tituloDaNorma, type FiltroDeLeis } from "@/lib/leis-vista";
import { buscarPublicoDetalhado, type BuscaPublica } from "@/lib/portal-api";
import "./leis-e-normas.css";

type Carga<T> = { fase: "carregando" } | { fase: "pronto"; busca: BuscaPublica<T> };

function useBuscaPublica<T>(segmentos: string[], consulta: Record<string, string>): Carga<T> {
  const chave = JSON.stringify([segmentos, consulta]);
  const [r, setR] = useState<{ de: string | null; carga: Carga<T> }>({ de: null, carga: { fase: "carregando" } });
  useEffect(() => {
    let vivo = true;
    const [segs, q] = JSON.parse(chave) as [string[], Record<string, string>];
    (async () => {
      const busca = await buscarPublicoDetalhado<T>(segs, q);
      if (vivo) setR({ de: chave, carga: { fase: "pronto", busca } });
    })();
    return () => {
      vivo = false;
    };
  }, [chave]);
  return r.de === chave ? r.carga : { fase: "carregando" };
}

function rotaDaLista(ente: string): string {
  return `/portal/casa/${encodeURIComponent(ente)}/leis`;
}

function plural(n: number): string {
  return `${n} ${n === 1 ? "norma publicada" : "normas publicadas"}`;
}

export function LeisDaCasa({ ente, filtro }: { ente: string; filtro: FiltroDeLeis }) {
  const carga = useBuscaPublica<NormasOut>([ente, "legislacao"], consultaDoFiltro(filtro));
  const filtrando = filtroAtivo(filtro);
  const busca = carga.fase === "pronto" ? carga.busca : null;
  const dado = busca?.estado === "ok" ? busca.dado : null;
  return (
    <div className="leis-e-normas">
      <div className="pg-cab">
        <span className="rotulo-secao">Legislação</span>
        <h1>Leis e normas</h1>
        <p className="sub">
          As leis e demais normas aprovadas pela Câmara e publicadas, como foram promulgadas. Abra uma norma para ver a
          ementa, a data da publicação e baixar o texto.
        </p>
      </div>

      <form className="ln-filtro" role="search" aria-label="Filtrar leis e normas" method="get" action={rotaDaLista(ente)}>
        <div className="ln-campo">
          <label htmlFor="ln-tipo">Tipo</label>
          <select id="ln-tipo" name="tipo" defaultValue={filtro.tipo} key={`t-${filtro.tipo}`}>
            <option value="">Todos os tipos</option>
            {TIPOS_DE_NORMA.map((t) => (
              <option key={t.valor} value={t.valor}>
                {t.rotulo}
              </option>
            ))}
          </select>
        </div>
        <div className="ln-campo">
          <label htmlFor="ln-ano">Ano</label>
          <input id="ln-ano" name="ano" inputMode="numeric" pattern="[0-9]{1,9}" placeholder="2026" defaultValue={filtro.ano} key={`a-${filtro.ano}`} />
        </div>
        <div className="ln-campo">
          <label htmlFor="ln-numero">Número</label>
          <input id="ln-numero" name="numero" inputMode="numeric" pattern="[0-9]{1,9}" placeholder="12" defaultValue={filtro.numero} key={`n-${filtro.numero}`} />
        </div>
        <div className="ln-acoes">
          <button type="submit" className="btn">
            Filtrar
          </button>
          {filtrando && <a href={rotaDaLista(ente)}>Limpar filtros</a>}
        </div>
      </form>

      {filtro.ignorados.map((campo) => (
        <p key={campo} className="ln-nota" role="note">
          O {campo} informado não é válido e foi ignorado.
        </p>
      ))}

      {carga.fase === "carregando" && <p className="estado">Carregando…</p>}
      {busca && busca.estado !== "ok" && (
        <p className="estado" role="alert">
          Não foi possível carregar as leis agora. Tente novamente em instantes.
        </p>
      )}
      {dado && dado.normas.length === 0 && (
        <p className="estado">{filtrando ? "Nenhuma norma encontrada com esses filtros." : "Esta Câmara ainda não publicou leis aqui."}</p>
      )}
      {dado && dado.normas.length > 0 && (
        <>
          <p className="ln-total">{plural(dado.normasTotal)}</p>
          {dado.normasTotal > dado.normas.length && (
            <p className="ln-aviso" role="status">
              Mostrando {dado.normas.length} de {dado.normasTotal} normas. Use os filtros de tipo, ano e número para chegar às
              demais.
            </p>
          )}
          <ul className="ln-lista" aria-label="Leis e normas publicadas">
            {dado.normas.map((n) => (
              <li key={n.normaId}>
                <a className="ln-titulo" href={`${rotaDaLista(ente)}/${encodeURIComponent(n.normaId)}`}>
                  {tituloDaNorma(n)}
                </a>
                <p className="ln-ementa">{n.ementa}</p>
                <p className="ln-meta">Publicada em {formatarData(n.publicadoEm)}</p>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

export function FichaDaNorma({ ente, normaId }: { ente: string; normaId: string }) {
  const carga = useBuscaPublica<NormaOut>([ente, "legislacao", normaId], {});
  const lista = rotaDaLista(ente);
  const busca = carga.fase === "pronto" ? carga.busca : null;
  const norma = busca?.estado === "ok" ? busca.dado : null;
  return (
    <div className="leis-e-normas">
      <nav className="migalha" aria-label="Trilha">
        <a href={`/portal/casa/${encodeURIComponent(ente)}`}>Início</a>
        <span aria-hidden="true">›</span>
        <a href={lista}>Leis e normas</a>
        {norma && (
          <>
            <span aria-hidden="true">›</span>
            <span>{tituloDaNorma(norma)}</span>
          </>
        )}
      </nav>

      {carga.fase === "carregando" && <p className="estado">Carregando a norma…</p>}
      {busca?.estado === "erro" && (
        <p className="estado" role="alert">
          Não foi possível carregar esta norma agora. Tente novamente em instantes.
        </p>
      )}
      {busca?.estado === "nao-encontrado" && (
        <>
          <p className="estado">Esta norma não foi encontrada nesta Câmara.</p>
          <p>
            <a className="ln-voltar" href={lista}>
              Ver todas as leis e normas
            </a>
          </p>
        </>
      )}
      {norma && (
        <article className="ln-ficha" aria-label={tituloDaNorma(norma)}>
          <div className="pg-cab">
            <span className="rotulo-secao">{rotuloDoTipo(norma.tipoNorma)}</span>
            <h1>{tituloDaNorma(norma)}</h1>
          </div>
          <p className="ln-ementa-ficha">{norma.ementa}</p>
          <dl className="ln-dl">
            <dt>Publicada em</dt>
            <dd>{formatarData(norma.publicadoEm)}</dd>
            <dt>Veículo da publicação</dt>
            <dd>{norma.veiculoPublicacao}</dd>
            <dt>Identificador oficial</dt>
            <dd className="ln-urn">{norma.urn}</dd>
          </dl>
          <div className="ln-links">
            <a className="btn" href={`/api/portal/casa/${encodeURIComponent(ente)}/legislacao/${encodeURIComponent(norma.normaId)}/artefato`}>
              Baixar o texto publicado
            </a>
            <a href={`/portal/casa/${encodeURIComponent(ente)}/materias/${encodeURIComponent(norma.proposicaoId)}`}>
              Ver a matéria que deu origem a esta norma
            </a>
          </div>
        </article>
      )}
    </div>
  );
}
