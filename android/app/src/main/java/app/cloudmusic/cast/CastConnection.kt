package app.cloudmusic.cast

import android.app.Activity
import android.app.Dialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.media.MediaRouter
import app.cloudmusic.R
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.*
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

data class CastState(val connecting: Boolean = false, val device: String? = null, val error: String? = null)

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context) = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setCastMediaOptions(CastMediaOptions.Builder().setMediaSessionEnabled(false).setNotificationOptions(null).build()).build()
    override fun getAdditionalSessionProviders(context: Context): MutableList<SessionProvider>? = null
}

/** Google Play services are initialized only after the user opens the Cast picker. */
class CastConnection(private val app: Context) {
    private val mutable = MutableStateFlow(CastState())
    val state = mutable.asStateFlow()
    private val sessionFlow = MutableStateFlow<CastSession?>(null)
    val sessions = sessionFlow.asStateFlow()
    private var context: CastContext? = null
    private var loading = false
    private var picker: Dialog? = null
    private val executor by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "cloud-cast-init").apply { isDaemon = true } } }
    private var resumeOnPhone = false
    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) { mutable.value = CastState(connecting = true) }
        override fun onSessionStarted(session: CastSession, id: String) = connected(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) = failed("Не удалось подключить Chromecast. Проверьте Wi-Fi.")
        override fun onSessionEnding(session: CastSession) { }
        override fun onSessionEnded(session: CastSession, error: Int) { sessionFlow.value = null; mutable.value = CastState(error = mutable.value.error) }
        override fun onSessionResuming(session: CastSession, id: String) { mutable.value = CastState(connecting = true) }
        override fun onSessionResumed(session: CastSession, suspended: Boolean) = connected(session)
        override fun onSessionResumeFailed(session: CastSession, error: Int) = failed("Соединение с Chromecast прервано.")
        override fun onSessionSuspended(session: CastSession, reason: Int) { sessionFlow.value = null; mutable.value = CastState(error = "Соединение с Chromecast приостановлено.") }
    }
    private fun connected(session: CastSession) {
        sessionFlow.value = session
        mutable.value = CastState(device = session.castDevice?.friendlyName ?: "Chromecast")
    }
    private fun failed(message: String) { sessionFlow.value = null; mutable.value = CastState(error = message) }
    fun playbackFailed(message: String) { mutable.value = mutable.value.copy(error = message); context?.sessionManager?.endCurrentSession(true) }
    fun returnToPhone() { resumeOnPhone = true; context?.sessionManager?.endCurrentSession(true) }
    fun consumeResumeOnPhone(): Boolean = resumeOnPhone.also { resumeOnPhone = false }
    fun open(host: Context) {
        val activity = activity(host) ?: return
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS) {
            val message = "Для Chromecast нужны Google Play Services. На этом устройстве они недоступны."
            mutable.value = CastState(error = message); Toast.makeText(host, message, Toast.LENGTH_LONG).show(); return
        }
        context?.let { show(activity, it); return }
        if (loading) return
        loading = true; mutable.value = CastState(connecting = true)
        CastContext.getSharedInstance(app, executor).addOnSuccessListener { cast ->
            loading = false; context = cast
            cast.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
            cast.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::connected) ?: run { mutable.value = CastState() }
            if (!activity.isFinishing && !activity.isDestroyed) show(activity, cast)
        }.addOnFailureListener {
            loading = false; failed("Не удалось запустить Chromecast. Проверьте Google Play Services.")
            Toast.makeText(host, mutable.value.error, Toast.LENGTH_LONG).show()
        }
    }
    private fun show(activity: Activity, cast: CastContext) {
        val selector = cast.mergedSelector ?: return
        val router = MediaRouter.getInstance(activity)
        picker?.dismiss()
        val dialog = if (router.selectedRoute.matchesSelector(selector)) MediaRouteControllerDialog(activity, R.style.Theme_CloudMusic_CastDialog)
            else MediaRouteChooserDialog(activity, R.style.Theme_CloudMusic_CastDialog).apply { routeSelector = selector }
        val owner = activity as? LifecycleOwner
        val observer = object : DefaultLifecycleObserver { override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() } }
        owner?.lifecycle?.addObserver(observer)
        dialog.setOnDismissListener { owner?.lifecycle?.removeObserver(observer); if (picker === dialog) picker = null }
        picker = dialog; dialog.show()
    }
    private fun activity(context: Context): Activity? = when (context) { is Activity -> context; is ContextWrapper -> activity(context.baseContext); else -> null }
}
