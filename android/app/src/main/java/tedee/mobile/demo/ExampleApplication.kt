package tedee.mobile.demo

import android.app.Application
import com.polidea.rxandroidble2.exceptions.BleException
import io.reactivex.exceptions.UndeliverableException
import io.reactivex.plugins.RxJavaPlugins

class ExampleApplication: Application() {
  @Volatile var bluetoothErrorListener: ((Throwable) -> Unit)? = null

  override fun onCreate() {
    super.onCreate()
    // RxAndroidBle can report errors after a connection subscription is cancelled.
    // The upstream demo installs this handler; without it RxJava terminates the app.
    RxJavaPlugins.setErrorHandler { reported ->
      val error = if (reported is UndeliverableException) reported.cause ?: reported else reported
      val bluetoothFailure = generateSequence(error) { it.cause }.take(4).any {
        it is BleException || it.javaClass.name.startsWith("tedee.mobile.sdk.ble.bluetooth.error.")
      }
      if (bluetoothFailure) {
        bluetoothErrorListener?.invoke(error)
      } else {
        val thread = Thread.currentThread()
        thread.uncaughtExceptionHandler?.uncaughtException(thread, reported)
      }
    }
    // SDK and API traffic must not expose credentials in Logcat.
  }
}
