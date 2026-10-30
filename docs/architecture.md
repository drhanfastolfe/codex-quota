# Arquitectura

La aplicación combina actividades y widgets Android nativos con reglas pequeñas en Kotlin. La interfaz usa el mismo repositorio que los trabajos en segundo plano. No hay servidor intermedio.

| Componente | Función |
| --- | --- |
| `QuotaRepository` | Sesión, caché, generación de cuenta, coordinación de consultas, trabajos y canjes pendientes. |
| `core.DeviceLogin`, `TokenStore` | Código de autorización, PKCE, reanudación y almacenamiento cifrado con Android Keystore. |
| `core.QuotaClient`, `HttpTransport` | Peticiones HTTPS y errores clasificados, límites de respuesta y renovación de tokens. |
| `core.QueryRetryPolicy`, `RefreshJob` | Reintentos limitados de consultas transitorias, separados de la frecuencia periódica. |
| `core.AlertPolicy`, `RecoveryPolicy`, `ExpirySchedulePolicy` | Condiciones, deduplicación, recuperación confirmada y fechas de recordatorios. |
| `QuotaAlerts`, `ResetExpiryReceiver`, `QuotaBootReceiver` | Canales, permisos, entrega de avisos y reprogramación tras eventos del sistema. |
| `core.QuotaPresentation`, `WidgetLayoutPolicy` | Antigüedad, colores según umbral, contexto de renovación y adaptación al espacio. |
| `MainActivity`, actividades de ajustes | Estado de cuota, acceso, ajustes y confirmación explícita de canjes. |
| `QuotaWidget`, `MiniQuotaWidget`, `ResetListService` | Presentación de datos compartidos y lista de créditos en la pantalla de inicio. |

## Consulta y presentación

La frecuencia elegida es de 15 a 360 minutos, con 30 minutos por defecto. `JobScheduler` coordina las consultas periódicas y manuales. Un fallo temporal de lectura puede generar hasta tres reintentos con espera progresiva y respeto de `Retry-After`. Estos trabajos no cambian el intervalo guardado y no se usan para canjear resets.

La interfaz identifica como antiguos los datos que superan el intervalo elegido. El widget programa una alarma local inexacta, sin despertar el móvil, para actualizar ese estado cuando venza la antigüedad. Actualizar un tiempo relativo o marcar datos antiguos no implica consultar al servidor. Los porcentajes muestran cuota restante y las fechas de renovación pertenecen a ventanas independientes de cinco horas, semanales u otras que entregue el servicio.

## Avisos

La cuota baja se comprueba con datos nuevos. La recuperación requiere haber observado que el servicio no permite uso y confirmar después que sí lo permite. Llegar a una fecha o mostrar un porcentaje positivo no autoriza por sí solo ese aviso.

La caducidad también se programa desde créditos conocidos con una alarma inexacta. El aviso local se identifica como basado en la última consulta. La cuenta, ajustes y fechas se validan antes de mostrarlo; las consultas posteriores permiten cancelar o reprogramar los recordatorios. Android puede retrasarlos. No se pide permiso de alarmas exactas.

Las categorías de aviso se pueden desactivar independientemente. Se conservan canales y diagnósticos de entrega; una entrega bloqueada no debe gastarse como si hubiera sido notificada. Los avisos de prueba no consultan la cuota ni consumen créditos.

## Canjes

El widget abre una confirmación en `MainActivity`. Solo la acción positiva entrega una aprobación a `QuotaRepository.consumeReset()`. La aprobación fija la sesión y el crédito que caduca antes; un estado cambiado obliga a revisar la acción. Antes de enviar se conserva la solicitud de canje con su identificador de idempotencia.

Un resultado incierto conserva el crédito y la solicitud para un reintento expresamente confirmado. Un error de autenticación puede renovar la credencial, pero no reenvía automáticamente el POST de canje: exige nueva confirmación. Un crédito cuyo consumo se confirma se retira de la caché y de sus recordatorios aunque falle la consulta posterior, sin inventar porcentajes ni una fecha nueva de consulta.

No se canjean créditos en trabajos de consulta, alarmas, inicio de sesión ni pruebas de notificaciones. Las solicitudes pendientes quedan vinculadas a la identidad de cuenta, separadas de la caché de uso.

## Integración y privacidad

La autenticación usa `auth.openai.com`; las consultas y resets usan endpoints internos bajo `https://chatgpt.com/backend-api/wham/`. Pueden cambiar sin un contrato público de compatibilidad.

Las credenciales se cifran con AES-GCM y Android Keystore. Las preferencias privadas guardan caché, ajustes y estados de avisos. El manifiesto deshabilita copias de seguridad y tráfico HTTP sin cifrar. Los informes y las pruebas no necesitan credenciales ni datos de una cuenta real.
