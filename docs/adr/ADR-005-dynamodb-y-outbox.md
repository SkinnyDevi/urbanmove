# ADR-005 · DynamoDB como base de datos y publicación de eventos con bandeja de salida (outbox)


## Contexto

Cada servicio tiene su propia base de datos (ADR-001). En local usábamos PostgreSQL, pero en AWS Academy eso obliga a mantener una instancia de RDS encendida, que consume presupuesto todo el tiempo y complica el `terraform destroy` de cada sesión.

Además, un servicio que guarda un cambio y publica un evento tiene un problema clásico: si guarda y el bus (SNS) falla antes de publicar, el evento se pierde; si publica y luego falla al guardar, se anuncia un cambio que no existe.

## Decisión

1. **DynamoDB** como base de datos de los servicios (empezando por `maintenance`), con **una tabla por servicio** en modo *on-demand* (`PAY_PER_REQUEST`): sin coste cuando no se usa y sin servidores que gestionar.
2. **Tabla única por servicio** con varios tipos de elemento (`pk` con prefijo: `ORDER#`, `VEHICLE#…#ACTIVE`, `IDEMPOTENCY#`, `OUTBOX#`) y un índice secundario disperso `gsi1` para las consultas por estado y para los eventos pendientes.
3. **Escrituras condicionales en una transacción** (`TransactWriteItems`, práctica 6):
   - `attribute_not_exists(pk)` en el candado del vehículo → como mucho una orden activa por vehículo, también con peticiones simultáneas;
   - `attribute_not_exists(pk)` en la `Idempotency-Key` → una petición repetida no se aplica dos veces (`AGENTS.md` 5.5);
   - `rowVersion = :expected` en la orden → bloqueo optimista.
4. **Bandeja de salida (outbox)**: los eventos se guardan en la **misma transacción** que el cambio. Después se publican en SNS y se borran. Si SNS falla, un proceso periódico (cada 5 s) los reintenta.

`relocation` usa el mismo diseño. Su candado es `ROUTE#<zonaDestino>#<tipo>#ACTIVE`: como mucho una orden activa por zona destino y tipo de vehículo, para que varios `DemandHotspotDetected` seguidos no creen órdenes repetidas. La idempotencia del hotspot usa su `eventId` como clave. El índice `gsi1` ordena las órdenes por `dueAt` (plazo), así la bandeja del técnico muestra primero las más urgentes.

En el lado consumidor (`availability`) se aplica la misma idea: la marca `PROCESSED#eventId` se guarda en la **misma transacción** que el cambio del vehículo, con `attribute_not_exists`. Así un evento repetido no se aplica dos veces aunque llegue a la vez a dos réplicas.

## Consecuencias

A favor:

- **No se pierde ningún evento** aunque SNS o el propio servicio fallen entre guardar y publicar. Está probado en `MaintenanceApiIntegrationTest.anEventThatCouldNotBePublishedIsRelayedLater`.
- Los consumidores reciben cada evento **al menos una vez**. Ya era así con SQS, así que no añade obligaciones nuevas: los consumidores deben ser idempotentes con el `eventId`.
- Sin coste fijo en AWS Academy y sin RDS en Terraform: la tabla es un recurso `aws_dynamodb_table` que se destruye al instante.
- Todo se prueba en local con LocalStack 4.14.0 (DynamoDB, SNS y SQS) y con Testcontainers.

En contra:

- Las consultas hay que diseñarlas de antemano (índices). Filtros arbitrarios o informes complejos no encajan: esos van al Data Lake (bloque C).
- Las lecturas del índice son eventualmente consistentes: una orden recién creada puede tardar un instante en aparecer en el listado. Las lecturas por id son consistentes.
- El orden de los eventos no está garantizado entre distintas órdenes (SNS estándar). Dentro de una orden, el bloqueo optimista impide escribir dos cambios a la vez.

## Alternativas descartadas

- **PostgreSQL en RDS**: más flexible para consultar, pero con coste fijo por hora y una instancia que gestionar. Se mantiene en local para `zones` hasta que el equipo decida migrarlo.
- **Publicar directamente en SNS después de guardar** (sin outbox): más simple, pero un fallo entre las dos operaciones pierde el evento.
