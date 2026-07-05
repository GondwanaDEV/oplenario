# Imagem do frontend (Next.js 16). §22.9 Eixo 5: Docker em todo deploy local e de teste — nenhum
# `npm run dev` solto no host. O stage `dev` roda com o código bind-mounted pelo compose (hot reload);
# `runner` é a imagem de produção (standalone, sem node_modules).

FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# ---- dev: next dev dentro do container; source vem do bind-mount do docker-compose ----
FROM deps AS dev
WORKDIR /app
EXPOSE 3000
CMD ["npx", "next", "dev", "--hostname", "0.0.0.0"]

# ---- build: gera o output standalone ----
FROM deps AS build
WORKDIR /app
COPY . .
# BACKEND_URL entra no routes-manifest do rewrite em build time (next.config.ts le no load do config).
ARG BACKEND_URL=http://app:8888
ENV BACKEND_URL=$BACKEND_URL
RUN npm run build

# ---- runner: so o standalone + estaticos, sem node_modules/devDeps ----
FROM node:22-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/public ./public
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
EXPOSE 3000
CMD ["node", "server.js"]
