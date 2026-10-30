# Validación de 0.2.0-beta.1

La suite local ejecuta **93 pruebas**, con **0 fallos, 0 errores y 0 omisiones**. El [informe de pruebas](test-results.json) identifica las clases y los recuentos. Las comprobaciones usan JUnit y Robolectric con Android API 35, incluyendo gráficos nativos para revisar los layouts. No utilizan cuentas, credenciales ni servicios reales de ChatGPT.

## Comportamiento cubierto

- Confirmación antes del canje desde la actividad y desde el widget. Cancelar no envía peticiones de canje.
- Selección del crédito que caduca antes, conservación del identificador de idempotencia y rechazo de aprobaciones invalidadas por un cambio de cuenta o de créditos.
- Un error 401 puede renovar la sesión, pero no reenvía automáticamente el POST de canje. Una nueva confirmación reutiliza la solicitud pendiente.
- Tras consumo confirmado y consulta posterior fallida, el crédito se retira de la caché y de sus recordatorios sin cambiar la fecha de los datos ni inventar una recuperación de cuota.
- Inicio de sesión por código: espera de autorización, límites de peticiones, PKCE y persistencia del intercambio. Los sustitutos de transporte impiden acceder al servicio real.
- Consultas y trabajos: frecuencia elegida, agrupación de actualizaciones, reintentos limitados y respeto de `Retry-After`.
- Avisos: permisos, deduplicación, cambio de cuenta, fechas conocidas, restauración de recordatorios y recuperación confirmada por datos del servidor.
- Presentación: umbral del 55 %, estados antiguos, contexto de renovación y widgets en sus dimensiones mínimas.
- Texto ampliado a 1,3 y 1,5, fechas de reset en una línea y presencia real de texto dibujado en los botones del widget.

## Revisión visual

Las imágenes en [screenshots](screenshots/) se generan con datos de prueba desde las vistas y recursos de la aplicación, usando el renderizado nativo de Robolectric. La colección de resets se alimenta con la misma fábrica que utiliza el servicio del widget. Se han revisado la pantalla principal, los avisos, la confirmación y ambos widgets.

Son vistas simuladas: no representan una cuenta real ni capturas tomadas en un Pixel. El lanzador, las fuentes y los ajustes del dispositivo pueden introducir diferencias.

## Alcance

La validación automática no sustituye una prueba de conexión real con OpenAI ni una instalación en hardware. La compatibilidad futura con los endpoints internos y la puntualidad de los trabajos sujetos a ahorro de batería dependen de servicios externos y del sistema Android.

La beta cambia deliberadamente funciones, recursos e identidad de instalación respecto a 0.1.6. No se afirma igualdad binaria con esa distribución. El APK de esta versión se construye desde el proyecto Kotlin/Java y su firma pública queda documentada en [compilación](building.md).
