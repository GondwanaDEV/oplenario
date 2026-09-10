"use client";

// Orquestração de "conceder acesso ao vereador" (Onda D Slice 5, Task 11) — o ÚNICO lugar do FE que
// encadeia os 3 passos do provisionamento (identidade -> ligar cadastro -> conceder acesso). O backend
// DELIBERADAMENTE não tem um módulo orquestrador (§22.10:26 proíbe módulo backend que orquestra sem
// possuir verdade própria — cada passo é dono da sua própria tabela: `identidade`, `cadastros`,
// `identidade` de novo para o vínculo/convite); a administração do ente é área de UI, não de domínio
// (docstring de `identidade/diplomat/http/in.clj`). Por isso é O FRONT que decide a sequência.
//
// A ORDEM É A GARANTIA DE SEGURANÇA (spec §4.2 "acesso por último"): só o passo 3
// (POST /identidade/acessos) abre a porta — cria o vínculo ATIVO que `resolver-sessao` exige pra alguém
// logar, e é o único passo que dispara o convite do Keycloak. Os passos 1 e 2 NUNCA concedem nada (passo 1
// é idempotente por CPF; passo 2 só liga o cadastro à identidade). Parar em qualquer ponto antes do passo 3
// deixa o sistema FECHADO — provado no domínio por Task 10 (marco fail-closed do provisionamento). Trocar a
// ordem abriria a porta cedo demais (ex.: conceder acesso a uma identidade ainda não ligada ao cadastro
// certo) ou tarde demais sem necessidade. NÃO REORDENAR.
//
// `concederAcesso` é a função PURA que prova essa propriedade isoladamente — recebe a função de chamada
// HTTP por injeção (sem depender de React/auth), porque a garantia de segurança não é sobre estado de UI,
// é sobre ORDEM de efeitos colaterais. `useConcederAcesso` a envolve com os 3 estados de rede, espelhando
// use-registrar-mandato.ts (mesmo idioma ocioso/enviando/erro, mesmo guard de reentrância e de
// desmontagem) — a diferença é que o alvo não é uma rota, são as 3 em sequência, cada uma passando o token
// do ator via `apiFetch` (nunca fetch cru em produção: é `apiFetch` quem decide Bearer vs. cookie
// `sessao`, ver boundary em api-fetch.ts).

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { semCredencial } from "./modo";

export type ConcederAcessoEntrada = {
  vereadorId: string;
  cpf: string;
  nome: string;
  email: string;
};

type ChamarApi = (url: string, init: RequestInit) => Promise<Response>;

async function postar(chamar: ChamarApi, url: string, metodo: "POST" | "PATCH", corpo: unknown) {
  const r = await chamar(url, {
    method: metodo,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(corpo),
  });
  if (!r.ok) {
    const corpoErro = await r.json().catch(() => null);
    const msg = corpoErro?.erro ?? `falha em ${url} (status ${r.status})`;
    throw new Error(msg);
  }
  return r.json();
}

// A função pura testada isoladamente (use-conceder-acesso.test.ts, describe "a ordem é a garantia de
// segurança"): a ordem dos 3 `await` abaixo NÃO É incidental, é o contrato inteiro desta função. Não
// reordenar, não paralelizar (Promise.all quebraria a garantia "acesso por último" — os passos 2 e 3
// dependem do resultado do passo anterior de qualquer forma, então nem seria possível por acidente).
export async function concederAcesso(
  entrada: ConcederAcessoEntrada,
  chamar: ChamarApi = fetch,
): Promise<{ identidadeId: string }> {
  // 1. Identidade por CPF — idempotente (mesmo CPF em Casas diferentes = mesma identidade), NÃO concede
  //    acesso nenhum: sem vínculo, `resolver-sessao` não resolve ninguém.
  const passo1 = (await postar(chamar, "/api/identidade/identidades", "POST", {
    cpf: entrada.cpf,
    nome: entrada.nome,
  })) as { "identidade-id": string };
  const identidadeId = passo1["identidade-id"];

  // 2. Liga o cadastro do vereador à identidade — ainda NÃO concede acesso (só associa os dois registros).
  await postar(
    chamar,
    `/api/cadastros/vereadores/${encodeURIComponent(entrada.vereadorId)}/identidade`,
    "PATCH",
    { "identidade-id": identidadeId },
  );

  // 3. Concede o acesso — a porta abre SÓ AQUI: cria o vínculo ativo e dispara o convite (Keycloak).
  await postar(chamar, "/api/identidade/acessos", "POST", {
    "identidade-id": identidadeId,
    tipo: "vereador",
    papeis: ["vereador"],
    email: entrada.email,
  });

  return { identidadeId };
}

type Estado = "ocioso" | "enviando" | "erro";

export function useConcederAcesso(token: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);
  useEffect(() => {
    // RE-ARMA no mount. Sem esta linha o ref nasce FALSE sob React StrictMode (dev), porque o
    // StrictMode roda cleanup+setup no primeiro mount — e entao TODO setEstado pos-resposta vira
    // no-op e nenhuma mensagem de erro do servidor chega na tela. Medido: 1,5s depois de um 409 o
    // botao seguia "Salvando..." com zero alerta na pagina. Os hooks de LEITURA ja faziam assim.
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function conceder(entrada: ConcederAcessoEntrada): Promise<{ identidadeId: string }> {
    if (semCredencial(token)) throw new Error("sem token de autenticacao");
    if (enviandoRef.current) throw new Error("envio em andamento");
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    try {
      // O token do ator (admin_ente) viaja em CADA um dos 3 passos via `apiFetch` — não só no primeiro.
      const chamar: ChamarApi = (url, init) => apiFetch(url, { ...init, token: token ?? undefined });
      const resultado = await concederAcesso(entrada, chamar);
      if (vivoRef.current) setEstado("ocioso");
      return resultado;
    } catch (e) {
      if (vivoRef.current) {
        setEstado("erro");
        setErro(e instanceof Error ? e.message : "falha ao conceder acesso");
      }
      throw e;
    } finally {
      enviandoRef.current = false;
    }
  }
  return { conceder, estado, erro };
}
