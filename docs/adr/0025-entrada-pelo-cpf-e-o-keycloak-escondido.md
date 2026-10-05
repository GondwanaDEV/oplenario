# ADR-0025 — Entrada pelo CPF e o Keycloak escondido atrás do O Plenário

- **Status:** ✅ **Aceita** (05/10/2026). Pedido direto: a tela de login do Keycloak ("Sign in to your account",
  "ENTE-10000000-…", um UUID no campo de usuário) não pode aparecer; "o Keycloak continua, mas precisa ser abstraído";
  "é mais prático o usuário usar CPF e senha e logar automaticamente no seu ente". Escolhidas as **duas etapas no
  padrão gov.br** (CPF → senha), não uma tela única (ver *Alternativas*).
- **Relacionadas:** `arquitetura/22-5-auth.md` (passwordless-first, MFA obrigatório), Onda D fatias 1 e 2
  (`docs/superpowers/specs/2026-07-12-onda-d-slice{1,2}-*`: realm por Casa, PKCE com custódia no BFF, o `[GAP]` de
  entrada a frio), ADR-0015 (gov.br do cidadão), ADR-0016 (o realm do operador, que esta ADR não toca), ADR-0018
  (Casa encerrada).

## Contexto

O login de servidor e vereador era: link da Câmara (`/entrar/<uuid>`) → botão "Entrar" → **a tela padrão do Keycloak**,
em inglês, com o nome técnico do realm no título e o `identidade-id` como usuário. Três problemas, um deles grave:

1. **A tela era do Keycloak.** Realm criado só com `{:realm :enabled}`: sem nome, sem idioma, sem tema.
2. **Sem o UUID da Câmara não havia como entrar.** `/entrar` era um placeholder ("use o link da sua Câmara") — o
   `[GAP]` de entrada a frio da Onda D, fatia 2.
3. **Um convidado de verdade não conseguia entrar.** Conferido contra o Keycloak 26.0.0: o usuário nascia devendo
   só a passkey (`webauthn-register-passwordless`), sem senha, e o realm usava o fluxo padrão (`browser`), que pede
   usuário **e senha** e não tem passo de passkey. Depois do convite a pessoa tinha passkey e nenhuma senha. As
   personas da demo entravam porque o seed define a senha delas e apaga a pendência.

## Decisão

### 1. Duas etapas, a primeira no O Plenário

- **`/entrar` pede o CPF** (`FormularioCpf`, HTML puro, POST). `/entrar/<uuid>` (o link que a Câmara divulga) pede o
  CPF do mesmo jeito, com a Câmara fixa.
- **`POST /api/auth/entrar` (BFF)** pergunta ao backend em quais Câmaras a pessoa tem acesso e:
  - uma Câmara (ou a do link) → 303 direto ao `authorize` do realm dela com **`login_hint` = o identidade-id** (o
    usuário dela no realm);
  - mais de uma → `/entrar/escolher`, com a lista num cookie httpOnly de 5 min (`entrar_escolha`); a escolha leva o
    mesmo `login_hint`. **Cada Câmara tem a sua senha** (um realm por Câmara) e a tela diz isso;
  - nenhuma, CPF que não confere, limite de tentativas, backend fora → volta com `?erro=` e a frase certa.
- **`POST /auth/localizar` (backend, público):** CPF **no corpo** (nunca em URL — URL vai para log de acesso), só
  dígitos, dígito verificador conferido antes de qualquer consulta. Devolve `{casas: [{ente-id, nome-oficial,
  nome-curto}], login-hint}`; sem Câmara, `{casas: []}` **sem hint**. A Casa encerrada (ou inexistente) fica fora
  (`casa-para-login`, injetada pelo host). A consulta ao banco roda mesmo sem identidade (o tempo de resposta não
  conta se o CPF existe).
- **A pergunta que atravessa as Casas é uma função estreita do banco:**
  `identidade.casas_com_acesso_institucional(identidade)` → os `ente_id` com vínculo `servidor`, `vereador` ou
  `admin_ente` **ativo**. O vínculo continua sob FORCE RLS para todo o resto (o pool, fora da Casa, segue vendo zero
  linhas — testado). A função é SECURITY DEFINER do dono das tabelas, e só o dono ganha a política de leitura
  irrestrita — o dono já manda nas tabelas, então nada se abre a quem não podia ler. Funciona com dono superuser e
  com dono restrito (Postgres gerenciado; `encerramento_test` migra assim). EXECUTE só do `oplenario_id_resolver`.
- **O CPF não vai ao Keycloak.** O usuário no realm continua sendo o `identidade-id` (o código já evitava levar CPF
  ao Keycloak, `identidade/db/identidade.clj`); o backend traduz.

### 2. O Keycloak com a cara do O Plenário (tema `oplenario`)

- **`apps/keycloak/temas/oplenario/`**: tema FreeMarker herdando do `base` (HTML sem estilo). Reescritas só a moldura
  (`template.ftl`: o cartão, a faixa de azulejo, a Câmara à frente, "Plataforma O Plenário" no rodapé — o arquétipo
  `produto/design-system/o-plenario/telas/login.html`), a senha (`login.ftl`), o código (`login-otp.ftl`) e a
  página de informação (`info.ftl`: o começo e o fim do convite). Toda outra página do Keycloak entra na moldura.
  Tokens, fontes (Mona Sans, Geist Mono) e claro/escuro são os do front.
- **A senha com o usuário escondido:** com `login_hint`, o campo de usuário vira `hidden` e a pessoa só digita a
  senha ("Não é você? Entrar com outro CPF" leva de volta). Sem hint (alguém abriu o Keycloak direto), o campo
  aparece como "E-mail institucional" com o atalho para o CPF. **Os ids `username`, `password` e `kc-login` não
  mudam**: os workflows de homolog preenchem esses campos.
- **Sem o gov.br nesta tela:** é a tela do servidor e do vereador. O cidadão vai do portal direto ao gov.br
  (`kc_idp_hint`, ADR-0015).
- **Tema de e-mail**: o convite sai como "Câmara X: seu acesso ao O Plenário está liberado", com os passos em
  português corrido.
- **Por que FreeMarker e não Keycloakify** (a recomendação inicial na conversa): o Keycloakify pede Node + Maven no
  build da imagem e um projeto React a mais para ~5 telas pequenas. O tema é um diretório copiado para a imagem, sem
  build; as páginas não sobrescritas herdam do Keycloak e já saem com o visual.

### 3. O realm da Casa (provisionamento, `keycloak_idp.clj`)

Convergido a cada `provisionar-realm!` (idempotente; os realms existentes convergem pelo "Reaplicar configuração de
login" do console do operador):

- `displayName` = **nome oficial da Casa** (`provisionar-realm!` ganhou a aridade `[idp ente-id {:nome}]`; sem nome,
  o realm existente guarda o que tinha e o novo nasce "O Plenário");
- **pt-BR** como único idioma; `loginTheme`/`emailTheme` = `KEYCLOAK_TEMA_LOGIN` (padrão `oplenario`; vazio
  grava `""`, o padrão do Keycloak, e reaplicar desfaz o tema). Keycloak sem o tema instalado cai no padrão com um erro
  no log dele — ligar não derruba nada;
- **política de senha:** 8 a 128 caracteres, diferente do usuário e do e-mail (NIST 800-63B: comprimento, sem regra
  de composição);
- **trava contra força bruta:** a cada 10 erros, 1 minuto, dobrando até 15; **nunca permanente** — senão quem
  soubesse o CPF de um vereador o trancaria fora no dia da sessão (quem já está dentro não cai: a sessão do O Plenário
  não depende do Keycloak);
- o client `oplenario-web` ganha `baseUrl` = `<origem>/entrar`: o "voltar" do fim do convite leva à entrada pelo CPF.

### 4. O primeiro acesso: senha + código (não mais só a passkey)

- `criar-usuario!` e `convidar!` pedem **`UPDATE_PASSWORD` + `CONFIGURE_TOTP`**, nesta ordem (o provisionamento
  põe a senha antes do código; o padrão do Keycloak pedia o código antes de a pessoa ter senha).
- O login da Casa fica **CPF → senha → código do aplicativo** (o OTP condicional do fluxo padrão: pedido de quem tem
  um). É o "senha + TOTP como piso" da §22.5, e o segundo fator continua obrigatório.
- `resetar-mfa!` agora devolve a pendência `CONFIGURE_TOTP`: sem o fator, o próximo login pede o cadastro — antes a
  pessoa passaria a entrar só com a senha. Quem nunca teve senha (o convite antigo, só passkey) ganha também
  `UPDATE_PASSWORD`, senão ficaria sem como entrar.
- **A passkey sai do primeiro acesso, por ora.** Ela fica presa ao domínio do Keycloak (o *RP ID*); o domínio
  definitivo do produto ainda está pendente (`docs/04-nome-e-marca.md`), e trocar o domínio depois invalidaria todas.
  A required action continua habilitada no realm; a passkey volta como segundo fator (ou primário, §22.5) quando o
  domínio existir. **Isto adia o "passwordless-first" da §22.5 — confirmar com o Daouda.**

### 5. Limite de tentativas

O backend não tinha nenhum. `oplenario.limite-de-taxa`: janela deslizante por chave, em memória da instância (hoje
uma), uma função pura trocada por `swap!`, com a memória varrida no máximo a cada décimo da janela; a tentativa
recusada não conta. Na entrada pelo CPF: **30 consultas por IP a cada 5 min** (`:entrada`; `ENTRADA_LIMITE_POR_IP`,
`ENTRADA_JANELA_MIN`) → 429 com `Retry-After`. Uma Câmara inteira costuma sair por um IP só; o teto cobre a chegada da
equipe.

**De onde vem o IP:** o BFF lê o **último** item do `X-Forwarded-For` — o que o proxy da borda (o Traefik, que fala
direto com o Next) acrescentou; os anteriores o cliente escreve o que quiser — e repassa só ele ao backend.
`/api/auth/localizar` responde 404 no próprio Next: o proxy de `/api/:path*` não leva mais o navegador direto ao
`POST /auth/localizar` com um `X-Forwarded-For` inventado. (A auditoria continua lendo o primeiro item; ela registra,
não limita.)

## Alternativas descartadas

- **Formulário de CPF e senha no próprio app (ROPC / Direct Access Grant):** a senha passaria pelo BFF, não há
  passkey nem ações do primeiro acesso por esse caminho, e o RFC 9700 manda não usar. O client segue com
  `directAccessGrantsEnabled false`.
- **Uma tela só (CPF e senha juntos) com um realm único para todas as Casas:** desfaz o realm por Casa — a Casa da
  sessão vem do emissor do token (regra de segurança da Onda D), o gov.br e o apagamento (ADR-0018) são por realm.
  Fica registrada como o caminho se a tela única virar requisito.
- **Outro IdP com API de tela própria** (Zitadel, Ory): refazer a Onda D, o gov.br e o console do operador pelo ganho
  visual.

## Consequências

- **A descoberta "este CPF tem acesso a estas Câmaras"** fica exposta a quem souber o CPF. Quem é servidor ou vereador
  de qual Câmara é quase sempre público (portal de transparência); o que se protege é a varredura em massa — o limite
  por IP — e a senha, pela trava do Keycloak. O `login-hint` (o identidade-id) só sai junto com uma Câmara.
- **Uma pessoa em duas Câmaras tem duas senhas.** Custo do realm por Casa.
- **Senha esquecida** = novo convite do administrador da Câmara (o "esqueci a senha" do Keycloak fica desligado: ele
  depende do SMTP de produção, que é `[GAP]`). A tela diz isso.
- **Sem SMTP em produção não sai convite** — isso já era verdade e continua.
- **Quem souber o CPF de um vereador consegue travar a conta dele por alguns minutos** (10 senhas erradas → 1 min,
  dobrando até 15). A trava existe porque protege também o código do aplicativo (6 dígitos) de ser adivinhado; ela é
  temporária e não derruba quem já está dentro (a sessão do O Plenário não depende do Keycloak). Com o domínio
  definitivo e a passkey de volta, a senha deixa de ser a porta principal. Aceito por ora.
- **Conceder acesso em `/administracao` reaplica o realm** (o `provisionar-realm!` sem nome): mais 3 ou 4 chamadas ao
  Keycloak por concessão, e um realm criado por esse caminho (Casa antiga, sem passar pelo console) nasce "O Plenário"
  até alguém usar "Reaplicar configuração de login" no console, que grava o nome.
- **O limite é por instância e um deploy o zera.** Com mais de uma réplica do backend, trocar pelo Valkey (o mesmo
  `tentar!`).

## O que o operador faz (produção)

`docs/27-runbook-ia-producao.md`, seção 9: trocar o serviço Keycloak do Dokploy pela imagem de
`apps/keycloak/Dockerfile`, conferir `KEYCLOAK_TEMA_LOGIN`, e **"Reaplicar configuração de login"** de cada Casa no
console.

## Materialização

- **Backend:** `identidade/diplomat/http/auth_in.clj` (`POST /auth/localizar`), `identidade/db/identidade.clj`
  (`id-por-cpf`, `casas-com-acesso-institucional`), migration `20261005000240-identidade-casas-com-acesso`,
  `oplenario/limite_de_taxa.clj`, `rotas.clj` (`casa-para-login`, o limite), `kernel/components/keycloak_idp.clj`
  (aparência, defesa, ordem das ações, convite, reset de MFA), `admin_sistema/controllers.clj` (o nome da Casa no
  realm). Catálogo: `:identidade/localizar-casas` em `fora-do-catalogo.edn` (porta de entrada; o agente nunca entra
  por ela).
- **Front:** `app/(publico)/entrar/{page,formulario-cpf,escolher/page}.tsx`, `[ente]/page.tsx`,
  `app/api/auth/{entrar/route,pkce,login/route}.ts`, `lib/{cpf,entrada-cpf,entrar-erro}.ts`.
- **Keycloak:** `apps/keycloak/` (tema + `Dockerfile`); o `docker-compose` monta o tema no Keycloak de dev e do CI.
- **Testes:** função do banco com o role de runtime (`casas_com_acesso_test`), a rota (`localizar_http_test`), o
  limitador, o realm contra o Keycloak 26.0.0 real (`provisionamento_test`), o BFF e as telas no front. Conferido no
  navegador contra o Keycloak real: CPF → senha → página pedida; senha errada; CPF sem acesso e inválido; o link da
  Câmara; o caminho dos workflows de homolog; o convite pelo e-mail (Mailpit) até "Pronto"; o login seguinte pedindo
  o código; claro, escuro e 390 px.
- **Fica de fora, de propósito:** a página "Minha conta" do Keycloak (tema de conta — `/api/auth/conta` ainda leva
  ao console do Keycloak), o domínio próprio do Keycloak (depende do domínio do produto), a passkey (idem) e o limite
  compartilhado entre instâncias (Valkey).
