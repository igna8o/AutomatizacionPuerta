# Una tarjeta NFC para iPhone y Android

iPhone ya funciona con su automatización personal NFC en Atajos y la acción de Apple Casa. Android usa una app propia basada en el ejemplo y SDK oficiales de Tedee. No se reescribe la tarjeta: cada celular registra la misma tarjeta como disparador y la cerradura verifica las credenciales Bluetooth del usuario.

## Estado

APK debug generado y compilación verificada el 10/10/2026 con assembleDebug y lintDebug (0 errores, 60 advertencias). JDK 17, SDK Android 34 y Gradle 8.2.1 están preparados en .tools. Pendiente de instalar y probar con el teléfono y la Tedee PRO física; no hay un dispositivo Android conectado por USB. No se ha verificado aún la emisión de certificados para el usuario compartido, la ejecución de NFC fuera de la app, ni la apertura real.

## Compilar

Opción recomendada: instalar Android Studio y abrir la carpeta `android`. Usar JDK 17, SDK 34 y Gradle 8.2.1. El ejemplo upstream no incluía el wrapper; el script `prepare-android.ps1` puede descargar las herramientas dentro de `.tools`, presentar las licencias del SDK y compilar el APK. Requiere conexión a internet y aceptar las licencias que presente Google.

```powershell
.\prepare-android.ps1
```

APK generado: `android/app/build/outputs/apk/debug/app-debug.apk` (aproximadamente 8 MB). Copiarlo al teléfono y abrirlo para instalar; Android puede pedir habilitar la instalación desde esa fuente. Informe de lint: `android/app/build/reports/lint-results-debug.html`.

Para volver a compilar con las herramientas ya instaladas:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build-android.ps1
```

## Configurar el Android

1. Instalar el APK en un Android con NFC y Bluetooth. Mínimo Android 8; versiones recientes se deben validar en el modelo real.
2. Abrir Tedee con la cuenta del usuario autorizado y comprobar que abre la puerta.
3. Desde la cuenta de ese usuario en https://portal.tedee.com, generar una Personal Access Key con `Device certificates: Operate` y `Mobile devices: Read` / `Write`, según la documentación actual de la API y el ejemplo oficial de registro móvil. Si el registro rechaza los permisos, revisar con el propietario; no usar una clave del propietario compartida entre celulares.
4. En la app Puerta NFC, ingresar número de serie, Device ID (desde Información en Tedee) y la clave. Tocar **Registrar celular / renovar acceso**. La clave se usa en memoria durante el registro y se borra del campo; no se incorpora al código ni al APK.
5. Conceder permisos Bluetooth/dispositivos cercanos y ubicación precisa mientras se usa la app. El SDK Tedee 1.0.0 comprueba ambos permisos también en Android 12 o posterior. En Android 8–11 activar además el servicio de ubicación para buscar Bluetooth.
6. Probar **Probar apertura por Bluetooth** al lado de la puerta.
7. Tocar **Registrar tarjeta NFC**, acercar la misma tarjeta usada en iPhone, retirarla y volver a acercarla. La lectura de registro no abre.

La configuración y el certificado se guardan cifrados con una clave de Android Keystore. El SDK gestiona la clave privada de Bluetooth. Copias de seguridad desactivadas. No se registran cuerpos de API ni claves en Logcat.

## Uso y límites

- Con la app visible, lee la tarjeta mediante NFC Reader Mode y solicita abrir por Bluetooth si coincide con la tarjeta registrada. No hay servidor ni Bridge en esta ruta.
- Con la pantalla desbloqueada, el filtro NFC TECH inicia una actividad transparente: no muestra la configuración, espera el resultado Bluetooth y presenta un aviso breve antes de cerrarse. Abrir Puerta NFC desde su icono sigue mostrando la configuración. Si faltan permisos, hay que concederlos desde la app visible; el escaneo no abre diálogos de permisos. El sistema puede mostrar un selector; las etiquetas con contenido NDEF reconocido por otra aplicación pueden dirigirse a esa aplicación. Hasta probar el teléfono y la tarjeta, el modo comprobable es abrir Puerta NFC y acercar la tarjeta. No modificar la tarjeta para resolver esto sin verificar también la automatización de iPhone.
- No se promete lectura con pantalla apagada o bloqueada.
- Internet se necesita para registrar/renovar certificados, y si la cerradura solicita sincronizar la hora firmada de Tedee. Los certificados vencidos se rechazan antes de conectar. No se promete funcionamiento offline indefinido.
- La tarjeta y su UID pueden copiarse: son un disparador público, no una prueba de identidad o de presencia. Solo el certificado autorizado permite abrir la cerradura. Los permisos, horarios y revocaciones deben probarse en la cerradura real; no se afirma revocación instantánea sin conexión.
- La app evita órdenes duplicadas durante una operación y en los cinco segundos siguientes al inicio; espera como máximo 20 segundos y no reintenta una apertura automáticamente.
- **Desbloquear** no siempre retrae el pestillo. Para una puerta que lo necesite, comprobar la configuración de pull spring en Tedee durante la prueba.
- Si un intento se interrumpe o pierde conexión, revisar el estado en Tedee antes de reintentar: la orden podría haber llegado a la cerradura.
- Distribución inicial privada mediante APK debug. Para distribuir una versión final, firmar con una clave propia y revisar permisos, UX y acceso en hardware real.

## Verificación en hardware

Probar apertura manual Bluetooth; tarjeta registrada y no registrada; registro sin apertura; lectura repetida; NFC/Bluetooth apagados; permisos denegados; teléfono bloqueado; fuera de alcance; certificado vencido; usuario sin acceso/fuera de horario; pérdida de conexión; pestillo si corresponde; iPhone después de registrar la tarjeta en Android. La app solo indica “Cerradura desbloqueada” tras recibir el estado abierto, no por enviar la orden.

## Fuentes

- https://github.com/tedee-com/tedee-example-ble-android (base: `b9bfad52a8afe6d50852f5542d79ae407f91ee9f`)
- https://github.com/tedee-com/tedee-mobile-sdk-android
- https://developer.android.com/develop/connectivity/nfc/nfc

El ejemplo upstream se conserva en `android/README.md`; describe el demo original y no reemplaza estas instrucciones. Los archivos del ejemplo mantienen sus licencias y avisos originales.
