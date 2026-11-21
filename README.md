# UrbanMove

> Autores: Félix M.V., Mohamed O.H.Z., Cristina S.M., Joel S.A.

Sistema distribuido de movilidad urbana: flota de bicicletas y patinetes eléctricos repartida por zonas de Las Palmas de Gran Canaria. Proyecto final de **Computación en la Nube** (ULPGC).

Incluye reservas y viajes de usuarios, tareas de mantenimiento y recolocación de la flota, un bus de eventos, un Data Lake en S3 con 7 data marts y la generación automática de órdenes de recolocación a partir de la demanda.

Las reglas de arquitectura y de código están en [`AGENTS.md`](AGENTS.md).

## Estructura

| Carpeta | Contenido |
|---|---|
| [`services/`](services/README.md) | 15 microservicios Java (Javalin). `zones` es la plantilla que copian los demás. |
| `lambdas/` | 5 funciones AWS Lambda en Java. |
| `apps/` | App de usuario, app de mantenimiento y dashboard operativo. |
| [`tools/`](tools/README.md) | Validador de contratos, generador de datos sintéticos, visor de mapa, simulador de telemetría, Locust. |
| `infra/terraform/` | Infraestructura como código para LocalStack (*dev*) y AWS Academy (*prod*). |
| `infra/local/` | Scripts del entorno local (creación de las bases de datos). |
| `docs/` | Contratos, arquitectura, ADR y data marts (ver abajo). |

## Pila

- **Java 21** con **Javalin 7**, JDBC + PostgreSQL + Flyway, Jackson, Nimbus JOSE (JWT), Logback. Sin framework de inyección ([ADR-003](docs/adr/ADR-003-pila-java-javalin.md)).
- **Una base de datos por servicio** (ADR-001).
- **Eventos**: SNS + SQS con DLQ (ADR-002), con el envelope de [`docs/contracts/asyncapi`](docs/contracts/asyncapi/README.md).
- **Local**: Docker Compose con PostgreSQL y **LocalStack 4.14.0**.
- **AWS Academy**: ECR + ECS Fargate, Lambda, S3, SNS/SQS, EventBridge y CloudWatch, desplegados con Terraform y `LabRole`.

## Arrancar en local

Necesitas Docker y Java 21 (IntelliJ trae Maven).

```bash
cp .env.example .env            # rellena las contraseñas y JWT_SECRET (nunca se sube al repo)
docker compose up -d --build
docker compose ps               # todos los servicios deben acabar en "healthy"
curl localhost:8083/health      # servicio zones
```

Tests:

```bash
cd services && mvn test         # microservicios (los de integración necesitan Docker)
cd tools && mvn test            # contratos, generador
```

## Documentación

| Documento | Qué explica |
|---|---|
| [`docs/contracts/openapi/`](docs/contracts/openapi/README.md) | Contratos REST (OpenAPI 3.1) |
| [`docs/contracts/asyncapi/`](docs/contracts/asyncapi/README.md) | Los 13 eventos de dominio y su envelope |
| [`docs/architecture/vehicle-state-machine.md`](docs/architecture/vehicle-state-machine.md) | Estados del vehículo: qué evento provoca cada cambio y qué servicio lo valida |
| [`docs/adr/`](docs/adr) | Decisiones de arquitectura |
| [`docs/practicas-aws.md`](docs/practicas-aws.md) | Qué práctica de clase se usa en cada parte del proyecto |

## Reparto por bloques

| Bloque | Ámbito |
|---|---|
| A | Usuarios y transacciones: identity, profiles, reservations, rides, incidents, Lambda de expiración, app de usuario |
| B | Flota y operaciones: inventory, availability, zones, telemetry, acquisitions, maintenance, relocation, Lambda de detección de mantenimiento, app de mantenimiento |
| C | Datos y analítica: history, analytics, Data Lake, 7 data marts, Lambdas de ingesta, data marts y recolocación |
| D | Plataforma: notifications, Terraform, ECR/ECS, CI/CD, CloudWatch, dashboard |
