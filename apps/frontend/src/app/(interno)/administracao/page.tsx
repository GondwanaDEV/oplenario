"use client";

// Rota /administracao (interno) — a ÁREA DO ADMINISTRADOR DA CASA (ADR-0005). O 1º administrador de uma Casa
// provisionada pelo console do operador (ADR-0016) nasce só com o papel `admin_ente` e pousa aqui depois do login
// (`destinoPorPapeis`), em vez de cair na tela da cidadã.
//
// O que ele faz aqui: CONCEDER ACESSO aos vereadores — o form que vivia, inalcançável, dentro do cadastro da
// secretaria. A lista vem de GET /cadastros/vereadores (leitura aberta ao admin_ente pelo P2 da ADR-0005); a
// escrita segue segregada: o admin_ente não mantém o cadastro (isso é da secretaria) e a secretaria não concede
// acesso. O form é o mesmo componente (3 passos, acesso por último — use-conceder-acesso.ts). Quem já tem acesso
// (`comAcesso`: a identidade já está ligada ao cadastro) aparece marcado e sem o botão — conceder de novo só daria
// conflito (409) no passo 2. Aqui também fica o
// interruptor da conferência automática (o agente institucional, B.8), que só o admin_ente liga, e a exportação
// completa dos dados da Casa (ADR-0018 fatia 2, 9.6). "Quem tem acesso" lista o que foi concedido e REVOGA, com motivo
// (ADR-0005, adendo): quem sai da Casa não fica com a porta aberta. E os SETORES da Casa (ADR-0020): os endereços internos dos
// comunicados, com quem é de cada um.

import { useState } from "react";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { useVereadores } from "@/lib/use-vereadores";
import { avatar, estadoChip } from "@/lib/cadastro-vereadores-vista";
import { comToken } from "@/lib/nav";
import { GuardAdminEnte } from "../guard-admin-ente";
import { TopoInterno } from "../topo";
import { ConcederAcessoForm } from "../cadastros/vereadores/conceder-acesso-form";
import { ConcederAuditorForm } from "./conceder-auditor-form";
import { ConcederJuridicoForm } from "./conceder-juridico-form";
import { AcessosConcedidos } from "./acessos-concedidos";
import { ParecerNoPortal } from "./parecer-no-portal";
import { RegrasDaPauta } from "./regras-da-pauta";
import { PrazosDasContas } from "./prazos-das-contas";
import { ExportarDados } from "./exportar-dados";
import { Setores } from "./setores";
import { PainelAgente } from "../conferencias/fila-conferencias";
import { useAgentesInstitucionais } from "@/lib/use-conferencias";
import "../cadastros/vereadores/cadastro-vereadores.css";
import "../conferencias/conferencias.css";
import "./administracao.css";

function nomeExibicao(p: { nome: string; nomeParlamentar?: string | null }): string {
  return p.nomeParlamentar ?? p.nome;
}

function Conteudo() {
  const { token } = useAuth();
  const [versao, setVersao] = useState(0);
  const { dados: vereadores, estado } = useVereadores(token, versao);
  const [aberto, setAberto] = useState<string | null>(null);
  const [concedido, setConcedido] = useState<string | null>(null);
  // B.8 (ADR-0013): o agente institucional da Casa é LIGADO pelo admin_ente. O painel vivia só em /conferencias, que
  // é da secretaria — o administrador que só tem esse papel não o alcançava.
  const agentes = useAgentesInstitucionais(token);
  // ADR-0017: o controle interno (papel `auditor`) — quem lê a trilha de auditoria da Casa inteira.
  const [auditorAberto, setAuditorAberto] = useState(false);
  const [auditorConcedido, setAuditorConcedido] = useState<string | null>(null);

  // ADR-0019: o jurídico da Casa (papel `juridico`) — quem redige e assina o parecer jurídico.
  const [juridicoAberto, setJuridicoAberto] = useState(false);
  const [juridicoConcedido, setJuridicoConcedido] = useState<string | null>(null);

  function abrir(id: string) {
    setConcedido(null);
    setAberto(id);
  }

  return (
    <>
      <TopoInterno area="Administração" />
      <main className="envelope adm">
        <header className="adm-cabeca">
          <p className="adm-sobre">Administração da Casa</p>
          <h1>Acessos dos vereadores</h1>
          <p className="adm-texto">
            Conceda aos vereadores o acesso ao sistema. Cada acesso é criado com o papel de vereador e o convite
            vai para o e-mail institucional informado. O cadastro dos vereadores é mantido pela secretaria.
          </p>
        </header>

        {concedido && (
          <p role="status" className="adm-aviso">
            Acesso concedido a {concedido}. O convite foi enviado para o e-mail informado.
          </p>
        )}

        {estado === "carregando" && <p className="adm-texto">Carregando os vereadores…</p>}
        {estado === "erro" && (
          <p role="alert" className="adm-texto">Não foi possível carregar os vereadores. Tente de novo em instantes.</p>
        )}
        {estado === "pronto" && vereadores.length === 0 && (
          <p className="adm-texto">Nenhum vereador cadastrado ainda. O cadastro é feito pela secretaria.</p>
        )}

        {estado === "pronto" && vereadores.length > 0 && (
          <ul className="adm-lista" aria-label="Vereadores">
            {vereadores.map((v) => {
              const nome = nomeExibicao(v);
              const av = avatar(nome, v.id);
              const chip = estadoChip(v.estadoMandato);
              return (
                <li key={v.id} className="adm-item">
                  <div className="adm-linha">
                    <span className="adm-avatar" style={{ background: av.cor }} aria-hidden="true">{av.iniciais}</span>
                    <span className="adm-quem">
                      <b>{nome}</b>
                      <span>{[v.partido, chip.rotulo].filter(Boolean).join(" · ")}</span>
                    </span>
                    {v.comAcesso && <span className="adm-chip">Acesso concedido</span>}
                    {!v.comAcesso && aberto !== v.id && (
                      <button
                        type="button"
                        className="btn btn-contorno btn-mini"
                        aria-label={`Conceder acesso a ${nome}`}
                        onClick={() => abrir(v.id)}
                      >
                        Conceder acesso
                      </button>
                    )}
                  </div>
                  {aberto === v.id && (
                    <div className="painel-cad">
                      <ConcederAcessoForm
                        token={token}
                        vereadorId={v.id}
                        nome={nome}
                        onSucesso={() => {
                          setAberto(null);
                          setConcedido(nome);
                          setVersao((n) => n + 1);
                        }}
                        onCancelar={() => setAberto(null)}
                      />
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}

        <AcessosConcedidos token={token} />

        <PainelAgente token={token} agentes={agentes} />

        <section className="adm-auditoria" aria-labelledby="adm-auditoria-titulo">
          <h2 id="adm-auditoria-titulo">Controle interno</h2>
          <p className="adm-texto">
            A trilha de auditoria registra cada ato no sistema — quem fez, o quê, quando e de onde — numa cadeia selada que
            ninguém edita. Quem a lê por inteiro é o controle interno da Casa (procuradoria, controladoria): dê a esse
            servidor o acesso à trilha. Ele só lê; não opera o sistema.
          </p>
          {auditorConcedido && (
            <p role="status" className="adm-aviso">
              Acesso à trilha concedido a {auditorConcedido}. O convite foi enviado para o e-mail informado.
            </p>
          )}
          {auditorAberto ? (
            <div className="painel-cad">
              <ConcederAuditorForm
                token={token}
                onSucesso={(nome) => {
                  setAuditorAberto(false);
                  setAuditorConcedido(nome);
                }}
                onCancelar={() => setAuditorAberto(false)}
              />
            </div>
          ) : (
            <button type="button" className="btn btn-contorno btn-mini" onClick={() => {
              setAuditorConcedido(null);
              setAuditorAberto(true);
            }}>
              Dar acesso ao controle interno
            </button>
          )}
        </section>

        <section className="adm-auditoria" aria-labelledby="adm-juridico-titulo">
          <h2 id="adm-juridico-titulo">Jurídico da Casa</h2>
          <p className="adm-texto">
            O jurídico redige e assina o parecer sobre as matérias e as consultas que a secretaria pede. O acesso registra a
            qualificação (efetivo, comissionado ou contratado) e a OAB, porque o parecer diz a que título foi assinado. O
            parecer é opinativo: não decide a matéria.
          </p>
          {juridicoConcedido && (
            <p role="status" className="adm-aviso">
              Acesso ao jurídico concedido a {juridicoConcedido}. O convite foi enviado para o e-mail informado.
            </p>
          )}
          {juridicoAberto ? (
            <div className="painel-cad">
              <ConcederJuridicoForm
                token={token}
                onSucesso={(nome) => {
                  setJuridicoAberto(false);
                  setJuridicoConcedido(nome);
                }}
                onCancelar={() => setJuridicoAberto(false)}
              />
            </div>
          ) : (
            <button type="button" className="btn btn-contorno btn-mini" onClick={() => {
              setJuridicoConcedido(null);
              setJuridicoAberto(true);
            }}>
              Dar acesso ao jurídico
            </button>
          )}
        </section>

        <Setores token={token} />
        <ParecerNoPortal token={token} />
        <RegrasDaPauta token={token} />
        <PrazosDasContas token={token} />
        <ExportarDados token={token} />

        <section className="adm-outras" aria-labelledby="adm-outras-titulo">
          <h2 id="adm-outras-titulo">Outras áreas da administração</h2>
          <ul>
            <li>
              <Link href={comToken("/paineis/ia", token)} prefetch={false}>IA da Casa</Link>
              <span> — o consumo da IA no mês e o que a Casa fez com o que ela entregou.</span>
            </li>
            <li>
              <Link href={comToken("/auditoria", token)} prefetch={false}>Trilha de auditoria</Link>
              <span> — quem concedeu e revogou acessos, e quem entrou no sistema.</span>
            </li>
          </ul>
        </section>
      </main>
    </>
  );
}

export default function PaginaAdministracao() {
  return (
    <GuardAdminEnte>
      <Conteudo />
    </GuardAdminEnte>
  );
}
