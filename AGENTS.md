# Cuota Codex

## Proyecto

Aplicación Android nativa. Versión de desarrollo: 0.2.0-beta.1, versionCode 8.
Namespace de código: `es.codexquota.mobile`; identificador instalable de la beta:
`es.codexquota.mobile.beta`. La beta se instala junto a la distribución 0.1.6.
Android mínimo 26, target/compile 35. Kotlin y Java con Gradle; no compilar Smali.
La fuente Java inicial se recuperó de la distribución 0.1.6 y se revisa al migrar;
los módulos nuevos deben preferir Kotlin con interfaces pequeñas y pruebas.

## Reglas de producto

- Interfaz en español, compacta, accesible y con estilo monocromo. No añadir historial.
- Confirmación explícita antes de TODO canje o reintento, desde actividad o widget.
- Fijar el crédito que caduca antes al preparar la confirmación y validarlo al enviar.
- Conservar idempotencia, crédito e identidad de cualquier solicitud pendiente.
- Reintentar automáticamente solo consultas de lectura, nunca un canje.
- Avisar de recuperación solo tras confirmación del servidor; no inferirla de un reloj.
- Los recordatorios basados en fechas guardadas deben identificarse como tales y
  cancelarse/reprogramarse al cambiar cuenta, ajustes, sesión o datos.
- Mantener frecuencia elegida, umbrales y preferencias. Android puede retrasar trabajos.
- Sesiones cifradas con Android Keystore. No registrar tokens ni códigos de acceso.

## Validación y entrega

Compilar, ejecutar pruebas unitarias y de integración Android disponibles y comprobar
firma/manifest del APK. Pruebas sin servicios reales: no iniciar sesión con cuentas ni
consumir resets. Explicar los límites de pruebas físicas o de conectividad reales.
La referencia 0.1.6 es inmutable y está fuera del proyecto. La beta cambia su comportamiento
deliberadamente; no se exige igualdad binaria con 0.1.6.
No incluir claves, contraseñas, local.properties, cachés ni SDK/JDK en el repositorio.
Conservar una firma de beta estable fuera del código para permitir futuras actualizaciones.
