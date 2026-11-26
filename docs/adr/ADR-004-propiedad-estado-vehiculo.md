# ADR-004 · Quién es dueño de cada estado del vehículo

- **Detalle:** [`docs/architecture/vehicle-state-machine.md`](../architecture/vehicle-state-machine.md)

## Contexto

El enunciado define 9 estados del vehículo (`AGENTS.md` 3.4) y pide documentar qué evento provoca cada cambio y qué servicio lo valida. El catálogo de eventos es cerrado (13 eventos) y **no tiene ningún evento** para entrar en `CHARGING`, `REPAIRING` ni `READY_FOR_SERVICE`. Además, cada servicio tiene su propia base de datos (ADR-001), así que no puede existir una tabla de estados compartida.

## Decisión

Cada parte del ciclo de vida tiene un único dueño:

| Estados | Dueño | Cómo cambian |
|---|---|---|
| `AVAILABLE`, `RESERVED`, `IN_USE`, `LOW_BATTERY`, `WITHDRAWN` | **availability** | Al consumir eventos del catálogo (`VehicleReserved`, `RideStarted`, `VehicleWithdrawnForMaintenance`…) |
| `CHARGING`, `REPAIRING`, `READY_FOR_SERVICE` | **maintenance**, como estados de la orden de mantenimiento | Por las acciones del técnico en la app (`POST /maintenance-orders/{id}/...`) |
| `RETIRED` | **inventory** (baja administrativa del activo) | Por `POST /vehicles/{id}/retire`, que publica `VehicleRetired` (ADR-006) |

Para el resto del sistema, un vehículo en el taller está `WITHDRAWN` hasta que llega `VehicleReturnedToService`.

Ambas máquinas de estados se implementan con el **patrón State** (`AGENTS.md` 5.3): `VehicleState` en availability y `MaintenanceOrderState` en maintenance. Una transición ilegal lanza `InvalidStateTransitionError` y, por HTTP, devuelve `409`.

## Consecuencias

- Se usan los 9 estados oficiales **sin inventar eventos** fuera del catálogo.
- Cada servicio valida solo sus transiciones, en su propia base de datos.
- Los estados del taller no viajan por el bus: `history` y los data marts ven la retirada y la vuelta (`VehicleWithdrawnForMaintenance` → `VehicleReturnedToService`), con el trabajo realizado y el tiempo fuera de servicio, que bastan para el MTTR de `dm_maintenance`.
- `dm_fleet` no puede contar por separado los vehículos `CHARGING` y `REPAIRING` a partir de los eventos. Si hace falta, el dashboard los consulta a maintenance o se amplía el catálogo con aprobación de todos.
- `RETIRED` se comunica con el evento 14, `VehicleRetired`: availability y los data marts se enteran por el bus. Ver [ADR-006](ADR-006-evento-vehicle-retired.md), pendiente de aprobación del equipo.
