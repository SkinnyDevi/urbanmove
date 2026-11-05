# Generador de datos sintéticos (bloque C)

Simula días de uso de UrbanMove en Las Palmas de Gran Canaria y produce los eventos que generarían los microservicios: reservas, viajes, baterías bajas, retiradas para mantenimiento… Todos cumplen los contratos de `docs/contracts/asyncapi/`.

## Para qué sirve

- **Probar el Data Lake, los data marts y el dashboard** antes de que existan los microservicios, sin hacer miles de reservas a mano.
- **Tener patrones realistas** (horas punta, zonas que se vacían) para que la Lambda de recolocación encuentre déficits de verdad.
- **Cargar datos iniciales** (`seed/`) en los servicios de zonas, inventario y usuarios.
- **Comprobar la analítica**: el generador apunta aparte la "verdad" (cuántos vehículos había en cada zona cada hora), para verificar que los data marts calculan lo mismo.

## Uso

**Desde IntelliJ:** abre `src/main/java/com/urbanmove/synthgen/GeneratorCli.java` y pulsa la flecha verde junto a `main`. Para pasar parámetros: **Run → Edit Configurations → Program arguments** (por ejemplo `--days 14 --seed 7`).

**Desde la terminal**, después de `mvn package` en `tools/`, y desde la raíz del repo:

```bash
java -jar tools/synthetic-generator/target/synthetic-generator.jar
java -jar tools/synthetic-generator/target/synthetic-generator.jar --days 14 --seed 7 --trips-per-day 900
```

Una semana tarda unos 4 segundos, validando cada evento contra los contratos.

| Parámetro | Por defecto | Qué hace |
|---|---|---|
| `--days` | 7 | Días a simular |
| `--start` | 2026-11-09 | Primer día (AAAA-MM-DD). El 9 de noviembre de 2026 es lunes |
| `--seed` | 42 | Semilla. **Misma semilla = exactamente los mismos datos** |
| `--vehicles` | 100 | Tamaño de la flota |
| `--users` | 200 | Usuarios iniciales |
| `--trips-per-day` | 700 | Peticiones de vehículo en un día laborable |
| `--out` | `tools/synthetic-generator/out` | Carpeta de salida |
| `--no-validate` | — | No validar contra los contratos |
| `--help` | — | Muestra la ayuda |

## Qué produce

```
out/
├── raw/year=2026/month=11/day=09/source=rides/events.jsonl   ← un fichero por día y servicio
├── events.jsonl                    ← todos los eventos juntos, en orden cronológico
├── seed/zones.json                 ← datos iniciales para los servicios
├── seed/vehicles.json
├── seed/users.json
├── ground_truth_zone_hours.csv     ← foto de cada zona al final de cada hora
└── summary.json                    ← resumen de lo generado
```

- **`raw/`** tiene exactamente las carpetas de la capa *raw* del Data Lake (`AGENTS.md`, 7.1). Se puede subir tal cual a S3 o LocalStack. Formato `.jsonl`: un evento JSON por línea.
- **`events.jsonl`** sirve para "reproducir" la simulación publicando los eventos en el bus SNS/SQS en orden.
- **`ground_truth_zone_hours.csv`** tiene una fila por zona y hora con los vehículos disponibles al final de la hora, las señales de demanda, las peticiones sin vehículo y si se cumple la regla de déficit del enunciado (`hotspot`). Es la referencia para comprobar el data mart de demanda y la Lambda de recolocación.
- **`summary.json`** contiene el resumen: eventos por tipo y día, viajes, reservas, mantenimiento, demanda no atendida y la lista de horas-zona con déficit.

La carpeta `out/` no se sube al repo (está en `tools/.gitignore`): se regenera idéntica con la misma semilla. Por seguridad, el generador **se niega a borrar una carpeta de salida que no haya creado él**.

## Cómo funciona por dentro

### 1. El mundo

- **8 zonas** de Las Palmas (`config/zones.json`), con coordenadas aproximadas, capacidad, popularidad y un tipo de perfil: campus, residencial, playa, comercial o casco histórico.
- **El día anterior al inicio** se dan de alta la flota (`VehiclePurchased`), 3 técnicos y los usuarios (`UserRegistered`). Los vehículos se reparten según la capacidad de cada zona, con batería entre el 60 % y el 100 %.
- **Durante la simulación** se registran unos 4 usuarios nuevos al día.

### 2. La agenda (el motor)

La simulación no espera en tiempo real. Usa una **agenda ordenada por tiempo** (`PriorityQueue`): cada entrada es "en el segundo T, haz esto". El motor saca siempre la más próxima, la ejecuta y, si provoca otras en el futuro, las apunta. Por ejemplo, "empieza un viaje" apunta "termina el viaje dentro de 22 minutos". Así se simula una semana en segundos.

### 3. La demanda

`config/demand_profiles.json` define para cada tipo de zona dos listas de 24 valores, uno por hora:

- **`departures`**: ganas de *coger* un vehículo allí. Las zonas residenciales tienen un pico a las 7-8.
- **`arrivals`**: ganas de *dejarlo* allí. El campus de Tafira tiene un pico de llegadas a las 7-9.

Además, `weekendFactor` cambia la demanda en fin de semana: el campus baja al 15 % y la playa sube al 160 %.

Con esas curvas se calcula cuántas personas querrán un vehículo en cada zona y hora. El número exacto varía al azar alrededor de la media (distribución de Poisson), como en la realidad.

Para que la flota no se acumule día tras día en unas zonas, el motor calcula un ajuste por zona que equilibra llegadas y salidas en un día completo (`balanceDailyFlows`). Los desequilibrios por horas se mantienen, y son los que provocan los déficits.

### 4. Qué pasa cuando alguien quiere un vehículo

1. Quiere un patinete (55 %) o una bici. Si no hay de su tipo, la mitad acepta el otro.
2. **Si no hay ninguno**, se apunta como demanda no atendida, sin evento: el catálogo no tiene uno para eso.
3. **Si hay**, el 70 % reserva primero (`VehicleReserved`) y el resto lo coge directamente (`RideStarted` con `reservationId: null`).
4. De las reservas, el 80 % acaba en viaje entre 1 y 10 minutos después, el 12 % se cancela y el 8 % caduca a los 15 minutos.
5. **El viaje**: el destino se elige según las curvas de llegada de esa hora. La distancia es la línea recta entre zonas por 1,3, y la duración sale de la distancia y la velocidad (bici 13 km/h, patinete 15 km/h). El coste es el desbloqueo más un precio por minuto.
6. Todo el flujo de una persona (reserva → inicio → fin) comparte el mismo **`correlationId`**.

### 5. Batería y mantenimiento

- Cada viaje gasta batería según la distancia; el patinete consume el doble que la bici.
- **Por debajo del 15 %** → `VehicleBatteryLow` → un técnico lo retira (`LOW_BATTERY`) → recarga de 2-4 h → `VehicleReturnedToService`.
- **Avería** (1 de cada 250 viajes) → retirada por `BREAKDOWN` → reparación de 6-24 h.
- **Cada 30 usos** → revisión obligatoria (`EXCESS_USAGE`, 1-2 h). Recargar no cuenta como revisión.
- **Los técnicos trabajan de 7:00 a 21:59.** Lo que ocurre de noche espera a la mañana, y eso alarga el tiempo fuera de servicio (útil para el data mart de mantenimiento).
- Cada flujo de mantenimiento tiene su propio `correlationId`.

### 6. La foto de cada hora

Al final de cada hora, el motor apunta para cada zona los vehículos `AVAILABLE` (lo que vería el servicio de disponibilidad a partir de los eventos), las señales de demanda (reservas + viajes sin reserva), las peticiones sin vehículo, y si se cumple la regla del enunciado: menos de 3 disponibles y más de 10 señales.

### 7. La semilla

Todo lo aleatorio, **incluidos los UUID**, sale de un único `java.util.Random` inicializado con la semilla. El algoritmo de `Random` está fijado por la especificación de Java, así que con la misma semilla y los mismos parámetros los datos son idénticos en cualquier ordenador. Todo el equipo puede trabajar con los mismos datos diciendo solo "semilla 42, 7 días".

## Qué NO genera, y por qué

`DemandHotspotDetected`, `RelocationOrderCreated` y `RelocationOrderCompleted` no se generan: los producen la Lambda `relocation-engine` y el servicio `relocation` a partir de estos datos. Si los inventara el generador, no habría nada que demostrar.

## Estructura del código

```
synthetic-generator/
├── pom.xml
├── config/
│   ├── zones.json              ← las 8 zonas
│   └── demand_profiles.json    ← curvas de demanda por tipo de zona
└── src/
    ├── main/java/com/urbanmove/synthgen/
    │   ├── GeneratorCli.java    ← punto de entrada: lee parámetros y muestra el resumen
    │   ├── Settings.java        ← parámetros (días, semilla, flota...)
    │   ├── Rules.java           ← comportamiento: probabilidades, umbrales y tiempos
    │   ├── ConfigLoader.java    ← lee y comprueba los JSON de config/
    │   ├── Zone.java, DemandProfile.java, VehicleType.java, Vehicle.java, User.java, Ride.java
    │   ├── World.java           ← crea flota y usuarios, distancias, puntos dentro de una zona
    │   ├── Dice.java            ← todas las decisiones al azar (incluida la distribución de Poisson)
    │   ├── IdFactory.java       ← ids reproducibles: UUID y res-000001, ride-000001...
    │   ├── EventFactory.java    ← construye cada evento con el formato de los contratos
    │   ├── Simulator.java       ← el motor: agenda, demanda, viajes, mantenimiento, foto horaria
    │   ├── HourZone.java, ZoneHour.java, GenerationResult.java
    │   ├── OutputWriter.java    ← escribe raw/, seed/, el CSV y summary.json
    │   ├── SummaryBuilder.java  ← calcula summary.json
    │   └── ToolPaths.java       ← encuentra la carpeta del generador dentro del repo
    └── test/java/com/urbanmove/synthgen/GeneratorTest.java
```

Para cambiar el **comportamiento**, edita `Rules.java`. Para cambiar **zonas o patrones**, edita los JSON de `config/` (no hace falta recompilar).

## Tests

`GeneratorTest` tiene 18 tests que comprueban:

- que todos los eventos cumplen los contratos y los `eventId` no se repiten;
- que el generador deja los eventos de recolocación para la Lambda;
- que la misma semilla da los mismos datos y otra semilla da datos distintos;
- que los eventos están en orden cronológico;
- que **cada vehículo sigue la máquina de estados** (nunca se reserva uno ya reservado);
- que **el estado final de cada vehículo coincide con lo que dicen los eventos** (detecta vehículos "atascados");
- que la zona de cada evento coincide con donde terminó el último viaje;
- que reserva, inicio y fin comparten `correlationId`, y que cada reserva tiene exactamente un desenlace y caduca justo cuando se prometió;
- que cada retirada tiene su vuelta al servicio con la misma traza, y que viajes y costes son coherentes;
- que el escenario por defecto produce déficits para la Lambda de recolocación;
- que las carpetas siguen el formato del Data Lake, que nunca se borra una carpeta ajena y que sí puede sobrescribir su propia salida.
