# 22.5 Modelo de autenticação e autorização

> *Parte do SSOT (documento-mestre). Versão canônica do conjunto em `documento-mestre-camaras.md` §24.*
> *Em conflito com memória de chat antigo, este arquivo prevalece.*

Esta subseção consolida as decisões fechadas sobre autenticação (quem é a pessoa) e autorização (o que ela pode fazer) para todos os atores do sistema: cidadão, servidor, vereador, presidente da Mesa, secretário, e admin interno (nosso). As decisões foram tomadas em sete eixos discutidos individualmente; aqui estão sintetizadas como referência canônica.

## 22.5.1 Visão geral e taxonomia de sujeitos e mecanismos

O sistema reconhece quatro categorias de ator com mecanismos de autenticação distintos:

**Cidadão.** Autenticação exclusiva via gov.br. Bronze, prata e ouro aceitos uniformemente na V1 — sem distinção de nível por fluxo. Sem cadastro próprio com e-mail/senha. A capacidade de exigir nível mínimo por fluxo existe na arquitetura mas não é exposta na V1; configuração futura possível sem refactor.

**Servidor.** Autenticação interna do produto, **passwordless-first**: WebAuthn/passkey é o **fator primário recomendado** (phishing-resistant por amarração ao origin, multifator num único gesto, menos atrito que senha+TOTP); senha + TOTP permanece como **piso obrigatoriamente disponível** para quem não usa passkey. SMS não é aceito por default (SIM swap é vetor real). **E-mail código de uso único** serve só ao bootstrap de primeiro acesso (enrollment) e à recuperação — nunca como fator standing (§22.5.2 eixo F). Recuperação de senha autoatendida para perfis comuns; com aprovação de admin do ente para perfis de poder elevado (secretário-geral e equivalentes).

**Vereador.** Mesmo padrão passwordless-first do servidor: WebAuthn/passkey primário (o app mobile do vereador, §16.7, é o caso ideal — Face ID/digital, alinhado à "assinatura em 2 toques"), senha + TOTP como piso. Sem gov.br vinculado. Sem ICP-Brasil no login. Identidade institucional, cadastrada pelo admin do ente no início da legislatura. ICP-Brasil é reservada exclusivamente para step-up de assinatura digital (§22.5.2 eixo F).

**Presidente / Secretário da Mesa.** Não é tipo de usuário separado. É papel (cargo) acumulado por um vereador durante o mandato bienal da Mesa. Mecânica vive nos eixos C (papéis temporais) e D (escopo ativo).

**Admin interno (nosso, da SaaS).** IdP fisicamente separado do IdP dos clientes desde a V1. WebAuthn com hardware key física obrigatório (sem passkey sincronizado). TOTP como backup com justificativa registrada. Política de senha mais dura, lifecycle (onboarding/offboarding) separado, audit log com retenção máxima. **É principal supratenant, não vínculo de tenant** — mora no módulo `admin_sistema` (§22.10): o operador da plataforma não tem Ente-casa, ele os provisiona. Agir *sobre* um tenant específico é um **grant de acesso de suporte** (§22.5.2 eixo D), nunca vínculo standing.

**Posição estrita da ICP-Brasil.** ICP-Brasil é mecanismo de **assinatura digital com valor jurídico de não-repúdio**, exclusivamente. Não é fator de autenticação. Não é fator de step-up genérico. É invocada apenas no ato de assinar artefato legal (proposição, parecer, ata, resolução). Distinção autenticação ↔ assinatura é arquitetural, não cosmética.

## 22.5.2 Decisões por eixo

**Eixo A — Sujeitos e fluxos de autenticação por ator.** Detalhado em §22.5.1. Pontos não-óbvios consolidados: cidadão sem conta gov.br cria conta no fluxo do próprio gov.br (externo a nós), sem fricção adicional na V1. Comentário/manifestação anônima é decisão de produto separada da decisão de identidade — pode ser oferecida como configuração por ente (formulário público sem login com moderação manual assumida pelo ente), e não confundir com cadastro próprio. Provedor de IdP concreto (Cognito vs. Keycloak vs. Auth0 vs. próprio) é decisão de stack, deferida para chat dedicado.

**Eixo B — Modelo de autorização: híbrido pragmático.** RBAC clássico para papéis estáticos centrados em pessoa + regras dinâmicas via DSL pequena compartilhada com tramitação (§22.4 eixo C) e compliance (Invariante 4). Decisões dinâmicas avaliadas como expressões sobre **funções de relação** expostas pelos bounded contexts donos dos recursos. Sem peça de infra dedicada de auth (sem OPA, sem OpenFGA) na V1. Migração futura para ReBAC dedicado é viável se complexidade explodir, porque as relações já estão modeladas explicitamente no domínio.

Inventário de funções de relação (consolidado em validação do eixo B):

*Expostas por Cadastros Estruturais:* `tem_mandato_vigente(usuario, ente, instante)`, `é_membro_de_comissao(usuario, comissao, instante)`, `é_presidente_de_comissao(usuario, comissao, instante)`, `é_presidente_da_mesa(usuario, ente, instante)`, `é_secretario_da_mesa(usuario, ente, instante)`, `quem_exerce_presidencia(ente, instante, sessao_opcional)`.

*Expostas por Processo Legislativo:* `é_autor_de(usuario, proposicao)`, `é_coautor_de(usuario, proposicao)`, `é_relator_de(usuario, parecer)`.

*Expostas por Sessões Plenárias:* `está_presente_em(usuario, sessao)`.

*Transversal:* `é_o_próprio(usuario, sujeito)` — utilitário simples para self-action ("editar próprio comentário", "ver próprio audit log", "justificar própria ausência").

Total: ~10 funções de relação + 2 consultas ao motor de tramitação (estado do recurso permite ação? recurso dentro do prazo?). Cabe confortavelmente no híbrido pragmático.

**Eixo C — Papéis: granularidade, composição, temporalidade.**

*Granularidade.* Papéis estáticos centrados em pessoa em tabela `usuario_papel`. Papéis contextuais a recurso (relator de parecer, autor de proposição, membro de comissão) modelados no próprio recurso — colunas FK ou tabelas de junção próprias — e expostos como funções de relação pelo bounded context dono. O papel **`admin_ente`** (administrador da câmara) é um desses estáticos, **escopado ao tenant** via vínculo (tem `ente_id`); seus poderes — gerir usuários/vínculos do ente, resetar MFA, aprovar recuperação de poder elevado, gerir config do ente — são operações que já moram nos módulos donos (`identidade`/`cadastros`), expostas via `http_server` e gated por `policy.check(papel=admin_ente)`. **A administração do ente não é módulo backend — é área de UI** (§22.10) que compõe esses endpoints via HTTP; é espaço de RBAC de tenant, **disjunto** do RBAC supratenant do `admin_sistema`. Razões: coerência com modelagem já feita em §22.4 (relator é atributo de parecer); preserva integridade referencial declarativa; cardinalidade fala alto (relator é coluna, membro é tabela de junção); funções de relação do eixo B mapeiam diretamente.

*Mandato.* Entidade explícita com estados (`vigente`, `licenciado`, `cassado`, `renunciado`, `falecido`, `concluido`). Tabela `mandato_licenca` com vínculo ao mandato do suplente em exercício durante a licença. Tabela `suplencia` cadastra ordem de suplentes por (legislatura, ente, partido). Suplente que assume recebe mandato próprio de natureza "exercício de suplência" — não vira "vereador titular". Cassação, renúncia e falecimento preenchem `fim_efetivo` e mudam `estado`; ato é audit-logged.

*Mesa Diretora.* Tipo especial de comissão (`tipo='mesa'`) com cargos nomeados em tabela `comissao_cargo`. Mandato bienal via `vigencia_inicio`/`vigencia_fim`. Eleição da Mesa via template de tramitação dedicado (motor declarativo do eixo C de §22.4). Reuso máximo: mesma mecânica de comissão para deliberações da Mesa (resoluções, decisões de pauta), votação, ata.

*Titularidade vs. exercício.* Função `quem_exerce_presidencia(ente, instante, sessao_opcional)` resolve em camadas: (a) fora de sessão, titular do cargo `presidente` na Mesa vigente, com fallback pela ordem regimental se titular licenciado; (b) durante sessão, consulta `sessao.presidencia_em_exercicio_id` (atualizável durante a sessão via evento `PresidenciaPassada` append-only). Ordem regimental de substituição mora no template de regimento configurável por ente.

*Composição de papéis.* Aditiva, sem precedência geral. Cascata via `tem_mandato_vigente`: licença derruba todos os papéis temporais sem necessidade de desativar individualmente — mas a *atribuição* dos papéis permanece registrada (vereador continua sendo o presidente eleito da Mesa, só não está em exercício enquanto licenciado). Suplente que assume *não* herda automaticamente cargos na Mesa nem membership em comissões do titular licenciado.

*Temporalidade e auditoria.* Toda atribuição de papel/cargo/membership tem `vigencia_inicio` e `vigencia_fim` (nullable). Append-only com mutação controlada (taxonomia de imutabilidade §22.4.3 disciplina 4 nível b). Eventos de domínio em todas as transições. Funções de relação aceitam `instante` como parâmetro com default `now()`; consulta histórica usa `instante` específico.

**Eixo D — Sessão, escopo ativo e multi-perfil.**

*Identidade ↔ Vínculo separados.* Identidade ancorada em CPF (entidade `identidade`). Vínculo é a relação da identidade com um Ente (servidor, vereador) ou Município (cidadão, conforme Invariante 1). Mesmo CPF pode ter múltiplos vínculos: vereador na Câmara X + cidadão no Município Y + cidadão no Município Z. Provedor de identidade externa (gov.br para cidadão) vincula via `identidade_externa(identidade_id, provedor, sub)`. **O admin interno (operador SaaS) é caso à parte: principal supratenant no módulo `admin_sistema` (§22.10), no IdP separado — não é vínculo de tenant** (não tem Ente-casa; ele os cria). Quando o operador precisa agir sobre um tenant, abre um **grant de acesso de suporte**: escopado a um ente, com prazo, justificativa, consentimento/LGPD e auditoria, sob hardware key + step-up (eixo F) — nunca vínculo standing.

*Escopo ativo: um vínculo por sessão.* Token carrega `(identidade_id, vinculo_ativo_id, tipo_vinculo, ente_id, papeis_estaticos_snapshot, expira_em)`. Não há sessão multi-vínculo simultânea. Trocar de vínculo é nova sessão (UI suave: botão "trocar perfil"; backend explícito: novo token).

*Multi-perfil resolvido pela camada Identidade.* UI oferece troca de perfil quando identidade tem múltiplos vínculos. Permissões não vazam entre perfis — vereador acessando como cidadão no mesmo CPF não traz consigo poderes de vereador. Auditoria registra explicitamente "identidade X agindo como vínculo Y no ente Z". Para moderação por servidor de outro ente: UI por padrão mostra só o vínculo ativo; admin/audit interno tem acesso à identidade subjacente quando justificado.

*Token = snapshot de papéis estáticos com TTL curto + revogação imediata.* Papéis estáticos do RBAC (`vereador`, `servidor_protocolo`, `presidente_mesa`) entram no token como snapshot no momento de emissão. Relações dinâmicas (relator, presente, membership) são **sempre** consultadas em runtime via funções de relação — nunca cabem no token. TTLs configuráveis com defaults (§22.5.3): access token de 30min para cidadão, 15min para servidor/vereador, 15min para admin interno; refresh token de 7d/24h/1h respectivamente. Revogação imediata via lista de tokens revogados em eventos críticos (`MandatoCassado`, `ServidorDesligado`, etc.) — quem dispara o evento de domínio também publica `TokensRevogadosPorIdentidade(identidade_id)`.

*Refresh, expiração e logout.* Padrão clássico access + refresh, com rotation a cada uso. Logout invalida refresh; access continua válido até TTL expirar (aceitável dado TTL curto). Logout urgente dispara revogação imediata. Sessões simultâneas em múltiplos dispositivos permitidas (vereador no app mobile + web), sem limite duro de sessões na V1. Admin interno: TTL especialmente curto, reautenticação com hardware key obrigatória para qualquer ação que atravesse `ente_id`.

**Eixo E — Avaliação dinâmica de permissões.**

*Topologia: defesa em profundidade.* Camada externa (middleware) faz checagens grossas que não dependem do recurso: token válido criptograficamente, `ente_id` da request bate com `ente_id` do token, vínculo ativo vigente, papel snapshot autoriza categoria da ação. **Caso supratenant (§22.10):** token de operador SaaS **não carrega `ente_id`** — o middleware trata `ente_id` ausente como escopo supratenant, válido **só** em endpoints de `admin_sistema`; token de tenant batendo endpoint supratenant (ou token supratenant batendo endpoint de tenant fora de grant de suporte) recebe 403/404. Fecha a ambiguidade do `ente_id` nulo (sem fail-open silencioso). Camada interna (in-domain) faz checagem fina por operação: cada operação de domínio chama `policy.check(ator, ação, recurso)` com o recurso já carregado, avaliando papéis estáticos do snapshot + funções de relação contra o recurso. Engine externa centralizada (PDP/OPA) descartada explicitamente — peso desnecessário na V1.

Mecanismos para garantir disciplina interna: convenção de naming (`dominio.acao(args, ator)` com `ator` no último parâmetro); lint/CI checa que toda função pública com `ator` chama `policy.check`; operações sem ator são proibidas em código de produto (jobs e workers usam `ator_sistema` explícito).

*Cache.* Memoization por request (mesma transação, mesma resposta) — zero invalidação, custo zero. Cache cross-request só se profiling justificar, não na V1. Funções de relação são consultas SQL simples sobre tabelas indexadas; otimização é responsabilidade do dono do bounded context.

*Modo de falha.* Fail closed por default (erro inesperado → 403 + log de severidade alta). Fail explicit para classe de erros transientes detectáveis (timeout, connection pool exausto, banco indisponível → 503 com header de retry). Erro de input (recurso não existe, parâmetro inválido) → 422 antes da auth. Taxonomia de erros com métricas separadas por categoria, mesma família da taxonomia de erros da fronteira IA (§22.3.5).

*Auditoria de decisões.* Toda operação de write registra (permit e deny). Reads sensíveis (audit log próprio, dados de outro vereador) registram. Reads de dado público não registram. Granularidade: ator (identidade + vínculo), ação, recurso (tipo + id), decisão, razão (qual cláusula da política decidiu), `ente_id`, timestamp. Audit log de auth segue retenção do audit log de produto (Invariante 10), particionado por `ente_id` + `created_at` desde V1.

*Testabilidade.* Cobertura obrigatória por operação autorizada: caminho feliz, denial por papel, denial por relação dinâmica, denial por estado do recurso, denial cross-tenant. CI bloqueia merge se cobertura cair abaixo do threshold. Mudança em política de autorização exige PR explícito com diff revisado — política como dado central torna mudanças visíveis. Ambiente de teste com identidades sintéticas em entes sintéticos (já decidido em §22.2).

**Eixo F — MFA e step-up para operações sensíveis.**

*MFA por tipo de vínculo.* **WebAuthn/passkey é o fator primário recomendado para servidor/vereador** (phishing-resistant por amarração ao origin, multifator num gesto); senha + TOTP permanece como piso obrigatoriamente disponível para quem não usa passkey. WebAuthn com hardware key física obrigatório para admin interno (sem passkey sincronizado), TOTP como backup com justificativa. SMS não aceito por default (SIM swap). **E-mail código de uso único** é canal de **bootstrap de primeiro acesso (enrollment) e de recuperação** — **nunca fator standing de login** (posse de inbox é fator único e phishável; substitui a antiga "senha temporária one-time"). Enrollment imediato no primeiro acesso: código de uso único enviado ao e-mail institucional inicia a sessão e **obriga** a configurar passkey (ou TOTP como piso) antes de qualquer outra ação; admin do ente não pode desabilitar MFA — política é global.

*Step-up para operações sensíveis.* Reautenticação imediata antes de ação específica. Operações com step-up: assinar artefato legal (ICP-Brasil), aprovar e publicar ata revisada (TOTP/WebAuthn), encerrar votação (TOTP/WebAuthn), editar proposição já protocolada em fluxo de correção auditada, cassar/encerrar mandato manualmente, mudar configuração do ente, ler audit log de outro usuário, ações de admin interno cross-ente (sempre, com hardware key). Operações comuns de tramitação (votar, comentar internamente, redigir parecer) **não** exigem step-up — fluxo de sessão precisa ser fluido.

Janela de step-up: default 5 minutos para servidor/vereador, configurável até 15 minutos por ente; admin interno 1 minuto, não configurável. Step-up é evento explícito (`StepUpRealizado`) que entra no audit log e pode ser checado por `policy.check` via cláusula `requer_step_up_recente_em: <categoria>, janela_max_min: N`.

*Posição estrita da ICP-Brasil.* Reservada exclusivamente para assinatura digital de artefato legal com valor de não-repúdio. Validação em duas camadas: (a) certificado válido na cadeia ICP-Brasil contra raiz oficial; (b) **CPF do certificado bate com CPF da identidade da sessão atual**. Sem essa segunda checagem, ICP-Brasil de outra pessoa "logada como" o vereador certo passaria. Assinatura armazenada como artefato persistente (tabela `assinatura_digital`) vinculado ao recurso, com timestamp confiável. Distinção autenticação ↔ assinatura é arquitetural — modelagem separada de sessão de auth.

Razões para não usar ICP-Brasil em autenticação cotidiana: atrito mata adoção (token USB no celular não funciona; app mobile não suporta A3); gerenciamento de certificado é responsabilidade da pessoa, vira incidente operacional nosso; MFA com TOTP+senha já é forte para autenticação. Razões para não usar como step-up genérico: TOTP/WebAuthn fazem step-up bem, baratos e ergonômicos; ICP-Brasil só faz sentido quando a saída é assinatura juridicamente válida; reservar para assinatura mantém o ato deliberado e excepcional.

Caso especial — ata da sessão: ata revisada e publicada exige assinatura ICP-Brasil do secretário e/ou presidente. **Aprovar e publicar** exige step-up TOTP/WebAuthn. **Assinar** é o ato adicional com ICP-Brasil. Dois passos distintos, registrados separadamente.

*Recuperação de fator e bypass.* Reset de fator é sempre ato auditado, nunca autoatendido para servidor/vereador/admin. Servidor perde TOTP → admin do ente reseta (audit). Vereador perde TOTP → admin do ente reseta com notificação a 2º responsável quando configurado. Servidor/vereador com poder elevado → reset exige aprovação de outro humano (modelado via política de autorização sobre operação `resetar_mfa(sujeito)` no padrão dual). Admin interno perde hardware key → outro admin faz revogação + emissão de nova key sob two-person rule. Reset de emergência em véspera de sessão crítica é fluxo explícito, com auditoria pós-fato. Cidadão recupera via gov.br (fora do nosso fluxo).

*Detecção de risco e step-up adaptativo.* Fora da V1 (modelo de risco real exige baseline de comportamento que só temos depois de meses operando; falsos positivos altos sem baseline). Adicionado em V1.5 ou V2. O que entra na V1 mesmo sem detecção adaptativa: notificação por e-mail a cada novo login de dispositivo desconhecido (default ligado para perfis de poder elevado, ativável por usuário); histórico de sessões visível para o próprio usuário ("ver minhas sessões ativas" e "encerrar sessões").

**Eixo G — Eventos de autenticação e autorização.**

*Taxonomia em quatro classes* (refinamento do Invariante 7 para o domínio de auth):

*Classe 1 — Domain events no bus interno* (Invariante 2): mudanças de estado de mandato/vínculo/papel. `MandatoIniciado`, `MandatoLicenciado`, `MandatoEncerrado`, `MandatoCassado`, `CargoMesaAssumido`, `CargoMesaEncerrado`, `MembroComissaoAdicionado`, `MembroComissaoRemovido`, `VinculoCriado`, `VinculoEncerrado`, `VinculoSuspenso`, `IdentidadeExternaVinculada`, `IdentidadeExternaDesvinculada`, `TokensRevogadosPorIdentidade`. Persistidos como auditoria, propagam para outros bounded contexts, candidatos naturais a webhook na V2.

*Classe 2 — Audit log de produto* (Invariante 10): atos auditáveis expostos ao cliente via UI. `LoginRealizado`, `LoginFalhou` (agregado em janela), `LogoutRealizado`, `SessaoExpirada`, `StepUpRealizado`, `StepUpFalhou`, `MFAEnrollmentRealizado`, `MFAFatorAdicionado`, `MFAFatorRemovido`, `MFAResetadoPorAdmin` (com justificativa), `MFAResetEmergenciaAprovado` (com aprovador), `SenhaResetada`, `SenhaTrocadaPeloUsuario`, `PoliticaAuthAlteradaNoEnte`, `AssinaturaDigitalRealizada` (vinculada ao recurso), `AssinaturaDigitalFalhou`, `AcessoNegadoPorPolitica` (denials são sinal forte), `AcessoSensivelRealizado`. Append-only, retenção regulatória.

*Classe 3 — Logs de aplicação:* erros, exceções, traces. Não são domain events nem audit. Retenção curta (30-90 dias). Sistema de logs, não audit log.

*Classe 4 — Métricas:* contadores agregados, ~13 meses (Invariante 7). Métrica nunca duplica audit (contar logins é métrica; registrar cada login é audit).

Disciplina anti-confusão: audit log nunca recebe log de aplicação; log de aplicação nunca recebe domain event; métrica nunca duplica audit. Reforço explícito porque auth é o lugar onde mais se erra essa separação.

*Retenção por classe* (defaults sugeridos com pisos legais; tunables conforme §22.5.3):

| Classe | Default | Configurável |
|---|---|---|
| Domain events de mandato/vínculo/papel | Permanente | Não — fato histórico |
| `LoginRealizado`/`LogoutRealizado`/`SessaoExpirada` | 18 meses | Por ente, mín. 12, máx. 36 meses |
| `LoginFalhou` (agregado) | 6 meses | Tunable global |
| `StepUpRealizado`/`StepUpFalhou` | 5 anos | Não configurável |
| `MFAResetado*`/`SenhaResetada` | 5 anos | Não configurável |
| `AssinaturaDigitalRealizada` | Permanente | Não — vinculada ao recurso |
| `AcessoNegadoPorPolitica` | 2 anos | Por ente, dentro de limites |
| `AcessoSensivelRealizado` | 5 anos | Não configurável (LGPD) |
| `PoliticaAuthAlteradaNoEnte` | Permanente | Não |

Para `LoginFalhou`: modelo de agregação por janela — registro individual nas primeiras N falhas em janela curta, depois agregação por (identidade_tentada, motivo, hora). Evita explosão de volume sem perder sinal de detecção.

Pisos legais refletem entendimento prático LAI/LGPD/auditoria, não parecer jurídico. Revisão pelo jurídico especializado em direito digital + administrativo é deferida; estrutura permite ajuste sem refactor.

*Direito ao apagamento (LGPD).* Política diferenciada por classe:

- *Não-apagáveis:* atos legais assinados (proposições, pareceres, atas, votações), eventos de mandato/cargo/Mesa, audit log de operações de servidor/vereador no exercício da função pública, assinaturas digitais. Justificativa: LGPD admite exceção para cumprimento de obrigação legal/regulatória, exercício regular de direitos, e interesse público sobre exercício de mandato.
- *Apagáveis sob solicitação:* audit log de cidadão (logins, acessos, consultas), comentários de cidadão (substituídos por placeholder "[comentário removido]" preservando integridade de thread), pedidos de e-SIC após prazo legal cumprido.
- *Pseudonimizáveis:* audit log de servidor/vereador para eventos não-funcionais (login pessoal, consulta a dados próprios) — após N anos, identidade substituída por hash estável; evento mantido para análise agregada.

Mecânica: evento `ApagamentoSolicitado(sujeito_id, escopo, base_legal)` (já mencionado em §22.3.4) dispara processo orquestrado. Apagamento nunca é DELETE silencioso — é ato registrado com escopo declarado e base legal documentada; auditoria do apagamento sobrevive ao apagado. Resposta ao titular tem prazo legal e é registrada (`PedidoApagamentoRespondido`).

Caso especial vereador ex-vereador: atos públicos (proposições, votações, falas) não-apagáveis (interesse público); dados pessoais não-funcionais (telefone, endereço, e-mail pessoal) apagáveis. Distinção dado funcional público ↔ dado pessoal acessório clara no modelo.

*Consentimentos.* Entidade `consentimento(identidade_id, finalidade, base_legal, concedido_em, revogado_em, versao_termo)`. Pontos de coleta: vinculação inicial gov.br (aceite de termo do ente), ativação de notificação opcional, comentário público em proposição. Termos versionados — mudança gera nova versão; consentimento antigo permanece atrelado à versão antiga. Revogação é evento auditado (`ConsentimentoRevogado`); tratamentos com base "consentimento" cessam em revogação, tratamentos com base "obrigação legal" não dependem.

Servidor/vereador no exercício da função: base legal é "execução de contrato/exercício regular de função pública", **não** consentimento. Coletar consentimento aqui seria erro conceitual com efeito ruim (sugere que pode revogar e parar o tratamento, o que não é verdade).

*Eventos de auth não atravessam a fronteira para a Plataforma de IA.* Plataforma de IA opera como `ator_sistema` próprio quando processa transcrição/ata/etc. Atravessamento humano só apareceria em V2.5+ com chatbot cidadão consultando seus próprios dados — e mesmo aí seria via propagação de contexto da request, não via evento de auth.

## 22.5.3 Disciplinas arquiteturais derivadas

Padrões que aparecem repetidamente nos sete eixos e viram regra geral, não decisão por feature:

**1. Identidade ↔ Vínculo separados.** Identidade ancorada em CPF é supratenant (não tem `ente_id`). Vínculo é a relação da identidade com um Ente (servidor/vereador/admin) ou Município (cidadão). Mesmo CPF pode ter múltiplos vínculos. Provedor de identidade externa vincula a `identidade`, não a `vinculo` — uma identidade pode ter múltiplos vínculos com o mesmo CPF gov.br. Audit limpo: query por `identidade_id` atravessa todos os vínculos; query por `ente_id` em cada vínculo isola escopo.

**2. Papéis estáticos centrados em pessoa em tabela; papéis contextuais a recurso no recurso.** Papel estático ("vereador", "servidor_protocolo") em `usuario_papel`. Papel contextual ("relator deste parecer", "membro desta comissão") como coluna FK ou tabela de junção no bounded context dono, exposto como função de relação. Não há tabela genérica única de papel com contexto polimórfico.

**3. Motor de autorização compartilhado, política como dado por bounded context.** `policy.check` é mecânica transversal (motor, código compartilhado). A política declarativa de cada ação mora no bounded context dono do recurso. Política reusa a DSL pequena compartilhada com tramitação (§22.4 eixo C) e compliance (Invariante 4) — disciplina arrastada de §22.4.3 disciplina 5, agora estendida para auth.

**4. Mandato como entidade com cascata de papéis temporais.** Mandato vive em entidade própria com estado. Cascata via `tem_mandato_vigente`: licença ou cassação derruba todos os papéis temporais em uma operação, sem desativar individualmente. Atribuição dos papéis permanece registrada (audit histórico); apenas o exercício é suspenso. Suplente que assume mandato titular não herda automaticamente cargos/membership do titular licenciado.

**5. Toda função de relação aceita `instante` como parâmetro.** Default `now()`; consulta histórica usa instante específico. "Quem era presidente da Mesa em 12/03/2024?" sempre tem resposta consultando dados dessa data, não dados de hoje. Append-only com mutação controlada permite isso sem refactor.

**6. Distinção autenticação ↔ assinatura é arquitetural.** Sessão de auth é efêmera, vinculada ao ator. Assinatura digital é artefato persistente, vinculada ao recurso. Modelagem separada (`assinatura_digital` é tabela própria, não derivação de sessão). ICP-Brasil é mecanismo de assinatura, não de autenticação; cada assinatura é ato deliberado com hash do conteúdo específico, não derivado da sessão.

**7. Tunables operacionais como configuração, não como código.** Todo parâmetro operacional sensível ao contexto (TTL, retenção, threshold, janela, limiar) é configurável, com defaults sugeridos funcionando como ponto de partida. Configuração tem escopo (global, por tipo de vínculo, por ente). Limites globais protegem contra valores inseguros (cliente pode reduzir TTL, não aumentar além do teto). Mudanças de configuração são atos auditáveis. Configuração de tunable é dado, não release — bate com Invariante 4. Default seguro: se configuração corromper ou faltar, sistema usa default; nunca falha aberto. **Propriedade da config segue schema-por-módulo (§22.10):** cada módulo é dono dos próprios tunables de tenant (`identidade` dos de auth — TTL/janela de step-up/retenção; `compliance` das suas janelas; etc.); atributos estruturais cross-cutting do ente (branding, contatos, canais/flags habilitados) ficam em `cadastros` (dono do cadastro do Ente). **Não há tabela central de config-do-ente** — a "config do ente" é visão agregada pela área de UI `admin_ente` via HTTP, nunca JOIN cross-schema.

**8. Defesa em profundidade na avaliação de autorização.** Camada externa (middleware) faz checagens grossas independentes do recurso. Camada interna (in-domain) faz checagem fina por operação com recurso carregado. Cada camada faz o que é boa em fazer. RLS no banco (§22.2) é defesa final, não defesa primária.

**9. Taxonomia de eventos de auth em quatro classes.** Domain events no bus, audit log de produto, logs de aplicação, métricas. Cada classe tem destino, retenção e público próprios. Disciplina anti-confusão: audit nunca recebe log de aplicação; log de aplicação nunca recebe domain event; métrica nunca duplica audit. Auth é o lugar onde mais se erra essa separação — reforço obrigatório.

**10. Apagamento LGPD é política diferenciada, nunca DELETE silencioso.** Política por classe de evento decide se apaga, pseudonimiza, ou recusa com motivo. Apagamento é ato auditado com escopo e base legal. Auditoria do apagamento sobrevive ao apagado.

## 22.5.4 Decisões deferidas e pontos a confirmar

Decisões concretas de implementação deferidas para outros chats:

**Para o chat de stack/infra:** provedor de IdP concreto (Cognito vs. Keycloak vs. Auth0 vs. próprio); biblioteca/framework para WebAuthn server-side; biblioteca/SDK para validação de cadeia ICP-Brasil; mecanismo concreto de revogação imediata de tokens (lista negra in-memory + Redis vs. JWT com expiração curta + revogação por TTL); particionamento concreto do audit log; mecanismo de agregação de `LoginFalhou` (worker + janela vs. roll-up periódico).

**Para revisão jurídica (após contratação de jurídico especializado em direito digital + administrativo):** revisão dos pisos legais de retenção em §22.5.2 eixo G; texto canônico dos termos de consentimento por finalidade; política concreta de anonimização vs. pseudonimização para casos limítrofes; tratamento de dados de menores em audiência pública e participação cidadã.

**Para validação com pesquisa qualitativa antes do lançamento:** taxa real de adoção de gov.br entre cidadãos engajados em política municipal no Nordeste (premissa do eixo A); apetite real de servidores/vereadores por WebAuthn vs. TOTP em diferentes faixas de letramento digital; calibração das janelas de step-up (5 min default funciona, ou frusta?); calibração do modelo de "comentário/manifestação anônima" como configuração por ente.

**Para V1.5/V2:** detecção de risco e step-up adaptativo; modelo concreto de baseline de comportamento; painel de risco para admin do ente; capacidade de exigir nível mínimo gov.br por fluxo (arquitetura suporta, exposição diferida); SSO com AD/LDAP municipal; federação com outros IdPs.

**Para confirmar com especialista em regimento (após contratação):** ordem regimental de substituição da presidência da Mesa é universal o bastante para template padrão, ou variável por câmara? Reset de fator de vereador em véspera de sessão crítica precisa de fluxo regimental específico ou basta o fluxo de emergência genérico? Aprovação dual para reset de poder elevado deve seguir hierarquia regimental (presidente da Mesa aprova reset de secretário-geral, etc.) ou pode ser configurada livremente pelo ente?
