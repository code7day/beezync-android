# Beezync SDK para Android (Kotlin)

Cliente de Beezync para Android: registro de dispositivos FCM, WebSocket con reconexión automática, canales, mensajes como `Flow` y recibos de notificación. Sigue `Api-Service/docs/SDK_SPEC.md`.

- Paquete: `com.beezync.sdk`, `minSdk 23`.
- Dependencias: OkHttp, kotlinx-coroutines, kotlinx-serialization-json y, para push, `firebase-messaging`.
- Nunca usa ni guarda la llave secreta. Solo la llave pública `pk_...`.

## Estructura

```
sdk/src/main/kotlin/com/beezync/sdk/        Núcleo en Kotlin puro (sin imports de Android)
sdk/src/main/kotlin/com/beezync/sdk/fcm/     Parte Android: servicio FCM, canal de notificaciones, SharedPreferences
sdk/src/test/kotlin/com/beezync/sdk/         Pruebas JVM (JUnit + MockWebServer)
```

El núcleo (`Beezync`, `BeezyncApi`, `RealtimeClient`, `Protocol`, `Backoff`) se prueba en la JVM sin emulador.

## Instalación

Mientras no haya publicación en Maven, incluya el módulo como proyecto Gradle. En el `settings.gradle.kts` de su app:

```kotlin
include(":sdk")
project(":sdk").projectDir = file("../ruta/a/Add-ons/SDKs/android-kotlin/sdk")
```

En `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":sdk"))
}
```

Si publica el SDK en un repositorio Maven propio, la dependencia sería `implementation("com.beezync:sdk:<version>")`.

## Configurar Firebase

1. En la consola de Firebase cree o abra el proyecto y agregue una app Android con el `applicationId` de su app.
2. Descargue `google-services.json` y colóquelo en `app/google-services.json`. No lo suba al repositorio.
3. En `build.gradle.kts` raíz agregue el plugin y aplíquelo en `app/build.gradle.kts`:

```kotlin
// raíz
plugins {
    id("com.google.gms.google-services") version "4.4.2" apply false
}

// app
plugins {
    id("com.google.gms.google-services")
}
```

4. En el panel de Beezync cargue las credenciales de la cuenta de servicio de Firebase (FCM HTTP v1) de esa app, para que el motor pueda enviar.

## Permisos y canal de notificaciones

El manifiesto de la librería ya declara `INTERNET`, `POST_NOTIFICATIONS`, el servicio `BeezyncMessagingService`, el receptor de descartes y el canal por defecto `beezync_default` para notificaciones en segundo plano.

- `Beezync.initialize(context, config)` crea el canal `beezync_default` (Android 8+).
- En Android 13+ (API 33) debe pedir el permiso en tiempo de ejecución:

```kotlin
val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
```

Sin el permiso, el SDK sigue registrando recibos pero no muestra notificaciones locales.

## Uso

Inicialice una sola vez en su `Application` (y declárela en el manifiesto con `android:name`):

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Beezync.initialize(this, BeezyncConfig(
            appId = "UUID-de-la-app",
            publicKey = "pk_...",
            baseUrl = "https://api.beezync.com",
        ))
    }
}
```

Registro y usuario (todas las llamadas son `suspend` y devuelven `Result`, no lanzan en el hilo principal):

```kotlin
val beezync = Beezync.requireShared()
val token = FirebaseMessaging.getInstance().token.await()   // kotlinx-coroutines-play-services, o addOnCompleteListener
beezync.registerDevice(token, externalUserId = "user-42", platform = "android", tags = mapOf("plan" to "pro"))
beezync.setUser("user-42")
beezync.setTags(mapOf("lang" to "es"))
beezync.unregisterDevice()
```

WebSocket y canales:

```kotlin
beezync.connect()
beezync.subscribe("noticias")                                  // canal público
beezync.subscribe("private-pedidos", auth = authDelServidor)   // canal privado
beezync.unsubscribe("noticias")

lifecycleScope.launch {
    beezync.messages.collect { m ->
        // m.kind: PUBLISHED (event, channel, data, id, timestamp) o NOTIFICATION (title, body, id)
    }
}
val sub = beezync.onMessage { m -> /* ... */ }   // sub.close() para dejar de escuchar
beezync.connectionState                          // StateFlow<ConnectionState>
beezync.subscriptionErrors                       // SharedFlow<SubscriptionError>
beezync.disconnect()
```

Canales privados: el `auth` es el HMAC de `socket_id:canal` que firma el servidor de su aplicación con la llave secreta (`POST /v1/api/channels/auth`). Como el `socket_id` cambia en cada conexión, para que la resuscripción tras una reconexión funcione registre un autorizador que lo pida a su servidor:

```kotlin
beezync.realtime.authorizer = ChannelAuthorizer { socketId, channel -> miBackend.authDe(socketId, channel) }
```

Con `token` (de `POST /v1/api/subscription-tokens`) no hace falta autorizador mientras el token no expire: `beezync.subscribe("private-x", token = t)`.

### Reconexión

Espera exponencial de 1 s hasta 30 s con variación (hasta +25 %), reinicio del contador al conectar, resuscripción automática a todos los canales activos (con un pequeño espaciado por el límite de 20 mensajes por segundo) y reconexión inmediata si el servidor cierra con el código `4200`. `disconnect()` detiene los reintentos.

### Recibos de notificación

El motor envía `bz_id` dentro de `data` del mensaje FCM.

```kotlin
val id = remoteMessage.beezyncId()          // o BeezyncPush.notificationId(remoteMessage.data)
beezync.trackReceived(id)
beezync.trackClicked(id)
beezync.trackDismissed(id)
```

`BeezyncMessagingService` ya hace esto: registra el token nuevo en `onNewToken`, reporta `received` en `onMessageReceived` (app en primer plano o mensajes solo de datos), muestra una notificación local si trae título o cuerpo y reporta `dismissed` si el usuario la descarta. Cuando la app está en segundo plano y el mensaje trae bloque `notification`, Android lo muestra sin ejecutar código de la app; al tocarlo, la Activity recibe `bz_id` como extra del `Intent`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Beezync.shared?.reportNotificationOpened(intent)   // envía received y clicked
}
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    Beezync.shared?.reportNotificationOpened(intent)
}
```

El servidor cuenta cada evento una vez por dispositivo, así que enviar `received` junto con `clicked` es seguro.

### Servicio propio

Para personalizar (icono, canal, texto) extienda la clase y reemplace la declaración de la librería en su manifiesto:

```kotlin
class MyService : BeezyncMessagingService() {
    override val channelId = "mi_canal"
}
```

```xml
<service android:name="com.beezync.sdk.BeezyncMessagingService" tools:node="remove" />
<service android:name=".MyService" android:exported="false">
    <intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT" /></intent-filter>
</service>
```

## Pruebas

Las pruebas están en `sdk/src/test/kotlin` (backoff, protocolo, HTTP con MockWebServer, WebSocket con reconexión y código 4200). Con Android Studio o Gradle instalado:

```
gradle wrapper    # solo si falta gradlew
./gradlew :sdk:testDebugUnitTest
```

`LiveEngineTest` se salta salvo que existan las variables de entorno `BEEZYNC_BASE_URL`, `BEEZYNC_APP_ID`, `BEEZYNC_PUBLIC_KEY` y `BEEZYNC_SECRET_KEY`; con ellas ejecuta el flujo completo contra un motor real (registro, WebSocket, publish, send-push por `ws`, recibos y contadores). La llave secreta se lee solo del entorno.

## Seguridad

- No se guarda ni se pide la llave secreta.
- No se escriben tokens en los logs.
- Los errores de red se devuelven como `Result.failure`.
- `baseUrl` debe ser `https` en producción. `http` solo para desarrollo (en Android requiere `usesCleartextTraffic`).
