package com.phonecapture.tracker.capture.cloud

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.phonecapture.tracker.capture.diagnostics.DiagnosticLogger
import com.phonecapture.tracker.core.model.DiagnosticType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

data class CloudUser(val uid: String, val email: String?)

sealed interface AuthResult {
    data class Success(val user: CloudUser?) : AuthResult
    data class Failure(val message: String) : AuthResult
}

/**
 * Single access point to Firebase. When the app was built without a google-services.json file,
 * [isConfigured] is false and the whole app runs in local-only mode: no network calls, no account.
 */
class FirebaseGateway(context: Context, private val diagnostics: DiagnosticLogger) {
    private val appContext = context.applicationContext

    val isConfigured: Boolean by lazy {
        try {
            FirebaseApp.getApps(appContext).isNotEmpty() || FirebaseApp.initializeApp(appContext) != null
        } catch (e: Exception) {
            false
        }
    }

    private val _user = MutableStateFlow<CloudUser?>(null)
    val user: StateFlow<CloudUser?> = _user.asStateFlow()
    private var listening = false

    val auth: FirebaseAuth? get() = if (isConfigured) FirebaseAuth.getInstance() else null
    val storage: FirebaseStorage? get() = if (isConfigured) FirebaseStorage.getInstance() else null
    val firestore: FirebaseFirestore? get() = if (isConfigured) FirebaseFirestore.getInstance() else null

    @Synchronized
    fun startListening() {
        if (listening || !isConfigured) return
        listening = true
        val firebaseAuth = FirebaseAuth.getInstance()
        _user.value = firebaseAuth.currentUser?.let { CloudUser(it.uid, it.email) }
        firebaseAuth.addAuthStateListener { a -> _user.value = a.currentUser?.let { CloudUser(it.uid, it.email) } }
    }

    fun currentUid(): String? = auth?.currentUser?.uid

    suspend fun signIn(email: String, password: String): AuthResult = authCall {
        val result = requireAuth().signInWithEmailAndPassword(email.trim(), password).await()
        AuthResult.Success(result.user?.let { CloudUser(it.uid, it.email) })
    }

    suspend fun register(email: String, password: String): AuthResult = authCall {
        val result = requireAuth().createUserWithEmailAndPassword(email.trim(), password).await()
        AuthResult.Success(result.user?.let { CloudUser(it.uid, it.email) })
    }

    suspend fun sendPasswordReset(email: String): AuthResult = authCall {
        requireAuth().sendPasswordResetEmail(email.trim()).await()
        AuthResult.Success(null)
    }

    /** Needed before deleting the account (Firebase requires a recent sign-in). */
    suspend fun reauthenticate(password: String): AuthResult = authCall {
        val current = requireAuth().currentUser ?: return@authCall AuthResult.Failure("Not signed in.")
        val email = current.email ?: return@authCall AuthResult.Failure("This account has no e-mail address.")
        current.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
        AuthResult.Success(CloudUser(current.uid, current.email))
    }

    fun signOut() {
        auth?.signOut()
    }

    private fun requireAuth(): FirebaseAuth = auth ?: throw IllegalStateException(NOT_CONFIGURED)

    private suspend fun authCall(block: suspend () -> AuthResult): AuthResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val message = describe(e)
        // Never log the e-mail address or password.
        diagnostics.log(DiagnosticType.FIREBASE_AUTH_FAILURE, "Cloud account action failed", message)
        AuthResult.Failure(message)
    }

    fun describe(e: Exception): String = when (e) {
        is FirebaseAuthWeakPasswordException -> "The password is too weak. Use at least 6 characters."
        is FirebaseAuthInvalidCredentialsException -> "Wrong e-mail address or password."
        is FirebaseAuthInvalidUserException -> "No active account exists for this e-mail address."
        is FirebaseAuthUserCollisionException -> "An account with this e-mail address already exists."
        is FirebaseAuthRecentLoginRequiredException -> "Please enter your password again to confirm."
        is FirebaseNetworkException -> "No internet connection."
        is FirebaseTooManyRequestsException -> "Too many attempts. Try again later."
        is IllegalStateException -> e.message ?: NOT_CONFIGURED
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        const val NOT_CONFIGURED =
            "Cloud sync is not set up in this build (no google-services.json). The app works in local-only mode."
    }
}
