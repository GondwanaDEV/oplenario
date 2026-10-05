"use client";

// "Minha atuação" — o espelho de atuação institucional do vereador (Onda E, `vereador-estatisticas`).
// Porte de produto/design-system/o-plenario/telas/vereador-estatisticas.html com o que o dado sustenta: o
// que ficou de fora do design (5 grupos fixos, percentual de presença, presença por mês, ausência
// justificada, seletor de período) está explicado em lib/atuacao-vista.ts. O chrome (topo + tabbar) é do
// layout (vereador).
//
// "Como você votou" vem da rota autenticada do vereador (GET /meu/votos), não do perfil público: aqui ele
// vê também o voto dado em sessão secreta ou fechada ao público, marcado como "só você vê". O perfil público
// dele no portal não muda.

import { useAuth } from "@/lib/auth";
import { useAtuacao } from "@/lib/use-atuacao";
import { derivarAtuacao, type AtuacaoVista, type LinhaVotoAtuacao } from "@/lib/atuacao-vista";
import { planificarFrase } from "@/lib/perfil-vereador-vista";
import "./atuacao.css";

export default function PaginaAtuacao() {
  const { token } = useAuth();
  const estado = useAtuacao(token);

  if (estado.fase === "carregando") {
    return (
      <div className="atuacao-estado">
        <h1>Carregando…</h1>
      </div>
    );
  }
  if (estado.fase === "sem-vereador") {
    return (
      <div className="atuacao-estado">
        <h1>Minha atuação</h1>
        <p>Seu acesso ainda não está ligado a um cadastro de vereador nesta Casa. Fale com a secretaria.</p>
      </div>
    );
  }
  if (estado.fase === "erro") {
    return (
      <div className="atuacao-estado">
        <h1>Não foi possível carregar sua atuação</h1>
        <p>Tente novamente em instantes.</p>
      </div>
    );
  }
  return <Atuacao vista={derivarAtuacao(estado.perfil, estado.painel, estado.votos)} />;
}

const ICONE_CADEADO = (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
    <rect x="5" y="11" width="14" height="9" rx="2" />
    <path d="M8 11V8a4 4 0 0 1 8 0v3" />
  </svg>
);

function LinhaVoto({ v }: { v: LinhaVotoAtuacao }) {
  return (
    <li className="voto-l">
      <span className="tit">
        {v.titulo}
        <span>{v.subtitulo}</span>
      </span>
      <span className={`chip ${v.votoClasse}`}>{v.votoRotulo}</span>
      {v.aviso && (
        <p className="voto-aviso">
          {ICONE_CADEADO}
          {v.aviso}
        </p>
      )}
    </li>
  );
}

function Atuacao({ vista }: { vista: AtuacaoVista }) {
  const { presenca } = vista;
  return (
    <div className="atuacao">
      <h1 className="pg-tit">Minha atuação</h1>
      <p className="pg-sub">Seus números no mandato, a partir dos registros oficiais da Casa.</p>

      <ul className="nums" aria-label="Números do mandato">
        {vista.cartoes.map((c) => (
          <li key={c.rotulo} className="ncard">
            <b>{c.valor}</b>
            <span>{c.rotulo}</span>
          </li>
        ))}
      </ul>

      <h2 className="secao-tit">Proposições por situação</h2>
      <div className="status-list">
        {vista.situacoesVazio ? (
          <p className="vazio">{vista.situacoesVazio}</p>
        ) : (
          <ul aria-label="Proposições de autoria por situação">
            {vista.situacoes.map((s) => (
              <li key={s.estado} className="srow">
                <span className="lab">{s.rotulo}</span>
                <span className="qt">{s.quantidade}</span>
                <span className="bar" aria-hidden="true">
                  <i style={{ width: `${s.largura}%` }} />
                </span>
              </li>
            ))}
          </ul>
        )}
        {vista.situacoesRecorte && <p className="recorte">{vista.situacoesRecorte}</p>}
      </div>

      <h2 className="secao-tit">Presença em sessões</h2>
      <div className="pres">
        {presenca.tipo === "fracao" ? (
          <>
            <div className="pres-top">
              <b>{presenca.presente}</b>
              <span>de {presenca.total} sessões com registro de presença</span>
            </div>
            <p className="pres-frase">{planificarFrase(presenca.frase)}</p>
            {presenca.ressalva && <p className="recorte">{presenca.ressalva}</p>}
          </>
        ) : presenca.tipo === "sem-janela" ? (
          <p className="pres-frase">
            <b>{presenca.titulo}</b> {presenca.texto}
          </p>
        ) : (
          <p className="pres-frase">{presenca.texto}</p>
        )}
        <p className="recorte">{presenca.marco}</p>
      </div>

      <h2 className="secao-tit">Como você votou</h2>
      {vista.votosVazio ? (
        <p className="vazio">{vista.votosVazio}</p>
      ) : (
        <>
          <ul className="votos-g" aria-label="Votos nominais por opção">
            <li className="vg">
              <b>{vista.votos.sim}</b>
              <span>a favor</span>
            </li>
            <li className="vg">
              <b>{vista.votos.nao}</b>
              <span>contra</span>
            </li>
            <li className="vg">
              <b>{vista.votos.abstencao}</b>
              <span>abstenções</span>
            </li>
          </ul>
          <ul className="votos-l" aria-label="Seus votos nominais, do mais recente ao mais antigo">
            {vista.linhasVotos.map((v) => (
              <LinhaVoto key={v.votacaoId} v={v} />
            ))}
          </ul>
          {vista.votosTruncamento && <p className="recorte">{vista.votosTruncamento}</p>}
        </>
      )}

      {vista.notas.map((n) => (
        <p key={n} className="nota">
          {n}
        </p>
      ))}
    </div>
  );
}
