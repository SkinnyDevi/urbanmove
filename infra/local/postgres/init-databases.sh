#!/bin/bash
# Crea una base de datos y un usuario por servicio (ADR-001: base de datos por servicio).
# Cada usuario solo puede entrar en su propia base de datos.
#
# Lo ejecuta PostgreSQL la primera vez que arranca (carpeta /docker-entrypoint-initdb.d).
# Si cambias esta lista con el volumen ya creado:  docker compose down -v  y vuelve a levantar.
#
# Para añadir un servicio: añade su nombre a SERVICE_DATABASES en docker-compose.yml
# y define <NOMBRE>_DB_PASSWORD en .env (p. ej. AVAILABILITY_DB_PASSWORD).
set -euo pipefail

for service in ${SERVICE_DATABASES}; do
  var="${service^^}_DB_PASSWORD"
  password="${!var:-}"
  if [ -z "$password" ]; then
    echo "Falta la variable $var para la base de datos de $service" >&2
    exit 1
  fi
  echo "Creando base de datos y usuario: $service"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
       -v svc="$service" -v pwd="$password" <<'SQL'
CREATE ROLE :"svc" LOGIN PASSWORD :'pwd';
CREATE DATABASE :"svc" OWNER :"svc";
REVOKE ALL ON DATABASE :"svc" FROM PUBLIC;
SQL
done
