FROM nginx:1.27-alpine

RUN apk add --no-cache openssl bash

COPY nginx.conf /etc/nginx/nginx.conf
COPY certs/generate-cert.sh /usr/local/bin/generate-cert.sh
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/generate-cert.sh /usr/local/bin/docker-entrypoint.sh

ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
