# ADR-006 · Evento 14: `VehicleRetired`

- **Estado:** propuesto por el bloque B, **pendiente de aprobación del equipo** (sobre todo del bloque C, responsable de los contratos de eventos).
- **Cierra:** la decisión abierta 1 de [`docs/architecture/vehicle-state-machine.md`](../architecture/vehicle-state-machine.md) y la consecuencia pendiente de [ADR-004](ADR-004-propiedad-estado-vehiculo.md).

## Contexto

`AGENTS.md` (3.3) define un catálogo cerrado de 13 eventos. Ninguno sirve para la **baja definitiva** de un vehículo (`RETIRED`, uno de los 9 estados oficiales). Sin un evento:

- availability seguiría teniendo el vehículo como `WITHDRAWN` para siempre y nunca sabría que ya no existe;
- `dm_fleet` no podría contar los `RETIRED` por zona y tipo, como pide la sección 7.2;
- history no tendría el final del ciclo de vida del vehículo.

Se valoraron tres opciones:

| Opción | Problema |
|---|---|
| **A. Evento nuevo `VehicleRetired`** | Amplía un catálogo cerrado: hace falta que lo apruebe todo el equipo. |
| B. Reutilizar `VehicleWithdrawnForMaintenance` con motivo "baja" | Mezcla una retirada temporal con una definitiva y confunde a history y a `dm_maintenance` (el MTTR saldría mal). |
| C. Baja solo dentro de inventory, sin evento | availability seguiría ofreciendo el vehículo: es un fallo real del sistema. |

## Decisión

Se añade **`VehicleRetired`** como evento 14 (opción A):

- **Productor:** `urbanmove.inventory`, al aceptar `POST /vehicles/{vehicleId}/retire` (solo rol `admin`).
- **Consumidores:** availability (pasa el vehículo a `RETIRED` y no lo vuelve a ofrecer), history y analytics-ingestion.
- **Payload:** `vehicleId`, `vehicleType` y `reason`. El instante de la baja es el `timestamp` del envelope.
- **Contrato:** `docs/contracts/asyncapi/schemas/{payloads,messages}/VehicleRetired.*.schema.json` y `examples/VehicleRetired.json`, añadido a `asyncapi.yaml` y al enum `eventType` del envelope.

Para saber si un vehículo está retirado de la calle (condición de la baja), inventory **no llama a availability por HTTP**. Escucha `VehicleWithdrawnForMaintenance` y `VehicleReturnedToService` y guarda su propia vista (`serviceStatus`: `IN_SERVICE`, `WITHDRAWN` o `RETIRED`). Así se mantienen el desacoplamiento y la base de datos por servicio (ADR-001).

## Consecuencias

- Los 9 estados oficiales tienen todos un camino real en el sistema.
- `dm_fleet` puede contar los `RETIRED` a partir de los eventos del Data Lake.
- availability acepta `VehicleRetired` solo desde `WITHDRAWN`. En cualquier otro estado lo rechaza (`REJECTED`) y lo confirma, igual que el resto de eventos ilegales.
- **Pendiente para el equipo:** actualizar `AGENTS.md` (secciones 1.1 y 3.3, "13 eventos" → "14") cuando se apruebe. No se ha cambiado aquí porque es el documento común de todos los bloques.
- Si el equipo rechaza la propuesta, basta con quitar el evento del contrato y la llamada a publicar en inventory. La baja seguiría existiendo dentro de inventory, pero availability no se enteraría (opción C).
