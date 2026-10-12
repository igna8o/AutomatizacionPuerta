package tedee.mobile.demo

import android.Manifest
import android.app.KeyguardManager
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import tedee.mobile.demo.api.data.model.MobileRegistrationBody
import tedee.mobile.demo.api.service.ApiProvider
import tedee.mobile.demo.api.service.MobileService
import tedee.mobile.sdk.ble.bluetooth.ILockConnectionListener
import tedee.mobile.sdk.ble.BluetoothConstants
import tedee.mobile.sdk.ble.bluetooth.ISignedTimeProvider
import tedee.mobile.sdk.ble.bluetooth.LockConnectionManager
import tedee.mobile.sdk.ble.keystore.getMobilePublicKey
import tedee.mobile.sdk.ble.model.DeviceCertificate
import tedee.mobile.sdk.ble.model.SignedTime
import tedee.mobile.sdk.ble.permissions.getBluetoothPermissions
import java.security.MessageDigest
import java.time.Instant
import retrofit2.HttpException
import java.io.IOException

/** NFC is a trigger, never an access credential. The lock authenticates the SDK certificate. */
open class NfcDoorActivity : AppCompatActivity(), ILockConnectionListener {
    protected open val quietScan = false
    private val errorListener: (Throwable) -> Unit = { onError(it) }
    companion object {
        private var lastAttempt = -10_000L
    }
    private lateinit var store: DoorStore
    private lateinit var config: JSONObject
    private lateinit var status: TextView
    private lateinit var serial: EditText
    private lateinit var deviceId: EditText
    private lateinit var accessKey: EditText
    private lateinit var enroll: Button
    private lateinit var register: Button
    private val connection by lazy { LockConnectionManager(this) }
    private val api = MobileService()
    private var enrolling = false
    private var resumed = false
    private var busy = false
    private var pendingOpen = false
    private var timeout: Job? = null
    private var opening: Job? = null
    private var enrollTimeout: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = DoorStore(this)
        try { config = store.read() } catch (_: Exception) {
            config = JSONObject()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun label(text: String) { content.addView(TextView(this).apply { this.text = text }) }
        fun field(hint: String, type: Int): EditText = EditText(this).apply {
            this.hint = hint
            inputType = type
            content.addView(this)
        }
        label("Abrir puerta con NFC")
        label("Configurá este celular con tu cuenta Tedee. Usá la misma tarjeta que en iPhone.")
        serial = field("Número de serie de la cerradura", InputType.TYPE_CLASS_TEXT)
        deviceId = field("Device ID", InputType.TYPE_CLASS_NUMBER)
        accessKey = field("Personal Access Key (solo para registrar o renovar)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        serial.setText(config.optString("serial"))
        deviceId.setText(config.optString("deviceId"))
        register = Button(this).apply {
            text = "Registrar celular / renovar acceso"
            setOnClickListener { registerPhone() }
            content.addView(this)
        }
        enroll = Button(this).apply {
            text = "Registrar tarjeta NFC"
            setOnClickListener {
                if (!busy && ready()) {
                    enrolling = true
                    enrollTimeout?.cancel()
                    enrollTimeout = lifecycleScope.launch {
                        delay(30_000)
                        enrolling = false
                        show("Registro de tarjeta cancelado. Tocá Registrar tarjeta para volver a intentar.")
                    }
                    show("Acercá la tarjeta al lector del Android. Esta lectura no abre la puerta.")
                }
            }
            content.addView(this)
        }
        content.addView(Button(this).apply {
            text = "Probar apertura por Bluetooth"
            setOnClickListener { startOpening() }
        })
        status = TextView(this).apply { text = "Primero registrá el celular y después la tarjeta." }
        content.addView(status)
        label("NFC y Bluetooth deben estar activados. El teléfono debe estar desbloqueado. No se modifica la tarjeta.")
        if (!quietScan) setContentView(ScrollView(this).apply { addView(content) })
        (application as ExampleApplication).bluetoothErrorListener = errorListener
        connection.signedDateTimeProvider = object : ISignedTimeProvider {
            override fun getSignedTime(callback: (SignedTime) -> Unit) {
                lifecycleScope.launch {
                    try { callback(api.getSignedTime()) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { stopOpening("No se pudo obtener la hora firmada de Tedee. Revisá internet.") }
                }
            }
        }
    }

    private fun show(message: String) = runOnUiThread {
        status.text = message
        if (quietScan && !busy && !isFinishing) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun permissions(): Array<String> = getBluetoothPermissions().toTypedArray()

    private fun ready(): Boolean {
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked) {
            show("Desbloqueá el teléfono y volvé a acercarlo.")
            return false
        }
        if (permissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            if (quietScan) show("Abrí Puerta NFC y concedé los permisos de dispositivos cercanos y ubicación precisa.")
            else {
                requestPermissions(permissions(), 20)
                show("Permití dispositivos cercanos y ubicación precisa mientras se usa la app: el SDK de Tedee requiere ambos. Después volvé a intentar.")
            }
            return false
        }
        if (getSystemService(BluetoothManager::class.java).adapter?.isEnabled != true) {
            show("Activá Bluetooth y volvé a intentar.")
            return false
        }
        return true
    }

    private fun registerPhone() {
        if (busy) return
        val id = deviceId.text.toString().toIntOrNull()
        val number = serial.text.toString().trim()
        val secret = accessKey.text.toString().trim()
        if (id == null || id <= 0 || number.isBlank() || secret.isBlank()) {
            show("Completá Device ID, número de serie y la clave de TU cuenta Tedee.")
            return
        }
        if (!ready()) return
        busy = true
        register.isEnabled = false
        enrolling = false
        enrollTimeout?.cancel()
        show("Registrando celular con Tedee…")
        lifecycleScope.launch {
            var stage = "preparar la clave del celular"
            try {
                ApiProvider.authenticationKey = secret
                val publicKey = getMobilePublicKey() ?: error("No mobile key")
                val mobileId = config.optString("mobileId").ifBlank {
                    stage = "registrar el celular"
                    api.registerMobile(MobileRegistrationBody("Puerta NFC Android", publicKey = publicKey)).id
                }
                stage = "obtener el certificado"
                val certificate = api.getCertificate(mobileId, id)
                stage = "validar el certificado y su vencimiento"
                require(certificate.mobilePublicKey == publicKey)
                require(Instant.parse(certificate.expirationDate).isAfter(Instant.now()))
                require(certificate.certificate.isNotBlank() && certificate.devicePublicKey.isNotBlank())
                val next = JSONObject(config.toString()).apply {
                    if (optString("deviceId") != id.toString() || optString("serial") != number) remove("tagHash")
                    put("mobileId", mobileId)
                    put("serial", number)
                    put("deviceId", id.toString())
                    put("certificate", certificate.certificate)
                    put("devicePublicKey", certificate.devicePublicKey)
                    put("expiration", certificate.expirationDate)
                }
                stage = "guardar el acceso en el celular"
                store.write(next)
                config = next
                show("Celular registrado. Probá Bluetooth y después registrá la tarjeta NFC.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val detail = when (e) {
                    is HttpException -> when (e.code()) {
                        401 -> "Clave rechazada o vencida. Pegá solo la clave, sin PersonalKey."
                        403 -> "Acceso denegado. Revisá Mobile devices: Read/Write, Device certificates: Operate y el acceso de tu cuenta a la cerradura."
                        400, 404 -> "Revisá Device ID y los datos; Tedee rechazó la solicitud."
                        429 -> "Demasiados intentos. Esperá antes de volver a intentar."
                        else -> "Tedee no pudo completar la solicitud. Intentá más tarde."
                    } + " (HTTP ${e.code()})"
                    is IOException -> "No se pudo comunicar con Tedee. Revisá internet; si usás VPN, probá sin ella."
                    else -> "Falló la validación o el guardado (${e.javaClass.simpleName}). Revisá fecha y hora automáticas del teléfono."
                }
                show("No se pudo $stage. $detail")
            } finally {
                ApiProvider.authenticationKey = ""
                accessKey.text.clear()
                register.isEnabled = true
                busy = false
            }
        }
    }

    private fun handleTag(tag: Tag) {
        if (!resumed || busy || tag.id.isEmpty()) return
        val hash = MessageDigest.getInstance("SHA-256").digest(tag.id)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (enrolling) {
            enrolling = false
            enrollTimeout?.cancel()
            try {
                val next = JSONObject(config.toString()).put("tagHash", hash)
                store.write(next)
                config = next
                show("Tarjeta registrada. Retirala y acercala otra vez para abrir.")
            } catch (_: Exception) { show("No se pudo guardar la tarjeta.") }
        } else if (config.optString("tagHash") == hash) startOpening()
        else show("Esta tarjeta no está registrada en este celular.")
    }

    private fun startOpening() {
        if (!resumed || busy || enrolling || !ready()) return
        if (config.optString("certificate").isBlank()) {
            show("Primero registrá el celular.")
            return
        }
        val valid = try { Instant.parse(config.optString("expiration")).isAfter(Instant.now()) }
            catch (_: Exception) { false }
        if (!valid) {
            show("El certificado venció. Renovalo con internet antes de abrir.")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttempt < 5_000) {
            if (quietScan) finish()
            return
        }
        lastAttempt = now
        busy = true
        pendingOpen = true
        register.isEnabled = false
        show("Conectando con la cerradura por Bluetooth…")
        timeout = lifecycleScope.launch {
            delay(20_000)
            stopOpening("Tiempo de espera agotado. Comprobá el estado en Tedee antes de reintentar.")
        }
        try {
            connection.connect(config.getString("serial"),
                DeviceCertificate(config.getString("certificate"), config.getString("devicePublicKey")),
                true, this)
        } catch (e: Exception) { stopOpening(bluetoothError("conectar", e)) }
    }

    override fun onLockConnectionChanged(isConnecting: Boolean, isConnected: Boolean) {
        runOnUiThread {
            if (isConnected && pendingOpen) {
                pendingOpen = false
                opening = lifecycleScope.launch {
                    try {
                        show("Enviando orden de apertura…")
                        // SDK 1.0.0 omits ILockInteractor.DefaultImpls from its AAR.
                        // Pass the documented default explicitly to avoid that missing class.
                        connection.openLock(BluetoothConstants.PARAM_NONE)
                        // Confirmation comes from lock state notifications, not from sending a command.
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { stopOpening(bluetoothError("enviar la apertura", e)) }
                }
            } else if (!isConnecting && !isConnected && busy && timeout != null) {
                stopOpening("Se perdió la conexión. Comprobá el estado en Tedee antes de reintentar.")
            }
        }
    }

    override fun onLockStatusChanged(currentState: Byte, status: Byte) {
        runOnUiThread {
            if (busy && timeout != null && currentState.toInt() == 0x02 && status.toInt() == 0) {
                stopOpening("Cerradura desbloqueada.")
            }
        }
    }
    override fun onNotification(message: ByteArray) = Unit
    override fun onError(throwable: Throwable) {
        runOnUiThread {
            if (timeout != null) stopOpening(bluetoothError(
                if (pendingOpen) "conectar" else "abrir", throwable))
        }
    }

    private fun bluetoothError(step: String, error: Throwable): String {
        // Show types only: SDK exception messages can contain connection or credential data.
        val types = generateSequence(error) { it.cause }.take(4)
            .map { it.javaClass.simpleName }.distinct().toList()
        val hint = when {
            types.any { it == "InvalidSerialNumberError" } -> "Revisá el número de serie de la cerradura."
            types.any { it == "InvalidCertificateError" } -> "Renová el acceso y verificá que Device ID y número de serie sean de la misma cerradura."
            types.any { it == "NoPermissionsError" || it == "SecurityException" } -> "Revisá los permisos de Puerta NFC y el acceso de tu cuenta a la cerradura."
            types.any { it == "DeviceNotFoundError" } -> "Acercate a la cerradura y cerrá la app Tedee. En Android 8–11 activá también Ubicación."
            types.any { it == "BluetoothDisabled" } -> "Activá Bluetooth."
            types.any { it == "LockBusyError" } -> "La cerradura está ocupada. Esperá y cerrá la app Tedee antes de reintentar."
            types.any { it.contains("SignedTime") || it == "NotProvidedSignedTime" } -> "Revisá internet y la fecha y hora automáticas del teléfono."
            else -> "Cerrá Tedee, comprobá el estado de la puerta y volvé a intentar estando cerca."
        }
        return "No se pudo $step por Bluetooth. $hint Código: ${types.joinToString(" / ")}."
    }

    private fun stopOpening(message: String) {
        pendingOpen = false
        timeout?.cancel()
        timeout = null
        opening?.cancel()
        opening = null
        busy = false
        register.isEnabled = true
        show(message)
        try { connection.disconnect() } catch (e: Exception) {
            show("$message ${bluetoothError("desconectar", e)}")
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (quietScan) {
            handleIntent(intent)
            intent = Intent(this, NfcScanActivity::class.java)
            if (!busy && !isFinishing) finish()
            return
        }
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) show("Este Android no tiene lector NFC.")
        else if (!adapter.isEnabled) show("Activá NFC en los ajustes del teléfono.")
        else adapter.enableReaderMode(this, { tag -> runOnUiThread { handleTag(tag) } },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
        handleIntent(intent)
        intent = Intent(this, NfcDoorActivity::class.java)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (resumed) handleIntent(intent) else setIntent(intent)
    }
    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent) {
        if (intent.action !in setOf(NfcAdapter.ACTION_TECH_DISCOVERED, NfcAdapter.ACTION_TAG_DISCOVERED,
                NfcAdapter.ACTION_NDEF_DISCOVERED)) return
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        handleTag(tag)
    }
    override fun onPause() {
        resumed = false
        enrolling = false
        enrollTimeout?.cancel()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        if (timeout != null) stopOpening("Apertura interrumpida. Comprobá el estado antes de reintentar.")
        super.onPause()
    }
    override fun onDestroy() {
        val app = application as ExampleApplication
        if (app.bluetoothErrorListener === errorListener) app.bluetoothErrorListener = null
        try { connection.clear() } catch (_: Exception) {
            // An already closed BLE connection must not crash activity teardown.
        }
        super.onDestroy()
    }
}

/** A transparent NFC entry point; keeps the BLE operation alive until its result. */
class NfcScanActivity : NfcDoorActivity() {
    override val quietScan = true
}
