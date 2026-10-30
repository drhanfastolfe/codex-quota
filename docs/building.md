# Compilación y firma

## Herramientas

- JDK 17 o posterior; validación de referencia con JDK 21.
- Android SDK Platform 35 y Build Tools 35.0.0.
- Gradle 8.13 mediante el wrapper incluido, con SHA-256 de la distribución fijado.
- Android Gradle Plugin 8.9.2 y Kotlin 2.1.20.

Configurar `ANDROID_HOME` o un archivo local `local.properties` con `sdk.dir` apuntando al SDK. No incluir ese archivo en el repositorio. Gradle descarga dependencias desde Google Maven, Maven Central y el portal de plugins; mantener TLS y la configuración de proxy del entorno si se utiliza uno.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Los informes de pruebas se generan en `app/build/reports/tests/` y los de Android Lint en `app/build/reports/`. El APK de desarrollo está en `app/build/outputs/apk/debug/app-debug.apk` y usa la firma local de depuración. Esa firma puede diferir de la distribución beta.

## Distribución beta

La aplicación usa namespace `es.codexquota.mobile` e identificador instalable `es.codexquota.mobile.beta`, versionCode **8** y versionName **0.2.0-beta.1**. Mantener el identificador y la firma en sucesivas betas para permitir actualizaciones. La instalación anterior `es.codexquota.mobile` tiene otra identidad de firma y permanece separada.

La compilación de distribución se firma únicamente cuando se proporcionan estas variables:

| Variable | Contenido |
| --- | --- |
| `CODEX_QUOTA_KEYSTORE` | Ruta al almacén de claves, fuera del repositorio. |
| `CODEX_QUOTA_STORE_PASSWORD` | Contraseña del almacén. |
| `CODEX_QUOTA_KEY_ALIAS` | Alias; predeterminado `codex-quota-beta`. |
| `CODEX_QUOTA_KEY_PASSWORD` | Contraseña de la clave, si difiere de la del almacén. |

Introducir las contraseñas mediante el gestor de secretos de CI o una lectura interactiva sin eco. No ponerlas literalmente en comandos, logs ni archivos versionados. Con las variables disponibles:

```sh
./gradlew :app:assembleRelease
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Sin variables de firma, el resultado de distribución queda sin firmar y no puede instalarse directamente. El archivo de `releases/` está firmado. SHA-256 del certificado público de la beta:

```text
6652f1dc8f7d1ad66d636f47a9a1a8bcfaa190cc881cccb1e3e010296b59805e
```

Conservar el almacén privado y sus contraseñas de forma separada del código. El certificado público identifica la firma, pero no permite volver a firmar una actualización.

## Verificación de una distribución

El directorio de la versión contiene `SHA256SUMS`. Con Android SDK Build Tools en el `PATH`:

```sh
cd releases/0.2.0-beta.1
sha256sum --check SHA256SUMS
apksigner verify --verbose --print-certs codex-quota-0.2.0-beta.1.apk
```

Los hashes del APK pueden cambiar entre compilaciones debido al empaquetado. La comparación de la beta se basa en pruebas de comportamiento, manifiesto, firma y contenido compilado; no se espera igualdad binaria con 0.1.6, que tiene funciones distintas.
