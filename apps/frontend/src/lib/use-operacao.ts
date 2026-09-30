"use client";

// ADR-0016 — o console do OPERADOR (supratenant). Os dados vêm de /api/operacao/* (rewrite para o backend
// /operacao/*), autenticados pelo cookie `sessao_operacao` — nunca pelo `sessao` de uma Casa. No modo dev o
// `?token=` (JSON com `operador-id`, idp-operacao-dev) segue pelo mesmo apiFetch.

import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { ObservabilidadeIA } from "./observabilidade-ia-vista";

export type EstadoCasa = "provisionar" | "ativo" | "suspenso" | "encerrado";

export type Casa = {
  enteId: string;
  nome: string;
  nomeCurto: string | null;
  uf: string;
  municipio: { ibge: string; nome: string | null } | null;
  estado: EstadoCasa;
  criadaEm: string | null;
  conviteEnviadoEm: string | null;
  ativadaEm: string | null;
  /** ADR-0018: a restrição vigente (só numa Casa suspensa). */
  restricao?: { motivo: string; desde: string } | null;
  /** ADR-0018: a suspensão aprovada que espera a sessão plenária em curso encerrar. */
  suspensaoAgendada?: boolean;
};

/** ADR-0018: o pedido de suspensão/encerramento que espera o 2º operador. */
export type Pedido = {
  id: string;
  enteId: string;
  casaNome: string | null;
  acao: "suspender" | "encerrar";
  motivo: string;
  justificativa: string;
  estado: "aguardando" | "aprovado" | "recusado" | "expirado" | "retirado";
  pedidoPorId: string;
  pedidoPor: string | null;
  pedidoEm: string;
  confirmarAte: string | null;
  efetivadoEm: string | null;
};

export type ListaDeCasas = {
  casas: Casa[];
  resumo: { total: number; ativas: number; aguardandoAdmin: number; suspensas?: number };
  pendentes?: Pedido[];
};

export type Atuacao = {
  id: string;
  em: string;
  acao: string;
  operador: string | null;
  detalhe: Record<string, unknown>;
  selo: string;
};

export type FichaDaCasa = {
  casa: Casa;
  primeiroAdmin: { nome: string | null; email: string | null } | null;
  pedidoAberto?: Pedido | null;
  atuacao: Atuacao[];
};

export type Operador = { id: string; nome: string; email: string; papeis: string[] };

type Estado = "carregando" | "pronto" | "erro" | "sem-sessao" | "nao-encontrada";

function useLeitura<T>(caminho: string | null, token: string | null) {
  const [dados, setDados] = useState<T | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");
  const [versao, setVersao] = useState(0);

  useEffect(() => {
    if (!caminho) return;
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch(caminho, { token: token ?? undefined, cache: "no-store" });
        if (!vivo) return;
        if (r.status === 401) return setEstado("sem-sessao");
        if (r.status === 404) return setEstado("nao-encontrada");
        if (!r.ok) throw new Error(`${caminho} ${r.status}`);
        const d = camelizarChaves(await r.json()) as T;
        if (!vivo) return;
        setDados(d);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [caminho, token, versao]);

  const recarregar = useCallback(() => setVersao((v) => v + 1), []);
  return { dados, estado, recarregar };
}

export function useOperador(token: string | null) {
  const r = useLeitura<{ operador: Operador }>("/api/operacao/eu", token);
  return { ...r, operador: r.dados?.operador ?? null };
}

export function useCasas(token: string | null) {
  return useLeitura<ListaDeCasas>("/api/operacao/casas", token);
}

export function useObservabilidadeIA(horas: number, token: string | null) {
  return useLeitura<ObservabilidadeIA>(`/api/operacao/ia?horas=${horas}`, token);
}

export function useFichaDaCasa(ente: string, token: string | null) {
  return useLeitura<FichaDaCasa>(`/api/operacao/casas/${encodeURIComponent(ente)}`, token);
}

export type EntradaProvisionar = {
  nomeOficial: string;
  nomeCurto: string;
  uf: string;
  municipioIbge: string;
  municipioNome: string;
  admin: { nome: string; cpf: string; email: string };
};

export type ResultadoEnvio<T> = { ok: true; dados: T } | { ok: false; mensagem: string };

const MENSAGENS: Record<number, string> = {
  400: "Algum dado não passou na conferência do servidor. Revise os campos e tente de novo.",
  401: "Sua sessão do console expirou. Entre de novo com a sua chave.",
  403: "Seu perfil de operador não permite esta ação.",
  404: "Esta Casa não está no registro.",
};

async function enviar<T>(caminho: string, token: string | null, corpo?: unknown): Promise<ResultadoEnvio<T>> {
  try {
    const r = await apiFetch(caminho, {
      token: token ?? undefined,
      method: "POST",
      headers: corpo === undefined ? undefined : { "content-type": "application/json" },
      body: corpo === undefined ? undefined : JSON.stringify(corpo),
      cache: "no-store",
    });
    if (r.ok) return { ok: true, dados: camelizarChaves(await r.json()) as T };
    if (r.status === 409) {
      const b = (await r.json().catch(() => ({}))) as { erro?: string };
      return { ok: false, mensagem: b.erro ? primeiraMaiuscula(b.erro) + "." : "A Casa não está mais nesse estado." };
    }
    return { ok: false, mensagem: MENSAGENS[r.status] ?? "O servidor não conseguiu concluir agora. Tente de novo em instantes." };
  } catch {
    return { ok: false, mensagem: "Sem conexão com o servidor. Verifique a rede e tente de novo." };
  }
}

function primeiraMaiuscula(s: string) {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** O corpo do POST /operacao/casas no formato do backend (chaves kebab). Só dígitos do CPF seguem. */
export function corpoProvisionar(e: EntradaProvisionar) {
  return {
    "nome-oficial": e.nomeOficial.trim(),
    "nome-curto": e.nomeCurto.trim() || null,
    uf: e.uf,
    "municipio-ibge": e.municipioIbge.replace(/\D/g, ""),
    "municipio-nome": e.municipioNome.trim(),
    admin: { nome: e.admin.nome.trim(), cpf: e.admin.cpf.replace(/\D/g, ""), email: e.admin.email.trim() },
  };
}

export function provisionarCasa(e: EntradaProvisionar, token: string | null) {
  return enviar<{ casa: Casa; convite: "enviado" | "falhou" }>("/api/operacao/casas", token, corpoProvisionar(e));
}

export function reenviarConvite(ente: string, token: string | null) {
  return enviar<Casa>(`/api/operacao/casas/${encodeURIComponent(ente)}/convite`, token);
}

export function reaplicarLogin(ente: string, token: string | null) {
  return enviar<Casa>(`/api/operacao/casas/${encodeURIComponent(ente)}/realm`, token);
}

// ---- ADR-0018: suspender, reativar, iniciar o encerramento ----

export type Transicao = { casa: Casa; pedido: Pedido | null; efeito: "imediato" | "agendado" | "ja-efetivado" | null };

export const MOTIVOS_SUSPENSAO = [
  { valor: "inadimplencia", rotulo: "Inadimplência" },
  { valor: "pedido_da_casa", rotulo: "Pedido da própria Câmara" },
  { valor: "ordem_judicial", rotulo: "Ordem judicial" },
  { valor: "incidente_de_seguranca", rotulo: "Incidente de segurança" },
] as const;

export const ORIGENS_ENCERRAMENTO = [
  { valor: "fim_de_contrato", rotulo: "Fim do contrato" },
  { valor: "pedido_da_casa", rotulo: "Pedido da Câmara (ofício)" },
] as const;

const ROTULOS_MOTIVO: Record<string, string> = {
  inadimplencia: "Inadimplência",
  pedido_da_casa: "Pedido da própria Câmara",
  ordem_judicial: "Ordem judicial",
  incidente_de_seguranca: "Incidente de segurança",
  encerramento_em_curso: "Encerramento em curso",
  fim_de_contrato: "Fim do contrato",
};

export function rotuloMotivo(motivo: string): string {
  return ROTULOS_MOTIVO[motivo] ?? motivo;
}

/** A justificativa tem de dizer algo: o servidor recusa menos de 10 caracteres (e o que for além de 2000). */
export function conferirJustificativa(texto: string): string | null {
  const t = texto.trim();
  if (t.length < 10) return "Escreva a justificativa — ela fica na atuação selada e a Câmara a lê.";
  if (t.length > 2000) return "A justificativa passa de 2.000 caracteres.";
  return null;
}

const base = (ente: string) => `/api/operacao/casas/${encodeURIComponent(ente)}`;

export function pedirSuspensao(ente: string, motivo: string, justificativa: string, token: string | null) {
  return enviar<Transicao>(`${base(ente)}/suspensao`, token, { motivo, justificativa: justificativa.trim() });
}

export function iniciarEncerramento(ente: string, origem: string, justificativa: string, token: string | null) {
  return enviar<Transicao>(`${base(ente)}/encerramento`, token, { origem, justificativa: justificativa.trim() });
}

export function reativarCasa(ente: string, justificativa: string, token: string | null) {
  return enviar<Transicao>(`${base(ente)}/reativacao`, token, { justificativa: justificativa.trim() });
}

export function aprovarPedido(pedido: string, token: string | null) {
  return enviar<Transicao>(`/api/operacao/pedidos/${encodeURIComponent(pedido)}/aprovacao`, token, {});
}

/** Outro operador recusa; quem pediu retira (o servidor decide qual pela sessão). */
export function recusarPedido(pedido: string, justificativa: string, token: string | null) {
  const j = justificativa.trim();
  return enviar<Transicao>(`/api/operacao/pedidos/${encodeURIComponent(pedido)}/recusa`, token, j ? { justificativa: j } : {});
}

// ---- conferência no navegador (espelha o backend; o servidor confere de novo) ----

export function cpfValido(entrada: string): boolean {
  const d = entrada.replace(/\D/g, "");
  if (d.length !== 11 || /^(\d)\1{10}$/.test(d)) return false;
  const dv = (n: number) => {
    let s = 0;
    for (let i = 0; i < n; i++) s += Number(d[i]) * (n + 1 - i);
    const r = s % 11;
    return r < 2 ? 0 : 11 - r;
  };
  return dv(9) === Number(d[9]) && dv(10) === Number(d[10]);
}

export const UFS = [
  "AC", "AL", "AM", "AP", "BA", "CE", "DF", "ES", "GO", "MA", "MG", "MS", "MT", "PA", "PB",
  "PE", "PI", "PR", "RJ", "RN", "RO", "RR", "RS", "SC", "SE", "SP", "TO",
] as const;

export type ErrosProvisionar = Partial<Record<"nomeOficial" | "uf" | "municipioNome" | "municipioIbge" | "adminNome" | "adminCpf" | "adminEmail", string>>;

export function conferirProvisionar(e: EntradaProvisionar): ErrosProvisionar {
  const erros: ErrosProvisionar = {};
  if (e.nomeOficial.trim().length < 5) erros.nomeOficial = "Escreva o nome oficial da Casa, como no CNPJ.";
  if (!UFS.includes(e.uf as (typeof UFS)[number])) erros.uf = "Escolha a UF.";
  if (e.municipioNome.trim().length < 2) erros.municipioNome = "Escreva o nome do município.";
  if (!/^\d{7}$/.test(e.municipioIbge.replace(/\D/g, ""))) erros.municipioIbge = "O código IBGE do município tem 7 dígitos.";
  if (e.admin.nome.trim().length < 3) erros.adminNome = "Escreva o nome completo de quem vai administrar a Casa.";
  if (!cpfValido(e.admin.cpf)) erros.adminCpf = "Este CPF não confere. Revise os 11 dígitos.";
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e.admin.email.trim())) erros.adminEmail = "O convite vai para este e-mail. Revise o endereço.";
  return erros;
}

// ---- rótulos ----

export function rotuloEstadoCasa(estado: EstadoCasa): string {
  return (
    { provisionar: "Aguardando 1º admin", ativo: "Ativa", suspenso: "Suspensa", encerrado: "Encerrada" } as const
  )[estado];
}

const ACOES: Record<string, string> = {
  "casa-provisionada": "Câmara provisionada",
  "convite-enviado": "Convite enviado ao 1º administrador",
  "convite-reenviado": "Convite reenviado ao 1º administrador",
  "casa-ativada": "A Casa assumiu: o 1º administrador entrou",
  "realm-reprovisionado": "Configuração de login reaplicada",
  "suspensao-pedida": "Suspensão pedida",
  "suspensao-aprovada": "Suspensão aprovada pelo 2º operador",
  "suspensao-recusada": "Suspensão recusada pelo 2º operador",
  "suspensao-agendada": "Suspensão agendada para o fim da sessão em curso",
  "suspensao-agendada-cancelada": "Suspensão agendada cancelada",
  "encerramento-pedido": "Encerramento pedido",
  "encerramento-aprovado": "Encerramento aprovado pelo 2º operador",
  "encerramento-recusado": "Encerramento recusado pelo 2º operador",
  "pedido-retirado": "Pedido retirado por quem pediu",
  "casa-suspensa": "Câmara com acesso restrito",
  "casa-reativada": "Câmara reativada",
};

export function rotuloAcao(acao: string): string {
  return ACOES[acao] ?? acao;
}

/** O selo no formato curto do desenho: primeiros e últimos 4 caracteres. */
export function seloCurto(selo: string): string {
  return selo.length > 8 ? `${selo.slice(0, 4)}·${selo.slice(-4)}` : selo;
}
