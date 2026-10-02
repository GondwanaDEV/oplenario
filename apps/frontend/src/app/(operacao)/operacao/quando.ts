// Datas do console em português, relativas a agora ("há 2 dias") — a linguagem do desenho (console-operador.html).

import type { Casa } from "@/lib/use-operacao";

export function haQuanto(iso: string | null, agora: Date = new Date()): string {
  if (!iso) return "";
  const ms = agora.getTime() - new Date(iso).getTime();
  const min = Math.round(ms / 60000);
  if (min < 1) return "agora";
  if (min < 60) return `há ${min} min`;
  const h = Math.round(min / 60);
  if (h < 24) return `há ${h} h`;
  const d = Math.round(h / 24);
  return d === 1 ? "há 1 dia" : `há ${d} dias`;
}

export function dataHora(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString("pt-BR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" });
}

/** Só a data, com o ano (DD/MM/AAAA) — o prazo da guarda e a data do encerramento atravessam a virada do ano. */
export function dataLonga(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleDateString("pt-BR", { day: "2-digit", month: "2-digit", year: "numeric" });
}

export function atividade(c: Casa, agora: Date = new Date()): string {
  if (c.estado === "ativo" && c.suspensaoAgendada) return "suspensão agendada para o fim da sessão";
  if (c.estado === "ativo") return c.ativadaEm ? `ativa ${haQuanto(c.ativadaEm, agora)}` : "ativa";
  if (c.estado === "provisionar") {
    return c.conviteEnviadoEm ? `convite enviado ${haQuanto(c.conviteEnviadoEm, agora)}` : "convite não saiu";
  }
  if (c.estado === "suspenso") return c.restricao ? `acesso restrito ${haQuanto(c.restricao.desde, agora)}` : "acesso restrito";
  if (c.estado === "encerrado") return c.encerradaEm ? `encerrada em ${dataLonga(c.encerradaEm)}` : "encerrada";
  return "";
}
