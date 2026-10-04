"use client";

// Barra institucional do App Shell interno (FE Onda A1) — porta ../sistema/chassi.css .topo (mesma
// marca+área-tag+tema-btn já usados em sessoes/[id]/plenario/page.tsx), agora compartilhada por QUALQUER
// página autenticada nova. `area` = o rótulo da seção atual (ex. "Painéis da Mesa"); quem está logado
// (nome+papel) o componente RESOLVE por conta própria via `useMeuIdentidade` — não é mais prop do caller.
//
// Conserto (fatia "demo-tres-consertos" #1, achado ao vivo — Daouda, 12/09/2026): até aqui `ator` era um
// literal fixo passado por CADA página (`{ nome: "Sérgio Lopes", papel: "Presidente da Mesa" }` na Mesa,
// "Rita Campos"/"Ana Ribeiro" alhures) — toda persona logada via o MESMO nome, sempre. GET /meu/identidade
// (identidade/diplomat/http/in.clj) devolve o ator REAL; `rotuloPapel` deriva o rótulo de exibição dos
// PAPÉIS (nunca do cargo de Mesa, que o backend de identidade não enxerga — ver docstring de rotulo-papel.ts).
// Enquanto carrega ou se a busca falhar, o cabeçalho NUNCA mostra um nome inventado — mostra que está
// carregando ou que a sessão está indisponível (mesma disciplina de honestidade da tela de votação, fatia 2).
//
// .topo/.marca/.tema-btn/.avatar já vivem em ../chassi.css (porte verbatim do design-system). .area-tag e
// .quem-mesa ainda NÃO foram promovidas ao chassi — hoje só existem inline em
// produto/design-system/o-plenario/telas/paineis-mesa.html (a superfície "cockpit"); copiadas verbatim
// para ./topo.css aqui. Promover ao chassi.css é decisão do design-system (PADROES-DE-COMPOSICAO.md,
// gatilho 2º-uso), não deste componente.

import Link from "next/link";
import { useTema } from "@/lib/tema";
import { useAuth } from "@/lib/auth";
import { useMeuIdentidade } from "@/lib/use-meu-identidade";
import { rotuloPapel } from "@/lib/rotulo-papel";
import { comToken } from "@/lib/nav";
import { useContagemDaCaixa } from "@/lib/use-comunicados";
import "./topo.css";

const DESTINOS_NAV: { rotulo: string; href: string; papel?: string | string[]; todos?: true }[] = [
  // Primeiro da lista de propósito: é o ponto de partida (a tela que responde "o que eu faço agora?") e a
  // única porta para as telas de sessão ao vivo, que não têm entrada de navegação própria.
  { rotulo: "Central", href: "/inicio" },
  // ADR-0020 (Eixo 7) — a caixa: os comunicados da Casa e os avisos do sistema. É de TODA pessoa interna, inclusive de
  // quem só administra, só audita ou só dá parecer (`todos`: escapa do recorte `soAdministracao` abaixo). Leva o número
  // do que está por ler (`useContagemDaCaixa`).
  { rotulo: "Caixa", href: "/caixa", todos: true },
  // Faixa A / A.5 da Track IA — busca intra-câmara (proposições + o que foi dito em plenário). Gated "secretario"
  // (GuardSecretaria + exige-papel no backend). Logo depois da Central: é a outra porta de entrada da secretaria.
  { rotulo: "Busca", href: "/busca" },
  // Faixa B / B.3 da Track IA — o assistente da Casa: pergunta em palavras, ele consulta o sistema com as permissoes de
  // quem pergunta (credencial delegada, ADR-0010) e responde citando. Gated "secretario" (GuardSecretaria; o backend
  // aceita secretario ou vereador).
  { rotulo: "Assistente", href: "/assistente" },
  // Faixa B / B.4 — as normas de referencia (LOM, Regimento, leis) que o assistente consulta; a secretaria importa e
  // confere. Gated "secretario" (GuardSecretaria + exige-papel no backend).
  { rotulo: "Normas", href: "/normas" },
  // Faixa B / B.8 — a conferência das proposições: a IA deixa uma nota técnica em rascunho a cada proposição
  // protocolada, e a secretaria aproveita ou descarta. Gated "secretario" (GuardSecretaria + exige-papel no backend).
  { rotulo: "Conferências", href: "/conferencias" },
  // ADR-0019 — a fila do parecer jurídico: o jurídico da Casa (papel `juridico`) redige e assina; a secretaria pede e
  // acompanha. É a única tela de trabalho do jurídico. Gated no GuardJuridico + exige-papel no backend.
  { rotulo: "Jurídico", href: "/juridico", papel: ["juridico", "secretario"] },
  // ADR-0021 Parte B — o julgamento das contas do Prefeito (e o acompanhamento das contas da Mesa). Secretaria, vereador
  // e jurídico leem; só a secretaria registra. Gated no GuardContas + exige-papel no backend.
  { rotulo: "Contas", href: "/contas", papel: ["secretario", "vereador", "juridico"] },
  // Faixa B / B.9 — a IA da Casa: consumo × orçamento e o que as pessoas fizeram com o resultado. Só para o
  // administrador da Casa (exige-papel "admin_ente" no backend) — por isso a entrada só aparece para ele.
  { rotulo: "IA da Casa", href: "/paineis/ia", papel: "admin_ente" },
  // ADR-0005 — a área do administrador da Casa (conceder acesso aos vereadores). Só para o admin_ente.
  { rotulo: "Administração", href: "/administracao", papel: "admin_ente" },
  // ADR-0017 — a trilha de auditoria. O servidor recorta pelo papel (auditor = a Casa inteira, admin_ente = os acessos,
  // secretaria = a própria); o `auditor` (controle interno) é quem mais a usa — é a única tela dele.
  { rotulo: "Auditoria", href: "/auditoria", papel: ["auditor", "admin_ente", "secretario"] },
  { rotulo: "Painéis da Mesa", href: "/paineis/mesa" },
  { rotulo: "Tramitação", href: "/tramitacao" },
  // Fatia 2b — a fila de cargas não recebidas (o rito exige que quem recebe assine). Ao lado de Tramitação:
  // é a outra metade do mesmo trabalho. Gated "secretario" (GuardSecretaria + exige-papel no backend).
  { rotulo: "Recebimentos", href: "/recebimentos" },
  // Faixa A / A.2 da Track IA — gravações enviadas pelo PC da transmissão que ainda não têm sessão. Gated
  // "secretario" (GuardSecretaria + exige-papel no backend). Vincular leva a gravação à transcrição e à ata.
  { rotulo: "Gravações", href: "/gravacoes" },
  { rotulo: "Proposições", href: "/proposicoes" },
  // Onda B Slice 6 — Expediente (gerar documento + Protocolo Geral) é área de topo nova, não sub-rota de
  // Proposições (documento administrativo não é matéria legislativa).
  { rotulo: "Expediente", href: "/expediente" },
  // Onda C Slice C2 — leitura da pauta de uma sessão agendada + convocação derivada (gated "secretario").
  { rotulo: "Pauta", href: "/pauta-convocacao" },
  // Agendar sessão (GAP docs/20 → tela de servidor): cria a sessão no estado agendada. Gated "secretario"
  // (GuardSecretaria na página + exige-papel no backend). Sem esta entrada a rota ficaria órfã.
  { rotulo: "Agendar sessão", href: "/agendar-sessao" },
  // Tempos da tribuna (pedido do stakeholder: "3 min e adicionais de 1 min") — a tabela de tempos regimentais
  // da Casa. Gated "secretario" (GuardSecretaria na página + exige-papel no backend). Sem esta entrada a rota
  // ficaria órfã.
  { rotulo: "Tempos da tribuna", href: "/tempos-da-tribuna" },
  // Cadastro de Vereadores (Task 9) — cadastros estruturais, área de topo nova (arquétipo master-detail).
  { rotulo: "Vereadores", href: "/cadastros/vereadores" },
  // Onda E — o livro de atas: as atas publicadas das sessões (a de sessão secreta, só para a secretaria — o servidor
  // filtra). Sem papel: vereador e secretaria leem o mesmo livro.
  { rotulo: "Atas", href: "/atas" },
  // Onda E fatia 2 — Calendário institucional (a agenda da Casa: sessões agendadas + prazos de
  // compliance). Sem esta entrada a rota existiria órfã, alcançável só por URL digitada.
  { rotulo: "Calendário", href: "/calendario" },
  // O balcão de atendimento ao cidadão (6.1 e-SIC, 6.2 ouvidoria, 5.10 LGPD): as filas do que o cidadão pediu pelo
  // portal, pelo prazo legal que vence primeiro. Gated "secretario" (GuardSecretaria + exige-papel no backend).
  { rotulo: "Atendimento", href: "/atendimento" },
  // Moderação de comentários (GAP docs/20 → tela de servidor): fila de pendentes + aprovar/rejeitar.
  // Gated "secretario" (GuardSecretaria na página + exige-papel no backend).
  { rotulo: "Moderação", href: "/moderacao" },
];

/** As entradas da nav que o ator vê. Entrada com `papel` só aparece para quem tem (um d)ele; a marcada `todos` (a
 *  caixa, ADR-0020), para qualquer pessoa. As SEM papel são as telas
 *  de trabalho da secretaria/Mesa — quem só administra, só audita ou só dá parecer jurídico na Casa
 *  (admin_ente/auditor/juridico sem secretario nem vereador: como nasce o 1º administrador provisionado, ADR-0016, o
 *  controle interno, ADR-0017, e o jurídico, ADR-0019) não as vê, porque cada uma o levaria a "Acesso restrito". */
export function destinosVisiveis(papeis: string[]) {
  const soAdministracao =
    (papeis.includes("admin_ente") || papeis.includes("auditor") || papeis.includes("juridico")) &&
    !papeis.includes("secretario") && !papeis.includes("vereador");
  return DESTINOS_NAV.filter((d) =>
    d.todos ? true : d.papel ? [d.papel].flat().some((p) => papeis.includes(p)) : !soAdministracao);
}

export function TopoInterno({ area }: { area: string }) {
  const { tema, alternar } = useTema();
  const { token } = useAuth();
  const { dados, estado } = useMeuIdentidade(token);
  const porLer = useContagemDaCaixa(token);
  // Nunca um nome inventado: "carregando"/"erro" são rótulos HONESTOS, não um ator fixo. `estado==="erro"`
  // cobre tanto a falha de rede quanto a resposta não-ok (ver docstring de useMeuIdentidade).
  const nome = estado === "pronto" && dados ? dados.nome : estado === "carregando" ? "Carregando…" : "Sessão";
  const papel =
    estado === "pronto" && dados ? rotuloPapel(dados.papeis) : estado === "carregando" ? "" : "indisponível";
  return (
    <header className="topo">
      <div className="envelope topo-grade">
        <div className="marca">
          <Brasao />
          <div>
            <p className="marca-nome">O&nbsp;Plenário</p>
            <p className="marca-orgao">Câmara Municipal</p>
          </div>
        </div>
        <div className="topo-sep" aria-hidden="true" />
        <span className="area-tag">{area}</span>
        <nav className="nav-interna" aria-label="Navegação interna">
          {destinosVisiveis(dados?.papeis ?? []).map((d) => (
            <Link
              key={d.href}
              href={comToken(d.href, token)}
              aria-current={d.rotulo === area ? "page" : undefined}
              // prefetch={false}: cada destino da nav é autenticado e, sem sessão válida, responde 401 e
              // redireciona para /entrar. O prefetch do Next dispara essas navegações em segundo plano — que
              // abortam em massa (ERR_ABORTED) e nunca deixam a rede assentar (achado do teste exploratório:
              // "prefetch storm"). Sem prefetch, a rota só é buscada no clique real. Custo: primeira navegação
              // sem pré-aquecimento — desprezível numa barra de app interno.
              prefetch={false}
            >
              {d.rotulo}
              {d.href === "/caixa" && porLer !== null && porLer > 0 && (
                <>
                  {/* o número é visual; o leitor de tela ouve a frase (", 3 por ler") como parte do link */}
                  <span className="nav-contagem" aria-hidden="true">{porLer > 99 ? "99+" : porLer}</span>
                  <span className="sr-only">, {porLer === 1 ? "1 por ler" : `${porLer} por ler`}</span>
                </>
              )}
            </Link>
          ))}
        </nav>
        <div className="topo-dir">
          <button className="tema-btn" type="button" aria-pressed={tema === "escuro"} onClick={alternar} title="Alternar tema claro / escuro">
            {tema === "escuro" ? "☾" : "☀"}
            <span className="tema-rotulo">{tema === "escuro" ? "Escuro" : "Claro"}</span>
          </button>
          <div className="quem-mesa">
            <span className="avatar" aria-hidden="true">
              {nome.split(" ").map((p) => p[0]).slice(0, 2).join("").toUpperCase()}
            </span>
            <span className="quem">
              <b>{nome}</b>
              <span>{papel}</span>
            </span>
          </div>
        </div>
      </div>
    </header>
  );
}

function Brasao() {
  return (
    <svg className="marca-simbolo" viewBox="0 0 40 40" role="img" aria-label="O Plenário">
      <circle cx="20" cy="20" r="19" fill="#FFF7EA" stroke="#A6BFA2" />
      <path d="M7 27 A13 13 0 0 1 33 27" fill="none" stroke="#2C5638" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M10 27 A10 10 0 0 1 30 27" fill="none" stroke="#3F6E92" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M13.5 27 A6.5 6.5 0 0 1 26.5 27" fill="none" stroke="#C0693F" strokeWidth="2.4" strokeLinecap="round" />
      <rect x="18.4" y="9.5" width="3.2" height="6" rx="1.2" fill="#CFA65C" />
    </svg>
  );
}
