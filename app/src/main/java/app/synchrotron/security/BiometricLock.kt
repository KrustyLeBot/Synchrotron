package app.synchrotron.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun biometricLock(c: Context) = p(c).getBoolean("biometric_lock", false)
    fun setBiometricLock(c: Context, v: Boolean) = p(c).edit().putBoolean("biometric_lock", v).apply()
}

/** Fingerprint / face, with the device PIN as fallback (API 30+; below, biometrics only). */
object BiometricLock {
    private fun authenticators(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 30) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK

    fun available(context: Context) =
        BiometricManager.from(context).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(activity: FragmentActivity, title: String = "Déverrouiller Synchrotron", onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(authenticators())
            .apply { if (android.os.Build.VERSION.SDK_INT < 30) setNegativeButtonText("Annuler") }
            .build()
        prompt.authenticate(info)
    }
}
