ARG NGINX_IMAGE=nginx:1.30.6-alpine
FROM ${NGINX_IMAGE}

COPY nginx.conf /etc/nginx/nginx.conf

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1/gateway-healthz || exit 1
