"use client";

// ADR-0016 — Provisionar câmara. A Operação registra a Casa, entrega o perfil público e convida o 1º
// administrador; a Casa passa às mãos dela quando ele entra. O CPF só serve para ancorar a identidade dele — não
// fica no registro nem volta para esta tela. Conferência no navegador espelha a do servidor, que confere de novo.

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useAuth } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import {
  conferirProvisionar,
  type EntradaProvisionar,
  type ErrosProvisionar,
  provisionarCasa,
  UFS,
} from "@/lib/use-operacao";

const VAZIO: EntradaProvisionar = {
  nomeOficial: "",
  nomeCurto: "",
  uf: "",
  municipioIbge: "",
  municipioNome: "",
  admin: { nome: "", cpf: "", email: "" },
};

export default function ProvisionarCamara() {
  const { token } = useAuth();
  const router = useRouter();
  const [e, setE] = useState<EntradaProvisionar>(VAZIO);
  const [erros, setErros] = useState<ErrosProvisionar>({});
  const [enviando, setEnviando] = useState(false);
  const [falha, setFalha] = useState<string | null>(null);

  const campo = (k: keyof Omit<EntradaProvisionar, "admin">) => (ev: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setE({ ...e, [k]: ev.target.value });
  const admin = (k: keyof EntradaProvisionar["admin"]) => (ev: React.ChangeEvent<HTMLInputElement>) =>
    setE({ ...e, admin: { ...e.admin, [k]: ev.target.value } });

  async function enviar(ev: React.FormEvent) {
    ev.preventDefault();
    if (enviando) return;
    const achados = conferirProvisionar(e);
    setErros(achados);
    setFalha(null);
    if (Object.keys(achados).length) {
      document.getElementById(`campo-${Object.keys(achados)[0]}`)?.focus();
      return;
    }
    setEnviando(true);
    const r = await provisionarCasa(e, token);
    setEnviando(false);
    if (!r.ok) return setFalha(r.mensagem);
    const destino = `/operacao/casas/${r.dados.casa.enteId}?provisionada=${r.dados.convite}`;
    router.push(comToken(destino, token));
  }

  const erroDe = (k: keyof ErrosProvisionar) =>
    erros[k] ? <span className="erro" id={`erro-${k}`}>{erros[k]}</span> : null;
  const aria = (k: keyof ErrosProvisionar) =>
    erros[k] ? { "aria-invalid": true as const, "aria-describedby": `erro-${k}` } : {};

  return (
    <>
      <nav className="op-migalha" aria-label="Trilha">
        <Link href={comToken("/operacao", token)}>Câmaras</Link> <span aria-hidden="true">›</span> <span>Provisionar câmara</span>
      </nav>
      <div className="op-pg-cab">
        <div>
          <h1>Provisionar câmara</h1>
          <p className="sub">
            A Operação cria o tenant e convida o 1º administrador. Quando ele entra, a câmara passa a ser dela — a
            partir daí quem dá acesso às pessoas é o administrador da Casa.
          </p>
        </div>
      </div>

      <form className="op-form" onSubmit={enviar} noValidate>
        <fieldset className="op-bloco">
          <legend>A câmara</legend>
          <p className="aj">O nome e o município aparecem no portal público da Casa.</p>
          <div className="op-grade">
            <div className="op-campo inteira">
              <label htmlFor="campo-nomeOficial">Nome oficial</label>
              <input id="campo-nomeOficial" value={e.nomeOficial} onChange={campo("nomeOficial")} autoComplete="off"
                placeholder="Câmara Municipal de Baturité" {...aria("nomeOficial")} />
              {erroDe("nomeOficial")}
            </div>
            <div className="op-campo">
              <label htmlFor="campo-nomeCurto">Nome curto <span className="dica">(opcional)</span></label>
              <input id="campo-nomeCurto" value={e.nomeCurto} onChange={campo("nomeCurto")} autoComplete="off"
                placeholder="Câmara de Baturité" />
            </div>
            <div className="op-campo">
              <label htmlFor="campo-uf">UF</label>
              <select id="campo-uf" value={e.uf} onChange={campo("uf")} {...aria("uf")}>
                <option value="">Escolha</option>
                {UFS.map((u) => <option key={u} value={u}>{u}</option>)}
              </select>
              {erroDe("uf")}
            </div>
            <div className="op-campo">
              <label htmlFor="campo-municipioNome">Município</label>
              <input id="campo-municipioNome" value={e.municipioNome} onChange={campo("municipioNome")} autoComplete="off"
                placeholder="Baturité" {...aria("municipioNome")} />
              {erroDe("municipioNome")}
            </div>
            <div className="op-campo">
              <label htmlFor="campo-municipioIbge">Código IBGE do município</label>
              <input id="campo-municipioIbge" value={e.municipioIbge} onChange={campo("municipioIbge")} inputMode="numeric"
                autoComplete="off" placeholder="2302008" maxLength={7} {...aria("municipioIbge")} />
              {erroDe("municipioIbge")}
            </div>
          </div>
        </fieldset>

        <fieldset className="op-bloco">
          <legend>O 1º administrador da câmara</legend>
          <p className="aj">
            Recebe o convite por e-mail e cadastra a própria passkey. É quem depois dá acesso às outras pessoas da Casa.
          </p>
          <div className="op-grade">
            <div className="op-campo inteira">
              <label htmlFor="campo-adminNome">Nome completo</label>
              <input id="campo-adminNome" value={e.admin.nome} onChange={admin("nome")} autoComplete="off" {...aria("adminNome")} />
              {erroDe("adminNome")}
            </div>
            <div className="op-campo">
              <label htmlFor="campo-adminCpf">CPF</label>
              <input id="campo-adminCpf" value={e.admin.cpf} onChange={admin("cpf")} inputMode="numeric" autoComplete="off"
                placeholder="000.000.000-00" maxLength={14} {...aria("adminCpf")} />
              {erroDe("adminCpf") ?? <span className="dica">Ancora a identidade da pessoa. Não aparece no console.</span>}
            </div>
            <div className="op-campo">
              <label htmlFor="campo-adminEmail">E-mail institucional</label>
              <input id="campo-adminEmail" type="email" value={e.admin.email} onChange={admin("email")} autoComplete="off"
                placeholder="nome@camara.gov.br" {...aria("adminEmail")} />
              {erroDe("adminEmail")}
            </div>
          </div>
        </fieldset>

        {falha && <p className="op-aviso erro" role="alert">{falha}</p>}
        <div className="op-acoes">
          <button className="btn btn-primaria" type="submit" disabled={enviando} aria-busy={enviando}>
            {enviando ? "Provisionando…" : "Provisionar e convidar"}
          </button>
          <Link className="btn btn-contorno" href={comToken("/operacao", token)}>Cancelar</Link>
        </div>
      </form>
    </>
  );
}
