"use client";

// O LIVRO DE ATAS (Onda E, `livro-atas`) — o componente único das duas superfícies: a tela interna (/atas) e o
// portal do cidadão (/portal/casa/[ente]/atas). Porte de produto/design-system/o-plenario/telas/livro-atas.html:
// a ata aberta como folha de papel (só leitura) e a lombada com as demais. O que o design tem e o dado não sustenta
// está explicado em lib/livro-atas-vista.ts (sobretudo: o selo diz "Publicada", nunca "Aprovada").
//
// A ata aberta é a pedida na URL (`?sessao=`, e `&versao=` para uma versão anterior) ou, sem pedido, a mais recente.

import Link from "next/link";
import { derivarAta, derivarLivro, type LinhaLivroVista } from "@/lib/livro-atas-vista";
import { hrefAta, useAtaDoLivro, useLivroAtas, type FonteLivro } from "@/lib/use-livro-atas";
import { dicaDaAta, useDicaDaClara } from "@/app/(interno)/clara/dica";
import "./livro-atas.css";

export function LivroAtas({
  fonte,
  sessao,
  versao,
  nomeCasa,
}: {
  fonte: FonteLivro;
  sessao: string | null;
  versao: number | null;
  nomeCasa?: string | null;
}) {
  const hrefDe = (sessaoId: string, v?: number) => hrefAta(fonte, sessaoId, v);
  const livro = useLivroAtas(fonte);
  const vista = livro.fase === "pronto" ? derivarLivro(livro.dado) : null;
  const aberta = sessao ?? vista?.linhas[0]?.sessaoId ?? null;

  return (
    <div className="livro-atas">
      <div className="pg-cab">
        <span className="rotulo-secao">Livro de atas</span>
        <h1>Atas das sessões</h1>
        <p className="sub">
          O registro oficial das sessões. A ata entra no livro quando é publicada, depois da sessão; uma retificação
          vira nova versão e nunca apaga a anterior.
        </p>
      </div>

      {livro.fase === "carregando" && <p className="estado">Carregando o livro…</p>}
      {livro.fase === "erro" && (
        <p className="estado" role="alert">
          Não foi possível carregar o livro de atas agora. Tente novamente em instantes.
        </p>
      )}
      {vista?.vazio && <p className="estado">{vista.vazio}</p>}

      {aberta && (
        <>
          <h2 className="secao-tit">{sessao ? "Ata aberta" : "Última ata publicada"}</h2>
          <AtaAberta fonte={fonte} sessaoId={aberta} versao={versao} hrefDe={hrefDe} nomeCasa={nomeCasa} />
        </>
      )}

      {vista && vista.linhas.length > 0 && (
        <>
          <h2 className="secao-tit">Todas as atas</h2>
          <ul className="livro" aria-label="Atas publicadas">
            {vista.linhas.map((l) => (
              <Linha key={l.sessaoId} linha={l} aberta={l.sessaoId === aberta} href={hrefDe(l.sessaoId)} />
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

function Linha({ linha, aberta, href }: { linha: LinhaLivroVista; aberta: boolean; href: string }) {
  return (
    <li className="ata-row" aria-current={aberta ? "true" : undefined}>
      <span className="lomb" aria-hidden="true">
        {linha.numero}
      </span>
      <div className="ar-mid">
        <b>{linha.titulo}</b>
        <span>{linha.detalhe}</span>
      </div>
      <span className="quando">{linha.quando}</span>
      <span className={linha.retificada ? "schip schip-rev" : "schip schip-pub"}>
        {linha.retificada ? "retificada" : "publicada"}
      </span>
      {aberta ? (
        <span className="ler ler-atual">aberta</span>
      ) : (
        <Link className="ler" href={href} aria-label={`Ler a ata da ${linha.titulo}`}>
          Ler
        </Link>
      )}
    </li>
  );
}

function AtaAberta({
  fonte,
  sessaoId,
  versao,
  hrefDe,
  nomeCasa,
}: {
  fonte: FonteLivro;
  sessaoId: string;
  versao: number | null;
  hrefDe: (sessaoId: string, versao?: number) => string;
  nomeCasa?: string | null;
}) {
  const ata = useAtaDoLivro(fonte, sessaoId, versao);
  // A dica da Clara ("Nesta tela: Ata da 15ª Sessão Ordinária"), só na tela interna e com a ata já lida: o portal não
  // tem Clara.
  useDicaDaClara(fonte.tipo === "interno" && ata.fase === "pronto" ? dicaDaAta(ata.dado.sessao) : null);
  if (ata.fase === "carregando") return <p className="estado">Abrindo a ata…</p>;
  if (ata.fase === "erro") {
    return (
      <p className="estado" role="alert">
        Esta ata não está disponível. Ela pode não ter sido publicada, ou a versão pedida não existe.
      </p>
    );
  }
  const a = derivarAta(ata.dado);
  return (
    <article className="papel" aria-label={a.titulo}>
      <span className="selo-ap">{a.selo}</span>
      <div className="folha-topo">
        {nomeCasa && <span className="casa">{nomeCasa}</span>}
        <p className="tit">{a.titulo}</p>
        <p className="quando-sessao">{a.quando}</p>
      </div>
      {a.aviso && (
        <p className="aviso-versao" role="note">
          {a.aviso} <Link href={hrefDe(sessaoId)}>Ler a versão que vale</Link>
        </p>
      )}
      <div className="texto-ata">
        {a.paragrafos.map((p, i) => (
          <p key={i}>{p}</p>
        ))}
      </div>
      <dl className="proveniencia">
        <div>
          <dt>Redação</dt>
          <dd>
            {a.origem}
            {a.publicadaPor && ` ${a.publicadaPor}`}
          </dd>
        </div>
        <div>
          <dt>Plenário</dt>
          <dd>{a.leitura ?? "Ainda não apresentada em plenário."}</dd>
        </div>
        <div>
          <dt>Integridade</dt>
          <dd className="hash">{a.integridade}</dd>
        </div>
      </dl>
      {a.temRetificacao && (
        <div className="versoes">
          <p className="secao-ata">Versões publicadas</p>
          <ol aria-label="Versões publicadas desta ata">
            {a.versoes.map((v) => (
              <li key={v.versao} aria-current={v.exibida ? "true" : undefined}>
                {v.exibida ? <b>{v.rotulo} (esta)</b> : <Link href={hrefDe(sessaoId, v.versao)}>{v.rotulo}</Link>}
                {v.motivo && <span className="motivo"> — retificada: {v.motivo}</span>}
              </li>
            ))}
          </ol>
        </div>
      )}
    </article>
  );
}
