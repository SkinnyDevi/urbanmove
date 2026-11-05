# Herramientas de desarrollo

Herramientas que no se despliegan en AWS: sirven para desarrollar, probar y enseñar los datos. Las dos primeras son un proyecto Maven (Java); el visor es una página web.

| Módulo | Qué hace |
|---|---|
| [`contract-validator`](contract-validator/README.md) | Valida eventos contra los contratos de `docs/contracts/asyncapi/`. Es una librería (la usarán el generador y la Lambda de ingesta) y una herramienta de terminal. |
| [`synthetic-generator`](synthetic-generator/README.md) | Simula días de uso de UrbanMove en Las Palmas y genera eventos realistas con las carpetas del Data Lake. |
| [`map-viewer`](map-viewer/README.md) | Página web (HTML y JavaScript) que dibuja en un mapa los datos del generador y permite avanzar en el tiempo. No es parte del proyecto Maven: se abre con doble clic en `index.html`. |

Requisitos: **Java 21** y **Maven** (IntelliJ trae Maven integrado).

## Desde IntelliJ (recomendado)

1. Clic derecho sobre `tools/pom.xml` → **Add as Maven Project**. A la derecha aparece la pestaña **Maven**.
2. En la pestaña Maven: **urbanmove-tools → Lifecycle → test** ejecuta los tests de los dos módulos.
3. **Lifecycle → package** genera los `.jar` ejecutables.

La primera vez Maven descarga las librerías de internet; tarda un poco.

## Desde la terminal (si tienes `mvn` instalado)

```bash
cd tools
mvn test       # compila y ejecuta todos los tests
mvn package    # además genera los .jar en contract-validator/target y synthetic-generator/target
```

## Versiones

Todas se fijan en `tools/pom.xml`: Java 21, Jackson 2.22.1, networknt json-schema-validator 2.0.8 y JUnit 5.12.2.
