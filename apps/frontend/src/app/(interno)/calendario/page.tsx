"use client";

// Calendário institucional (Onda E, fatia 2) — porte de
// produto/design-system/o-plenario/telas/calendario.html: grade do mês + agenda lateral ("Próximos").
//
// O DESVIO deliberado em relação ao design, e o motivo de cada um (medido no backend, não suposto):
//   - o design pinta QUATRO famílias de evento (sessão, comissão, audiência pública, prazo) e ainda um
//     card de "Recesso". Só DUAS existem no backend: sessão (GET /sessoes) e prazo de compliance
//     (GET /compliance/painel, bloco `em-aberto`). Reunião de comissão não é entidade agendável
//     (`comissao-id` só existe como atributo de parecer); audiência pública não existe; recesso só
//     existe como estado transitório de plenário DENTRO de uma sessão aberta. As três saem como um
//     EmBreve com motivo concreto — nunca como célula pintada, nunca como legenda que promete cor para
//     um evento que a Casa não registra.
//   - o card do design diz "14h · Plenário". `SessaoOut` NÃO tem campo de local (conferido em
//     apps/backend .../sessoes/adapters/out/sessao.clj) — "Plenário" era texto estático da maquete, e
//     escrevê-lo aqui seria afirmar um local que o dado não sustenta. A meta leva hora e estado.
//
// Toda a derivação (fuso, grade, ordenação, "próximos") vive em @/lib/calendario-vista; a busca, em
// @/lib/use-calendario. Esta página só compõe — nenhuma regra de apresentação mora aqui.

import { useEffect, useMemo, useState } from "react";
import { useAuth, usePapeis } from "@/lib/auth";
import { EmBreve } from "@/lib/em-breve";
import { useCalendario } from "@/lib/use-calendario";
import {
  DIAS_DA_SEMANA,
  derivarCalendario,
  hojeLocal,
  mesAnterior,
  mesSeguinte,
  msAteViradaDoDia,
  partesDoDia,
  type CelulaDia,
  type EventoCalendario,
  type Mes,
} from "@/lib/calendario-vista";
import { TopoInterno } from "../topo";
import "./calendario.css";

const NOME_TIPO: Record<EventoCalendario["tipo"], string> = { sessao: "Sessão", prazo: "Prazo" };
// Glifo é o segundo canal, ao lado da cor: verde-jade (sessão) e telha (prazo) são exatamente o par que
// um deuteranope não separa. Sem isto a grade seria "só cor" (GUIDELINES-CHECKLIST).
const GLIFO_TIPO: Record<EventoCalendario["tipo"], string> = { sessao: "●", prazo: "◆" };

function mesDoDia(dia: string): Mes {
  const [ano, mes] = dia.split("-");
  return { ano: Number(ano), mes: Number(mes) };
}

export default function PaginaCalendario() {
  const { token } = useAuth();
  // `hoje` é ESTADO, não memo congelado: derivarCalendario continua puro (recebe o dia como parâmetro),
  // mas o parâmetro é revalidado na virada do dia da Casa. Uma tela de secretaria fica aberta a noite
  // toda; sem isto, às 9h da manhã seguinte a pílula "hoje" e o `aria-current="date"` ainda apontam
  // ontem, e o card "Próximos" ainda lista a sessão de ontem como compromisso futuro.
  const [hoje, setHoje] = useState<string>(() => hojeLocal());
  const [mes, setMes] = useState<Mes>(() => mesDoDia(hojeLocal()));

  useEffect(() => {
    // Um timeout por virada (não um poll): dorme até a meia-noite da Casa e se reagenda pelo `hoje` novo.
    // A margem de 1s cobre o arredondamento do relógio — sem ela o timer pode acordar no último
    // milissegundo do dia anterior e reagendar para daqui a nada.
    const t = setTimeout(() => setHoje(hojeLocal()), msAteViradaDoDia() + 1000);
    return () => clearTimeout(t);
  }, [hoje]);

  // Prazos (GET /compliance/painel) sao `secretario`-only por design: em vez de o hook bater na porta e
  // tomar 403 (ruido no console p/ um vereador), resolvemos a permissao pelo papel e a passamos. Enquanto
  // o papel carrega (modo real, GET /eu), "aguardando" segura os prazos em "carregando"; sem acesso,
  // "bloqueado" degrada direto (mesma tela de "prazos nao vieram"). Achado docs/20.
  const { papeis, estado: estadoPapeis } = usePapeis();
  const prazosPermissao =
    estadoPapeis === "carregando" ? "aguardando" : papeis.includes("secretario") ? "buscar" : "bloqueado";
  const { sessoes, estadoSessoes, obrigacoes, truncamentoPrazos, estadoPrazos } = useCalendario(
    token,
    prazosPermissao,
  );

  const vista = useMemo(
    () => derivarCalendario({ ano: mes.ano, mes: mes.mes, hoje, sessoes, obrigacoes }),
    [mes, hoje, sessoes, obrigacoes],
  );

  // As duas fontes são independentes (e uma delas, o painel de compliance, exige o papel `secretario`).
  // Só se pode AFIRMAR ausência quando as DUAS responderam — antes disso a tela cala sobre o vazio.
  const ambasProntas = estadoSessoes === "pronto" && estadoPrazos === "pronto";
  const carregando = estadoSessoes === "carregando" || estadoPrazos === "carregando";

  return (
    <>
      <TopoInterno area="Calendário" />

      <main id="conteudo" className="envelope cal-pagina">
        <div className="pg-cab">
          <div>
            <span className="eyebrow">Calendário institucional</span>
            <h1>Agenda da Casa</h1>
          </div>
          <div className="mes-nav">
            <button className="seta" type="button" aria-label="Mês anterior" onClick={() => setMes(mesAnterior(mes))}>
              <Seta direcao="anterior" />
            </button>
            <span className="atual">{vista.titulo}</span>
            <button className="seta" type="button" aria-label="Próximo mês" onClick={() => setMes(mesSeguinte(mes))}>
              <Seta direcao="proximo" />
            </button>
          </div>
        </div>

        {/* Falha de UMA fonte não apaga a outra: o que veio continua na tela, e o que não veio é DITO.
            Sem estas duas linhas, um 403 no painel de compliance deixaria a grade sem prazo nenhum —
            visualmente idêntica a uma Casa em dia com o TCE. */}
        {estadoSessoes === "erro" && (
          <p role="status" className="aviso-fonte">
            As <b>sessões não puderam ser carregadas</b>. O que está abaixo pode estar incompleto.
          </p>
        )}
        {estadoPrazos === "erro" && (
          <p role="status" className="aviso-fonte">
            Os <b>prazos de compliance não puderam ser carregados</b> — esta leitura exige o papel de
            secretário. O que está abaixo pode estar incompleto.
          </p>
        )}
        {/* O painel de compliance corta `em-aberto` em 100 SEM sinalizar (medido no backend — ver o
            cabeçalho de use-calendario). Com backlog, as VENCIDAS ocupam os primeiros slots e a remessa
            FUTURA do TCE some da grade: o servidor lê um mês sem nenhum losango e conclui que não há
            prazo. Enquanto a rota não falhar fechada, a tela é quem diz que está vendo uma página. */}
        {truncamentoPrazos && (
          <p role="status" className="aviso-fonte">
            A lista de prazos veio <b>cortada em {truncamentoPrazos.exibidos} de {truncamentoPrazos.total}</b>{" "}
            em aberto — a grade pode estar sem prazos que existem, inclusive os mais distantes. Confira no
            painel de compliance antes de contar com o calendário.
          </p>
        )}
        {carregando && (
          <p role="status" className="aviso-fonte">
            Carregando a agenda…
          </p>
        )}

        <div className="legenda">
          <span className="lg">
            <span className="pt pt-sessao" aria-hidden="true">
              {GLIFO_TIPO.sessao}
            </span>
            Sessão plenária
          </span>
          <span className="lg">
            <span className="pt pt-prazo" aria-hidden="true">
              {GLIFO_TIPO.prazo}
            </span>
            Prazo de compliance
          </span>
        </div>

        <div className="grade">
          <div className="cal">
            <div className="cal-grid" role="grid" aria-label={vista.titulo}>
              <div className="cal-dows" role="row">
                {DIAS_DA_SEMANA.map((d) => (
                  <span key={d} role="columnheader">
                    {d}
                  </span>
                ))}
              </div>
              {vista.semanas.map((semana) => (
                <div className="cal-linha" role="row" key={semana[0].iso}>
                  {semana.map((c) => (
                    <Celula key={c.iso} celula={c} />
                  ))}
                </div>
              ))}
            </div>
          </div>

          <aside className="rail" aria-label="Próximos eventos">
            <div className="rcard">
              <h2>Próximos</h2>
              {vista.proximos.map((e) => (
                <ItemAgenda key={e.id} evento={e} />
              ))}
              {vista.proximosOcultos > 0 && (
                <p className="mais-adiante">+{vista.proximosOcultos} mais adiante</p>
              )}
              {vista.proximos.length === 0 && ambasProntas && (
                <p className="vazio">Nada agendado daqui para frente.</p>
              )}
              {/* "Nada adiante" e "a Casa não registrou nada" são coisas distintas, e a grade calada não
                  separa as duas: sem esta linha, um mês vazio de Casa recém-migrada é visualmente igual a
                  um mês vazio de Casa em dia. */}
              {vista.vazio && ambasProntas && (
                <p className="vazio">
                  Esta Casa ainda não tem <b>nenhuma sessão nem prazo registrado</b> — a grade está vazia
                  porque não há o que mostrar, não porque a carga falhou.
                </p>
              )}
              <p className="nota-gap">
                <span className="tag">[GAP]</span> O vencimento do prazo é o que a regra de compliance
                configurada para esta Casa calculou. O calendário oficial do TCE-CE (dias corridos ou
                úteis) ainda não foi confirmado — confira no Tribunal antes de contar com a data.
              </p>
            </div>

            <EmBreve
              titulo="Comissões, audiências e recesso"
              motivo="A Casa ainda não registra reunião de comissão, audiência pública nem recesso como evento com data: não existe cadastro desses eventos no sistema. Só sessões plenárias e prazos de compliance têm data — e é só isso que este calendário mostra."
            />
          </aside>
        </div>
      </main>
    </>
  );
}

function Celula({ celula }: { celula: CelulaDia }) {
  const classe = ["dia", celula.foraDoMes ? "fora" : "", celula.hoje ? "hoje" : ""].filter(Boolean).join(" ");
  return (
    <div
      className={classe}
      role="gridcell"
      aria-label={celula.rotuloDia}
      // `aria-current="date"` é o canal não-visual do "hoje": a pílula preenchida sozinha seria cor.
      aria-current={celula.hoje ? "date" : undefined}
    >
      <span className="n">
        {celula.dia}
        {celula.hoje && <span className="sr-only"> (hoje)</span>}
      </span>
      {celula.eventos.length > 0 && (
        <ul className="ev-lista">
          {celula.eventos.map((e) => (
            // O nome acessível diz o TIPO por extenso ("Sessão: …"/"Prazo: …") — na grade, a cor e o
            // glifo são o canal visual, e este é o canal do leitor de tela. `<li>` (role=listitem) é
            // nomeável por aria-label; um <span> solto não seria.
            <li
              key={e.id}
              className={`ev ev-${e.tipo}`}
              aria-label={`${NOME_TIPO[e.tipo]}: ${e.titulo}${e.meta ? ` · ${e.meta}` : ""}`}
            >
              <span className="ev-glifo" aria-hidden="true">
                {GLIFO_TIPO[e.tipo]}
              </span>
              {e.hora && <span className="hh">{e.hora}</span>}
              <span className="ev-texto">{e.rotulo}</span>
              {/* O estado vinha SÓ no aria-label: uma sessão `nao_realizada` pintava o mesmo ● jade com o
                  mesmo "15ª Ordinária" de uma agendada, e uma obrigação `vencida` o mesmo ◆ de uma
                  pendente. Quem enxerga ficava sabendo menos que o leitor de tela. */}
              {e.alerta && <span className="ev-alerta">{e.alerta}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function ItemAgenda({ evento }: { evento: EventoCalendario }) {
  const { numero, mesCurto } = partesDoDia(evento.dia);
  return (
    <div className="ag">
      <div className="data">
        <b>{numero}</b>
        <span>{mesCurto}</span>
      </div>
      <div className="info">
        <b>{evento.titulo}</b>
        <div className="meta">
          <span className={`tipo t-${evento.tipo}`}>{evento.tipo === "sessao" ? "sessão" : "prazo"}</span>
          {evento.meta && <span>{evento.meta}</span>}
        </div>
      </div>
    </div>
  );
}

function Seta({ direcao }: { direcao: "anterior" | "proximo" }) {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <path d={direcao === "anterior" ? "M15 6l-6 6 6 6" : "M9 6l6 6-6 6"} />
    </svg>
  );
}
