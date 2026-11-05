# contract-validator

Valida eventos de UrbanMove contra los contratos de `docs/contracts/asyncapi/`.

Se usa en tres sitios:

- **Tests de contrato** (`mvn test`, y en el CI): comprueban que los 13 eventos tienen esquema y ejemplo, que los ejemplos son válidos y que se rechazan los eventos mal formados.
- **Generador sintético**: valida cada evento que genera.
- **Lambda `analytics-ingestion`**: decidirá qué eventos pasan de la capa *raw* a la *trusted* del Data Lake.

## Cómo funciona

Revisa cada evento en dos pasos, como un funcionario que revisa una carta:

1. **El sobre** (`schemas/envelope.schema.json`): los 7 campos comunes y que `eventType` sea uno de los 13.
2. **La carta**: si el sobre está bien, lee el `eventType` y comprueba el mensaje completo con `schemas/messages/<Evento>.message.schema.json`. Eso incluye el payload y que el `source` sea el productor oficial.

Devuelve una lista de errores con la forma `ruta: mensaje`, por ejemplo `payload/batteryLevel: must have a maximum value of 100`. Lista vacía = evento válido.

Por seguridad, solo lee ficheros de dentro de la carpeta de contratos.

## Desde código Java

```java
ContractValidator validator = ContractValidator.fromDefaultLocation();   // encuentra docs/contracts/asyncapi solo
List<String> errors = validator.validate(event);   // event: JsonNode o String con el JSON
if (errors.isEmpty()) { /* válido */ }
```

Crea una sola instancia y reutilízala: cargar los esquemas cuesta, validar es rápido. Se puede usar desde varios hilos.

## Desde la terminal

Después de `mvn package` (desde la raíz del repo):

```bash
java -jar tools/contract-validator/target/contract-validator-1.0.0-cli.jar docs/contracts/asyncapi/examples
java -jar tools/contract-validator/target/contract-validator-1.0.0-cli.jar tools/synthetic-generator/out/events.jsonl
```

Acepta ficheros `.json` (un evento o una lista), ficheros `.jsonl` (un evento por línea) y carpetas enteras. Termina con código 0 si todo es válido y 1 si hay algún evento inválido.

## Tests

`ContractValidatorTest` tiene 38 tests:

- que el catálogo tiene exactamente los 13 eventos oficiales;
- que cada evento tiene esquema de payload, esquema de mensaje y ejemplo (13 tests);
- que `asyncapi.yaml` lista los mismos eventos y que todos llegan al Data Lake;
- que los 13 ejemplos son válidos (13 tests);
- que se rechazan: sin `correlationId`, tipo inventado, productor equivocado, campo de más, batería al 150 %, UUID inválido, fecha inválida y tipo de vehículo inventado;
- que se acepta un viaje sin reserva previa (`reservationId: null`);
- que la herramienta de terminal cuenta bien los válidos e inválidos de un `.jsonl`.

## Dónde busca los contratos

Sube desde la carpeta actual hasta encontrar `docs/contracts/asyncapi`, así que funciona igual desde la raíz, desde `tools/` o desde IntelliJ. Se puede forzar otra ruta con la variable de entorno `URBANMOVE_CONTRACTS_DIR`.
