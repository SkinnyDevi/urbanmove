# Visor de mapa

Página web que dibuja sobre un mapa de Las Palmas los datos del [generador sintético](../synthetic-generator/README.md): las 8 zonas, los 100 vehículos con su estado, los viajes en curso y los déficits de la semana. Permite avanzar y retroceder en el tiempo para ver cómo se mueve la flota.

Es una herramienta para **ver y comprobar los datos** y para **la demo**. No sustituye a la app de usuario (bloque A) ni al dashboard operativo (bloques C/D), pero ambos pueden reutilizar su código.

## Uso

1. Genera los datos con el generador (flecha verde en `GeneratorCli.java`). Se crea `tools/synthetic-generator/out/`.
2. Abre `tools/map-viewer/index.html` con doble clic. Se abre en el navegador; no hace falta servidor.
3. Pulsa **Abrir carpeta out** y elige `tools/synthetic-generator/out`. El navegador puede pedir confirmación para "subir" la carpeta: los ficheros **no salen de tu ordenador**, la página los lee en local.

Necesita conexión a internet para descargar la librería del mapa (Leaflet) y las imágenes del mapa (OpenStreetMap).

## Qué se ve

- **Zonas**: círculo con el número de vehículos disponibles. Verde con 6 o más, ámbar con 3 a 5 y rojo con menos de 3 (el umbral de déficit del enunciado). Al acercar el zoom aparece también el nombre.
- **Vehículos**: círculo = bici, cuadrado = patinete. Colores: verde disponible, ámbar reservado, azul en uso, rojo batería baja y gris retirado. Los que están en uso se ven viajando de una zona a otra. Al pasar el ratón por encima se ve su id.
- **Panel**: totales de la flota, tabla por zona (disponibles bici / patinete, reservados, batería baja y retirados), los últimos eventos en castellano y la lista de **déficits de la semana**.
- **Déficits**: si se cargó `ground_truth_zone_hours.csv`, durante una hora con déficit la zona parpadea y aparece un aviso. Al pulsar un déficit de la lista, el mapa vuela a esa zona en ese momento: es justo lo que deberá detectar la Lambda de recolocación.

Controles: la barra de tiempo, **▶ Reproducir** (con velocidad de 15 min, 1 h o 4 h por segundo) y los botones de ±1 hora.

## Cómo funciona

| Fichero | Qué hace |
|---|---|
| `index.html` | La estructura de la página. Carga Leaflet desde un CDN con versión fija (1.9.4) y huella de integridad. |
| `styles.css` | El aspecto, con modo claro y oscuro según el sistema y diseño adaptado a móvil. |
| `viewer-core.js` | **La lógica, sin nada visual.** Reproduce los eventos en orden hasta el instante elegido, como haría el servicio de disponibilidad, y calcula el estado de cada vehículo y los totales por zona. |
| `app.js` | **La parte visual.** Lee los ficheros, dibuja zonas y vehículos con Leaflet, y conecta los controles de tiempo. |

La idea clave es la de `viewer-core.js`: **el estado de la flota no se guarda en ningún sitio, se reconstruye a partir de los eventos**. Para saber cómo estaba todo el miércoles a las 14:00, se aplican en orden todos los eventos hasta ese momento. Es el mismo principio con el que trabajarán el historial y la analítica (*event sourcing*).

Se comprobó que esta reconstrucción coincide exactamente con la "hoja de respuestas" del generador: 0 discrepancias en las 1.344 horas-zona de una semana.

## Tests

La lógica tiene 12 tests que se ejecutan con Node.js (sin navegador), desde la raíz del repo:

```bash
node --test tools/map-viewer/test/viewer-core.test.js
```

Comprueban la lectura de ficheros, los cambios de estado de los vehículos, que al terminar un viaje el vehículo cambia de zona, que retroceder en el tiempo da lo mismo que avanzar, los recuentos por zona, la detección de déficits y los textos del registro de eventos.
