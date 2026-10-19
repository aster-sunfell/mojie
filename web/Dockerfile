FROM node:22-bookworm-slim AS build
WORKDIR /app/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
COPY docs/user-guide/ /app/docs/user-guide/
RUN npm run build

FROM nginx:1.28-alpine
COPY deploy/nginx.prod.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/web/dist /usr/share/nginx/html
EXPOSE 80
