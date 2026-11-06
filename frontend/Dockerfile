FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
# Rendered to /etc/nginx/conf.d/default.conf at startup with environment variables substituted.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
ENV S3_PUBLIC_ORIGIN="" \
    STORAGE_UPSTREAM=http://seaweedfs:8333 \
    STORAGE_HOST=seaweedfs:8333 \
    DNS_RESOLVER=127.0.0.11
