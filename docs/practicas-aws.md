# De las prácticas de clase a UrbanMove

Qué enseña cada práctica de la asignatura y dónde se usa en el proyecto. Sirve para saber qué práctica repasar antes de tocar cada parte y para justificar las decisiones en la defensa.

Las prácticas usan Python y Boto3. En UrbanMove los servicios son Java, así que lo equivalente es el **AWS SDK for Java v2** (mismas operaciones, mismos nombres: `PutItem`, `Publish`, `ReceiveMessage`…).

## Resumen

| Práctica | Tema | Dónde aparece en UrbanMove | Bloque |
|---|---|---|---|
| 1 | AWS CLI, credenciales temporales, perfiles | Despliegue en AWS Academy: perfil `cn-lab`, región `us-east-1` | Todos |
| 3 | VPC, subred pública, IGW, rutas, Security Groups, EC2, `/health`, systemd | Red de `infra/terraform`; `GET /health` de cada servicio | D (red), todos (`/health`) |
| 4 | LocalStack + Terraform, entornos *dev* y *prod* | `docker-compose.yml` (LocalStack 4.14.0) e `infra/terraform` | D |
| 5 | S3, versionado, ciclo de vida, Boto3 | Data Lake `raw` / `trusted` / `curated` | C |
| 6 | DynamoDB, escrituras condicionales, lotes, GSI | Idempotencia (`processed_events`), anti doble reserva, posible almacén de telemetría | A, B, C |
| 7 | Docker, ECR, introducción a ECS/Fargate | `Dockerfile` de cada servicio → ECR → ECS Fargate | D (pipeline), todos (su imagen) |
| 8 | Mensajería: SNS + SQS + DLQ | Bus de eventos de dominio (ADR-002) | C (contratos), D (Terraform), todos (publicar y consumir) |

## Práctica 1 · AWS CLI y credenciales

- Las credenciales de AWS Academy son **temporales** (incluyen `aws_session_token`) y caducan al terminar el laboratorio. Hay que copiarlas de nuevo en cada sesión.
- Se guardan en `~/.aws/credentials`, **nunca en el repositorio** (`AGENTS.md` 8.5). En el proyecto usamos el perfil `cn-lab` para AWS real, como en la práctica 4.
- Región única: `us-east-1`.
- Comprobación rápida antes de desplegar: `aws sts get-caller-identity --profile cn-lab`.

## Práctica 3 · Red y servicio web en EC2

Lo que se reutiliza en el proyecto:

- **VPC + subred pública + Internet Gateway + tabla de rutas `0.0.0.0/0 → IGW`.** Es la red donde irán las tareas de ECS Fargate. En AWS Academy **no se usa NAT Gateway** (consume presupuesto, `AGENTS.md` 8.2): las tareas van en subred pública con `assign_public_ip = true`.
- **Security Groups** con el mínimo de puertos abiertos. Lección de la práctica: aunque el servicio funcione, si falta la regla del puerto o la ruta al IGW no es accesible.
- **`GET /health`** que devuelve el estado en JSON: es el mismo patrón que tienen todos nuestros servicios (`AGENTS.md` 6.3).
- **systemd con `Restart=on-failure`** es la idea que en contenedores hacen Docker (`restart: unless-stopped` + `HEALTHCHECK`) y ECS (reinicia las tareas cuyo `/health` falla).

## Práctica 4 · LocalStack y Terraform

Es el modelo de trabajo del proyecto:

- **dev = LocalStack**, **prod = AWS Academy**. La misma infraestructura en Terraform; solo cambia el `provider`:
  - dev: credenciales `test`, `endpoints { ... = "http://localhost:4566" }` y las opciones `skip_...`;
  - prod: `profile = "cn-lab"`, `region = "us-east-1"`.
- Cada entorno tiene **su propio estado** (`terraform.tfstate`). No se copia el estado de dev a prod. `*.tfstate` y `.terraform/` están en `.gitignore`.
- Ciclo: `terraform init` → `plan` (revisar) → `apply` → `destroy` al terminar cada sesión (`AGENTS.md` 8.4).
- **LocalStack 4.14.0**, la versión fijada en clase: las versiones nuevas piden cuenta. `docker-compose.yml` usa esa misma versión.
- Proveedor de AWS de clase: `hashicorp/aws` **5.100.0**. Conviene usar la misma versión en `infra/terraform`.
- Diferencias con la práctica que hay que respetar en AWS Academy: no se pueden crear roles IAM; ECS y Lambda usan el `LabRole` existente (`data "aws_iam_role" "lab_role"`).

## Práctica 5 · S3

Base del Data Lake (bloque C):

- Los "directorios" son **prefijos**: `raw/year=YYYY/month=MM/day=DD/source=<servicio>/` (`AGENTS.md` 7.1) no requiere crear carpetas.
- **Ciclo de vida**: buena opción para expirar la capa `raw` antigua y no acumular datos.
- **Versionado** y limpieza: un bucket versionado no se vacía con `aws s3 rm --recursive`; hay que borrar todas las versiones. En Terraform se resuelve con `force_destroy = true` (`AGENTS.md` 8.4).
- **URL prefirmada**: útil si el dashboard necesita descargar un fichero privado del Data Lake sin credenciales.

## Práctica 6 · DynamoDB

Lo más aprovechable para el proyecto son las **escrituras condicionales**:

- `attribute_not_exists(clave)` hace que una escritura solo ocurra si la clave no existe. Es exactamente el **consumidor idempotente** (`AGENTS.md` 5.5): guardar el `eventId` en `processed_events` con esa condición; si falla con `ConditionalCheckFailedException`, el evento ya se procesó → se registra "Duplicate event ignored" y se hace ACK.
- Una condición sobre el estado (`#status = :available`) en la misma escritura evita la **doble reserva** sin bloqueos: si dos peticiones llegan a la vez, solo una cumple la condición.
- `PutItem` **reemplaza** el elemento entero si la clave ya existe; para cambiar solo algunos atributos se usa `UpdateItem`.
- **GSI disperso**: solo indexa los elementos que tienen el atributo. Útil, por ejemplo, para "vehículos con batería baja".
- Las lecturas de un GSI son **eventualmente consistentes**.

Candidato claro en UrbanMove: **telemetry** (muchas escrituras pequeñas por vehículo y tiempo; clave `vehicleId` + `timestamp`). Los servicios con relaciones y transacciones (reservas, mantenimiento) siguen mejor en PostgreSQL. Usar DynamoDB o PostgreSQL es decisión de cada servicio siempre que la base de datos sea **solo suya** (ADR-001).

## Práctica 7 · Docker y ECR

Flujo de publicación de cada servicio:

```bash
# 1. Construir la imagen (desde services/, porque necesita el pom padre)
docker build -f zones/Dockerfile -t urbanmove-zones services/

# 2. Autenticarse en ECR
aws ecr get-login-password --region us-east-1 --profile cn-lab | \
  docker login --username AWS --password-stdin <ID-CUENTA>.dkr.ecr.us-east-1.amazonaws.com

# 3. Etiquetar y subir
docker tag urbanmove-zones <ID-CUENTA>.dkr.ecr.us-east-1.amazonaws.com/urbanmove-zones:<etiqueta>
docker push <ID-CUENTA>.dkr.ecr.us-east-1.amazonaws.com/urbanmove-zones:<etiqueta>
```

- En el proyecto conviene etiquetar con el **hash del commit**, además de `latest`: así cada despliegue sabe qué versión exacta ejecuta y el *rollback* es volver a la etiqueta anterior.
- Nuestros `Dockerfile` son de **dos etapas** (compilar con Maven y ejecutar con solo el JRE), con usuario sin privilegios y `HEALTHCHECK`. La imagen de la práctica es más sencilla porque Python no se compila.
- El cierre de la práctica (ECS + Fargate) es justo donde se ejecutan nuestros servicios: tareas con `cpu = 256` y `memory = 512` (`AGENTS.md` 8.3).

## Práctica 8 · Mensajería con SNS, SQS y DLQ

Ejemplo de clase: un servicio de pedidos que, al recibir un pedido, debe avisar a cocina, reparto y notificaciones.

```
                                    ┌─► SQS cocina ─────────► servicio cocina
usuario ─HTTP─► pedidos ─► SNS ─────┼─► SQS reparto ────────► servicio reparto
           (síncrono)     (tópico)  └─► SQS notificaciones ─► servicio notificaciones
                                          │ (tras 3 fallos)
                                          └─► DLQ (mensajes que no se pudieron procesar)
```

- **Síncrono**: el usuario hace una petición HTTP y espera la respuesta (crear el pedido).
- **Asíncrono**: el resto se entera por mensajes, sin que pedidos espere ni sepa quién escucha.
- **SNS (tópico)** reparte una copia de cada mensaje a todas las colas suscritas (*fan-out*).
- **SQS (cola)** guarda los mensajes hasta que el consumidor los pide. Si el consumidor está caído, los mensajes esperan; no se pierden.
- **DLQ**: si un mensaje falla varias veces (`maxReceiveCount = 3` en el proyecto), pasa a la cola de mensajes caídos para revisarlo y reprocesarlo.

En UrbanMove es **el mismo patrón** con nuestros eventos. Por ejemplo, en el flujo de mantenimiento:

```
técnico ─HTTP─► maintenance ─► SNS urbanmove-domain-events
                (retirar)             │  VehicleWithdrawnForMaintenance
                                      ├─► SQS availability-queue ─► availability (vehículo → WITHDRAWN)
                                      ├─► SQS history-queue ──────► history
                                      ├─► SQS notifications-queue ► notifications
                                      └─► SQS analytics-queue ────► Lambda analytics-ingestion (Data Lake)
                                            cada cola con su DLQ
```

Por qué importa para el proyecto:

- **SQS entrega "al menos una vez"**: un mensaje puede llegar repetido. Todo consumidor debe ser idempotente (práctica 6).
- **Tolerancia a fallos**: si `history` se cae, sus eventos esperan en su cola y se procesan al volver (escenario de fallo 1). Si un evento falla siempre, acaba en la DLQ y se reprocesa (escenario de fallo 2).
- Un único tópico con **filtros por `eventType`** en cada suscripción (decisión abierta 3 de `docs/contracts/asyncapi/README.md`, a cerrar en el ADR-002 del bloque D).

## Checklist de cada sesión en AWS Academy

1. Iniciar el laboratorio y copiar las credenciales nuevas al perfil `cn-lab`.
2. `aws sts get-caller-identity --profile cn-lab`.
3. `terraform plan` y revisar antes de `apply`.
4. Probar y recoger evidencias (capturas, logs de CloudWatch).
5. `terraform destroy` y comprobar en la consola que no queda nada.
6. *End Lab*.
