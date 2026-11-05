# Contratos de eventos (AsyncAPI)

Responsable: **bloque C**. Estado: **BORRADOR v1.0.0, pendiente de revisión por el equipo.**

Este directorio es usado para los 13 eventos de dominio de UrbanMove (`AGENTS.md`, secciones 3.2 y 3.3). Ningún servicio ni Lambda debe publicar o consumir un evento sin que su contrato esté aquí.

## Estructura

```
asyncapi/
├── asyncapi.yaml                 # Documento AsyncAPI 3.0: canal, operaciones y mensajes
├── schemas/
│   ├── envelope.schema.json      # Envelope común obligatorio para TODOS los eventos
│   ├── common.schema.json        # Tipos compartidos: ids, vehicleType, 9 estados, money, location…
│   ├── payloads/<Evento>.payload.schema.json   # Solo el payload de cada evento
│   └── messages/<Evento>.message.schema.json   # Envelope + payload: lo que se valida de verdad
└── examples/<Evento>.json        # Un evento completo y válido de cada tipo
```

Los esquemas usan JSON Schema 2020-12 y se referencian entre sí con rutas relativas, así que el directorio funciona igual en local, en CI y dentro de una Lambda.

## Convenciones

- **Envelope** (todos los campos obligatorios, no se admiten otros):
  `eventId` (UUID v4, clave de idempotencia), `timestamp` (UTC ISO-8601, cuándo ocurrió), `source` (`urbanmove.<productor>`), `eventType`, `schemaVersion` (semver, p. ej. `"1.0.0"`), `correlationId` (UUID v4 del flujo) y `payload`.
- **`source` está fijado por evento**: un evento solo puede publicarlo su productor oficial. Por ejemplo, un `VehicleReserved` con `source: urbanmove.rides` es inválido.
- **Payloads estrictos** (`additionalProperties: false`): añadir un campo es un cambio de contrato.
- **Identificadores** con prefijo: `veh-`, `usr-`, `res-`, `ride-`, `mnt-`, `rel-`. Las zonas usan slugs (`tafira`, `mesa-y-lopez`…), cuyo catálogo es del servicio `zones` (bloque B).
- **Dinero** siempre como `{"amount": 4.88, "currency": "EUR"}`.
- Los ejemplos de `VehicleReserved`, `RideStarted` y `RideFinished` comparten `correlationId`: es el flujo completo de una usuaria, útil para probar la trazabilidad.

## Catálogo

Todos los eventos llegan a `analytics-ingestion` (Data Lake). El resto de consumidores tiene su propia cola SQS con DLQ.

| Evento | Productor | Consumidores | Para qué |
|---|---|---|---|
| `UserRegistered` | `urbanmove.identity` | profiles, notifications, analytics-ingestion | Alta de usuario; profiles crea el perfil a partir de este evento. |
| `VehiclePurchased` | `urbanmove.acquisitions` | inventory, availability, telemetry, history, analytics-ingestion | Incorporación de un vehículo nuevo; inventory lo registra como activo. |
| `VehicleReserved` | `urbanmove.reservations` | availability, history, notifications, analytics-ingestion | Reserva creada sobre un vehículo disponible; availability lo pasa a RESERVED. |
| `ReservationCancelled` | `urbanmove.reservations` | availability, history, notifications, analytics-ingestion | Reserva cancelada por el usuario; el vehículo vuelve a AVAILABLE. |
| `ReservationExpired` | `urbanmove.reservations` | availability, history, notifications, analytics-ingestion | Reserva liberada por timeout. La Lambda reservation-expiration la dispara; ver decisión abierta 1. |
| `RideStarted` | `urbanmove.rides` | availability, telemetry, history, analytics-ingestion | Inicio de uso; availability pasa el vehículo a IN_USE. |
| `RideFinished` | `urbanmove.rides` | availability, telemetry, history, notifications, analytics-ingestion | Fin del viaje; availability libera el vehículo en la zona de destino. |
| `VehicleBatteryLow` | `urbanmove.telemetry` | maintenance-detection, availability, history, analytics-ingestion | Batería por debajo del umbral; la Lambda maintenance-detection crea la orden. |
| `VehicleWithdrawnForMaintenance` | `urbanmove.maintenance` | availability, history, notifications, analytics-ingestion | Vehículo retirado de la calle; availability lo pasa a WITHDRAWN. |
| `VehicleReturnedToService` | `urbanmove.maintenance` | availability, telemetry, history, analytics-ingestion | Vehículo listo y de vuelta en la calle; telemetry reinicia contadores de uso. |
| `DemandHotspotDetected` | `urbanmove.relocation-engine` | relocation, notifications, analytics-ingestion | La Lambda relocation-engine detecta déficit con dm_demand y propone origen y cantidad. |
| `RelocationOrderCreated` | `urbanmove.relocation` | notifications, history, analytics-ingestion | El servicio relocation crea la orden a partir del hotspot. |
| `RelocationOrderCompleted` | `urbanmove.relocation` | availability, history, notifications, analytics-ingestion | Orden completada; availability actualiza la zona de los vehículos movidos. |

## Cómo validar

```bash
cd tools/contract-validator
pip install -r requirements.txt
python -m pytest -q
```

Para validar el documento AsyncAPI con la herramienta oficial:

```bash
npx @asyncapi/cli validate docs/contracts/asyncapi/asyncapi.yaml
```

## Cómo cambiar un contrato

1. Abrir un PR que modifique el esquema **y** su ejemplo.
2. Subir `schemaVersion`: cambio compatible (campo opcional nuevo) → versión menor; cambio incompatible (campo obligatorio nuevo, renombrar, borrar) → versión mayor.
3. Lo aprueban el productor y todos los consumidores afectados (`AGENTS.md`, sección 9).
4. Los tests de `tools/contract-validator` deben pasar en CI.

## Decisiones abiertas (para revisar en grupo)

1. **¿Quién publica `ReservationExpired`?** Propuesta: la Lambda `reservation-expiration` se despierta con EventBridge, pide al servicio `reservations` que expire las reservas caducadas, y es `reservations` quien publica el evento. Así ninguna Lambda escribe en la base de datos de otro servicio (ADR-001). El `AGENTS.md` sugiere que la emite la Lambda: el bloque A decide.
2. **Transiciones sin evento en el catálogo.** El enunciado solo define 13 eventos, así que no hay evento para `CHARGING`, `REPAIRING`, `READY_FOR_SERVICE`, `RETIRED`, la confirmación de una reserva ni las incidencias. Propuesta: estas transiciones se gestionan dentro de `maintenance`/`inventory` vía REST, y el tiempo fuera de servicio (MTTR) se calcula con `VehicleWithdrawnForMaintenance` → `VehicleReturnedToService`. Si hacen falta para el historial o los data marts, se amplía el catálogo con aprobación del grupo.
3. **Un topic SNS o varios.** Propuesta: un único topic `urbanmove-domain-events` con filtros por `eventType` en cada suscripción SQS. Es más sencillo en Terraform. Lo decide el bloque D en el ADR-002.
4. **Datos personales en el Data Lake.** `UserRegistered` incluye email y nombre. Propuesta: la capa *trusted* los seudonimiza (hash) antes de pasar a *curated*.
