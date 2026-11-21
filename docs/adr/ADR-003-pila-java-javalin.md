# ADR-003 · Pila de los microservicios Java: Javalin sin framework de inyección

- **Estado:** aceptada (propuesta por el bloque B, pendiente de confirmar por el equipo)
- **Fecha:** 21 de noviembre de 2026

## Contexto

El backend necesita una misma base para todos los microservicios Java: servidor HTTP, validación de JWT, logs JSON con `correlationId`, errores RFC 7807, base de datos propia con migraciones y tests. El equipo tiene tres semanas hasta la congelación del código y la mayoría de sus miembros no ha empezado todavía el backend, así que la plantilla tiene que ser fácil de entender, copiar y explicar en la defensa.

La primera versión de la plantilla (`zones`) se hizo con Spring Boot 4.1. Funcionaba, pero mucho de lo que hacía quedaba oculto: anotaciones, autoconfiguración y un contenedor de inyección que decide qué objetos se crean.

## Decisión

Los microservicios Java usan:

| Necesidad | Librería |
|---|---|
| HTTP | **Javalin 7** (sobre Jetty) |
| JSON | Jackson 2 |
| JWT | Nimbus JOSE + JWT |
| Base de datos | JDBC con HikariCP, PostgreSQL y migraciones Flyway |
| Logs | SLF4J + Logback con un encoder JSON propio (`AGENTS.md` 6.2) |
| Tests | JUnit, Testcontainers (PostgreSQL real), swagger-parser + networknt (contrato) |

**Sin framework de inyección de dependencias.** Cada servicio crea y conecta sus piezas a mano en `*Application.start()` y se las pasa por constructor a los casos de uso (`AGENTS.md` 4.2). Las versiones se fijan en `services/pom.xml`.

Las Lambdas siguen siendo Java sin framework, con las librerías oficiales de AWS para Lambda.

## Consecuencias

A favor:

- **Todo es visible.** El grafo de objetos de un servicio está en un solo método; no hay comportamiento que dependa de anotaciones o de la autoconfiguración. Se explica fácil en la defensa.
- **Arranque rápido y poca memoria**: el servicio `zones` arranca en menos de un segundo y su contenedor ocupa unos 150 MB, cómodo en tareas Fargate de 512 MB (`AGENTS.md` 8.3).
- **La arquitectura hexagonal no depende del framework**: el paso de Spring a Javalin no tocó ni el dominio ni los casos de uso.
- Las mismas librerías (Jackson, Nimbus, Logback) sirven en las Lambdas.

En contra:

- Lo que Spring daba hecho hay que escribirlo: el filtro de `correlationId`, el control de roles, el formato de errores y el acceso JDBC. Está resuelto una vez en la plantilla (`services/zones`) y se copia.
- Sin repositorios automáticos: cada consulta SQL se escribe a mano.
- Menos ejemplos en internet que Spring Boot.

## Alternativas descartadas

- **Spring Boot 4.1**: más completo y con más documentación, pero más pesado y menos transparente para un equipo que empieza tarde. Fue la primera versión de la plantilla (commit `4d3876f`).
- **Quarkus / Micronaut**: ligeros, pero con menos documentación y una curva de aprendizaje que no compensa en tres semanas.
