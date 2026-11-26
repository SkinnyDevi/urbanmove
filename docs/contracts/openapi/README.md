# Contratos REST (OpenAPI 3.1)

Responsable transversal: **bloque A**.

Ningún servicio puede crear o cambiar un endpoint sin que su contrato esté aquí (`AGENTS.md` 3.1).

## Ficheros

| Fichero | Servicio | Bloque | Puerto local |
|---|---|---|---|
| `common.yaml` | Componentes comunes: JWT, `X-Correlation-Id`, `Idempotency-Key`, errores RFC 7807, `/health` | todos | — |
| `inventory.yaml` | inventory | B | 8081 |
| `availability.yaml` | availability | B | 8082 |
| `zones.yaml` | zones | B | 8083 |
| `maintenance.yaml` | maintenance | B | 8084 |
| `relocation.yaml` | relocation | B | 8085 |

Faltan los de los bloques A (identity, profiles, reservations, rides, incidents), C (history, analytics) y D (notifications). Cada uno debe seguir las mismas reglas; los tests lo comprueban solos en cuanto se añade el fichero.

## Reglas que cumplen todos

- **`GET /health`** sin autenticación: `200` si el servicio y sus dependencias responden, `503` si no (`AGENTS.md` 6.3).
- **JWT** obligatorio en todo lo demás (`bearerJwt`). Los roles permitidos de cada operación están en su `description`.
- **`X-Correlation-Id`** opcional en la petición y devuelto siempre en la respuesta (`AGENTS.md` 6.1).
- **Errores** en `application/problem+json` (RFC 7807) con `correlationId` obligatorio. Una transición de estado ilegal devuelve `409` con `type: /problems/invalid-state-transition`, `currentState` y `action`. Las acciones también pueden devolver `409` por una edición simultánea (`/problems/concurrent-update`) o por reutilizar una `Idempotency-Key` (`/problems/idempotency-key-reused`).
- **`Idempotency-Key`** obligatoria en todos los `POST` (`AGENTS.md` 5.5).
- **Mismos tipos que los eventos.** `vehicleId`, `zoneId`, `vehicleType`, `money`… se referencian desde `../asyncapi/schemas/common.schema.json`. Un formato se cambia en un solo sitio. Excepción: los campos que pueden ser `null` se escriben como `type: [string, 'null']` con su `pattern`, porque `oneOf: [$ref, null]` no se resuelve bien al validar.
- **Acciones como `POST /recurso/{id}/accion`.** En `maintenance` y `relocation`, cada acción del técnico es una operación que aplica una transición de la máquina de estados (`docs/architecture/vehicle-state-machine.md`). Así el contrato refleja el dominio y no se puede escribir un estado arbitrario.

## Cómo validar

```bash
cd tools
mvn -pl contract-validator test
```

`OpenApiContractsTest` lee cada fichero con swagger-parser, resuelve todas las referencias y comprueba las reglas de arriba.

## Decisiones abiertas

1. **Doble reserva** (con el bloque A). availability solo es de lectura por REST. Si reservations debe bloquear el vehículo de forma síncrona, se añadirá una operación en `availability.yaml` con bloqueo optimista.
2. **Identidad de las Lambdas.** `maintenance-detection` llama a `POST /maintenance-orders`. Necesita un JWT de servicio; el formato lo decide el bloque A junto con el de usuario. **Provisional:** la Lambda firma con `JWT_SECRET` un token `sub = svc-maintenance-detection`, `roles = [admin]` (ver `docs/architecture/maintenance-detection.md`).
3. **Puertos.** Los de la tabla son una propuesta para el `docker-compose.yml`.
