"use client";

// Bloco "Quem tem acesso" da área do administrador (ADR-0005, adendo "Revogar acesso"): os acessos que o `admin_ente`
// concedeu — vereador, controle interno e jurídico — com o botão de REVOGAR ao lado de cada um e, nos já revogados, a
// data, quem revogou e o motivo. Até aqui a tela só concedia: quem saía da Casa ficava com a porta aberta.
//
// Revogar pede MOTIVO (fica registrado) e confirmação explícita. O efeito vale na próxima ação da pessoa — a sessão dela
// não guarda papel — e quem fica sem nenhum acesso na Casa deixa de entrar. Nada é apagado, e dar o acesso de novo
// continua possível: vereador e controle interno aqui mesmo (basta o e-mail do convite); o jurídico pelo formulário
// "Dar acesso ao jurídico", porque a qualificação e a OAB precisam ser confirmadas.

import { useState, type FormEvent } from "react";
import { formatarData } from "@/lib/formatar-data";
import {
  MOTIVO_MAXIMO,
  MOTIVO_MINIMO,
  concederDeNovo,
  revogarAcesso,
  rotuloDoAcesso,
  useAcessos,
  type AcessoOut,
} from "@/lib/use-acessos";

type Aviso = { tom: "ok" | "erro"; texto: string } | null;

const chave = (a: AcessoOut) => `${a.identidadeId}:${a.papel}`;

export function AcessosConcedidos({ token }: { token: string | null }) {
  const [versao, setVersao] = useState(0);
  const { dados, estado } = useAcessos(token, versao);
  const [aviso, setAviso] = useState<Aviso>(null);

  const ordenados = [...dados].sort(
    (a, b) => Number(a.revogadoEm !== null) - Number(b.revogadoEm !== null) || a.nome.localeCompare(b.nome, "pt-BR"),
  );

  return (
    <section className="adm-auditoria adm-acessos" aria-labelledby="adm-acessos-titulo">
      <h2 id="adm-acessos-titulo">Quem tem acesso</h2>
      <p className="adm-texto">
        Estes são os acessos que você concedeu: vereadores, controle interno e jurídico. Ao revogar, a pessoa perde o
        acesso na próxima ação que fizer no sistema. O motivo fica registrado, nada é apagado, e você pode dar o acesso de
        novo depois.
      </p>

      {aviso && (
        <p role={aviso.tom === "erro" ? "alert" : "status"} className={aviso.tom === "erro" ? "adm-erro" : "adm-aviso"}>
          {aviso.texto}
        </p>
      )}

      {estado === "carregando" && <p className="adm-texto">Carregando os acessos…</p>}
      {estado === "erro" && (
        <p role="alert" className="adm-texto">
          Não foi possível carregar os acessos. Tente de novo em instantes.
        </p>
      )}
      {estado === "pronto" && ordenados.length === 0 && <p className="adm-texto">Nenhum acesso concedido ainda.</p>}
      {ordenados.length > 0 && (
        <ul className="adm-lista" aria-label="Acessos concedidos">
          {ordenados.map((a) => (
            <ItemDoAcesso
              key={chave(a)}
              acesso={a}
              token={token}
              aoMudar={(tom, texto) => {
                setAviso({ tom, texto });
                if (tom === "ok") setVersao((n) => n + 1);
              }}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

function ItemDoAcesso({
  acesso,
  token,
  aoMudar,
}: {
  acesso: AcessoOut;
  token: string | null;
  aoMudar: (tom: "ok" | "erro", texto: string) => void;
}) {
  const revogado = acesso.revogadoEm !== null;
  const rotulo = rotuloDoAcesso(acesso.papel);
  const [modo, setModo] = useState<"ver" | "revogar" | "dar-de-novo">("ver");
  const [motivo, setMotivo] = useState("");
  const [email, setEmail] = useState("");
  const [tocado, setTocado] = useState(false);
  const [enviando, setEnviando] = useState(false);
  const idMotivo = `rev-motivo-${chave(acesso)}`;
  const idEmail = `rev-email-${chave(acesso)}`;
  const motivoAparado = motivo.trim();
  const erroMotivo = motivoAparado.length < MOTIVO_MINIMO ? "Escreva o motivo da revogação." : undefined;
  const emailOk = /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim());

  async function confirmarRevogacao(e: FormEvent) {
    e.preventDefault();
    setTocado(true);
    if (erroMotivo) return;
    setEnviando(true);
    const r = await revogarAcesso(token, acesso.identidadeId, acesso.papel, motivoAparado);
    setEnviando(false);
    if (!r.ok) return aoMudar("erro", r.mensagem);
    setModo("ver");
    setMotivo("");
    setTocado(false);
    aoMudar(
      "ok",
      `Acesso de ${acesso.nome} (${rotulo}) revogado. ` +
        (r.vinculoEncerrado
          ? "A pessoa já não consegue entrar no sistema."
          : "A pessoa mantém os outros acessos que tinha."),
    );
  }

  async function confirmarDeNovo(e: FormEvent) {
    e.preventDefault();
    setTocado(true);
    if (!emailOk) return;
    setEnviando(true);
    const r = await concederDeNovo(token, acesso.identidadeId, acesso.papel, email);
    setEnviando(false);
    if (!r.ok) return aoMudar("erro", r.mensagem);
    setModo("ver");
    setEmail("");
    setTocado(false);
    aoMudar("ok", `Acesso de ${acesso.nome} (${rotulo}) concedido de novo. O convite foi enviado para o e-mail de acesso desta pessoa.`);
  }

  return (
    <li className="adm-item adm-acesso">
      <div className="adm-linha">
        <span className="adm-quem">
          <b>{acesso.nome}</b>
          <span>
            {rotulo} ·{" "}
            {revogado
              ? `Revogado em ${formatarData(acesso.revogadoEm!)}${acesso.revogadoPorNome ? ` por ${acesso.revogadoPorNome}` : ""}`
              : `Acesso ativo desde ${formatarData(acesso.concedidoEm)}`}
          </span>
          {revogado && acesso.motivo && <span>Motivo: {acesso.motivo}</span>}
        </span>
        {modo === "ver" && !revogado && (
          <button
            type="button"
            className="btn btn-contorno btn-mini"
            aria-label={`Revogar acesso de ${acesso.nome} (${rotulo})`}
            onClick={() => setModo("revogar")}
          >
            Revogar acesso
          </button>
        )}
        {modo === "ver" && revogado && acesso.papel !== "juridico" && (
          <button
            type="button"
            className="btn btn-contorno btn-mini"
            aria-label={`Dar o acesso de novo a ${acesso.nome} (${rotulo})`}
            onClick={() => setModo("dar-de-novo")}
          >
            Dar o acesso de novo
          </button>
        )}
      </div>

      {modo === "ver" && revogado && acesso.papel === "juridico" && (
        <p className="adm-texto">
          Para dar o acesso de novo, use “Dar acesso ao jurídico” mais abaixo, com o mesmo CPF: a qualificação e a OAB
          precisam ser confirmadas.
        </p>
      )}

      {modo === "revogar" && (
        <form className="form-cad adm-revogar" onSubmit={(e) => void confirmarRevogacao(e)} aria-label={`Revogar acesso de ${acesso.nome}`}>
          <p className="adm-texto">
            {acesso.nome} perde o acesso de {rotulo.toLowerCase()} na próxima ação que fizer no sistema. Isso fica
            registrado na trilha de auditoria.
          </p>
          <div className="campo">
            <label htmlFor={idMotivo}>Motivo da revogação*</label>
            <textarea
              id={idMotivo}
              rows={3}
              maxLength={MOTIVO_MAXIMO}
              value={motivo}
              onChange={(e) => setMotivo(e.target.value)}
              aria-invalid={tocado && !!erroMotivo}
              aria-describedby={tocado && erroMotivo ? `${idMotivo}-erro` : undefined}
            />
            {tocado && erroMotivo && (
              <p id={`${idMotivo}-erro`} role="alert" className="campo-erro">
                {erroMotivo}
              </p>
            )}
          </div>
          <div className="form-acoes">
            <button type="submit" className="btn btn-primaria btn-mini" disabled={enviando}>
              {enviando ? "Revogando…" : "Confirmar revogação"}
            </button>
            <button type="button" className="btn btn-fantasma btn-mini" onClick={() => { setModo("ver"); setTocado(false); }}>
              Cancelar
            </button>
          </div>
        </form>
      )}

      {modo === "dar-de-novo" && (
        <form className="form-cad adm-revogar" onSubmit={(e) => void confirmarDeNovo(e)} aria-label={`Dar o acesso de novo a ${acesso.nome}`}>
          <div className="campo">
            <label htmlFor={idEmail}>E-mail institucional*</label>
            <input
              id={idEmail}
              type="email"
              placeholder="nome@camara.gov.br"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              aria-invalid={tocado && !emailOk}
              aria-describedby={tocado && !emailOk ? `${idEmail}-erro` : undefined}
            />
            {tocado && !emailOk && (
              <p id={`${idEmail}-erro`} role="alert" className="campo-erro">
                Informe um e-mail válido.
              </p>
            )}
            {/* O backend NÃO troca o e-mail de quem já tem conta: o convite sai para o endereço já cadastrado. A tela
                diz isso para o administrador não achar que redirecionou o convite. */}
            <p className="campo-dica">
              Quem já recebeu convite antes recebe o novo no mesmo e-mail de então. O endereço acima só vale para quem
              ainda não tem conta de acesso. Para trocar o e-mail de alguém, fale com o operador da plataforma.
            </p>
          </div>
          <div className="form-acoes">
            <button type="submit" className="btn btn-primaria btn-mini" disabled={enviando}>
              {enviando ? "Concedendo…" : "Dar o acesso de novo"}
            </button>
            <button type="button" className="btn btn-fantasma btn-mini" onClick={() => { setModo("ver"); setTocado(false); }}>
              Cancelar
            </button>
          </div>
        </form>
      )}
    </li>
  );
}
