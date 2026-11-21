# Microservicios Java de UrbanMove

Todos los servicios Java son módulos de este proyecto Maven (`services/pom.xml`), que fija las versiones en un solo sitio.

**Pila:** Java 21 · **Javalin 7** (HTTP) · JDBC con HikariCP y Flyway (PostgreSQL) · Jackson · Nimbus JOSE (JWT) · Logback.
Sin framework de inyección: cada servicio conecta sus piezas a mano en su clase `*Application` (inyección por constructor, `AGENTS.md` 4.2). Así se ve todo el grafo de objetos en un solo método y el arranque tarda menos de un segundo.

```bash
cd services
mvn test                 # todos los tests de todos los servicios (necesita Docker para los de integración)
mvn -pl zones test       # solo un servicio
```

## La plantilla: `zones`

`zones` es el servicio más sencillo y sirve de **modelo para todos los demás**. Cumple lo que exige el `AGENTS.md`:

| Requisito | Dónde está |
|---|---|
| Arquitectura hexagonal (4) | Paquetes `domain`, `application`, `infrastructure`, `interfaces` |
| Inyección por constructor, dominio sin dependencias técnicas (4.2) | `ZonesApplication.start`: único sitio donde se crean y conectan las piezas |
| `GET /health` (6.3) | `interfaces/http/HealthCheckHandler.java` + `infrastructure/health/` |
| Logs JSON con `correlationId` (6.1, 6.2) | `interfaces/http/ZonesHttpApi.java` (lo pone en el MDC) + `infrastructure/logging/JsonLogEncoder.java` + `logback.xml` |
| Errores RFC 7807 con `correlationId` (3.1) | `interfaces/http/ZonesHttpApi.java` (manejadores de excepciones) + `ApiProblem.java` |
| JWT con roles (3.1) | `interfaces/http/JwtVerifier.java` + `Role.java`; cada ruta declara sus roles al registrarse |
| Base de datos propia y migraciones (ADR-001) | `infrastructure/persistence/Database.java` + `src/main/resources/db/migration/` (Flyway) |
| Timeouts en llamadas de red (6.4) | `Database.TIMEOUT_SECONDS`: conexión, validación y consultas a 3 s |
| Sin secretos en el repo (8.5) | `infrastructure/ServiceConfig.java`: `DB_PASSWORD` y `JWT_SECRET` solo por variables de entorno, y nunca se imprimen |
| Tests unitarios, de contrato y de integración (9.4) | `src/test/java/.../unit`, `contract`, `integration` |

Los tres tipos de test:

- **unit**: dominio y casos de uso con un repositorio falso en memoria. Sin red ni Docker; tardan milisegundos.
- **integration**: arrancan el servicio completo contra un PostgreSQL real en Docker (Testcontainers) y le hacen peticiones HTTP: seguridad, errores, correlationId, semilla.
- **contract**: comprueban que las respuestas reales cumplen el `.yaml` de `docs/contracts/openapi/`. Si el código y el contrato dejan de coincidir, fallan.

## Crear un servicio nuevo a partir de la plantilla

1. **Contrato primero.** El `.yaml` del servicio tiene que existir en `docs/contracts/openapi/` (y los eventos en `asyncapi/`).
2. Copia `services/zones/` a `services/<servicio>/` y cambia `artifactId`, `finalName`, `mainClass`, el paquete `com.urbanmove.zones`, el nombre en `ServiceConfig` y `<service>` en `logback.xml`.
3. Añade `<module><servicio></module>` en `services/pom.xml`.
4. Sustituye el dominio, los casos de uso, el repositorio y los manejadores HTTP, y conéctalos en `*Application.start`. Lo transversal (`ZonesHttpApi` salvo las rutas, `ApiProblem`, `JwtVerifier`, `Role`, `HealthCheckHandler`, `JsonLogEncoder`, `Database`) se copia tal cual: es infraestructura técnica, sin lógica de negocio (`AGENTS.md` 4.1).
5. Cambia la migración `V1__...sql` por las tablas del servicio.
6. En `docker-compose.yml`: añade el servicio, su nombre a `SERVICE_DATABASES` y su `<NOMBRE>_DB_PASSWORD`; añade esa variable a `.env.example`.
7. Adapta el test de contrato al `.yaml` del servicio.

## Arrancar en local

Desde la raíz del repo:

```bash
cp .env.example .env     # rellena las contraseñas y JWT_SECRET
docker compose up -d --build
docker compose ps        # postgres, localstack y zones deben estar "healthy"
curl localhost:8083/health
```

Si ya tienes otro PostgreSQL en el puerto 5432, cambia `POSTGRES_PORT` en `.env`.

## Decisiones provisionales

- **Formato del JWT**: HS256 con secreto compartido y roles en el claim `roles`. Lo cierra el bloque A; si cambia, solo se toca el constructor de `JwtVerifier`.
- **Publicación de eventos**: zones no publica eventos, así que la plantilla todavía no incluye el publicador SNS con el envelope. Se añadirá con `maintenance`, que es el primer servicio que publica.
- **LocalStack 4.14.0**: la misma versión fijada en la práctica 4; las versiones 2026.x necesitan cuenta (`LOCALSTACK_AUTH_TOKEN`).
