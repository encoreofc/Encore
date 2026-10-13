package com.encore.app

import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import org.json.JSONObject

sealed interface GoogleResult {
    data class Logged(val token: String, val user: User) : GoogleResult
    data class NewUser(val ticket: String, val email: String?, val name: String?) : GoogleResult
}

object GoogleSignIn {
    fun clientId(): String? {
        val j = Api.call("GET", "/v1/config", auth = false)
        return if (j.isNull("google_client_id")) null else j.getString("google_client_id")
    }

    fun getIdToken(activityCtx: Context, clientId: String, done: (Result<String>) -> Unit) {
        val option = GetSignInWithGoogleOption.Builder(clientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        CredentialManager.create(activityCtx).getCredentialAsync(
            activityCtx,
            request,
            CancellationSignal(),
            ContextCompat.getMainExecutor(activityCtx),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val cred = result.credential
                    if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        try {
                            done(Result.success(GoogleIdTokenCredential.createFrom(cred.data).idToken))
                        } catch (e: Exception) {
                            done(Result.failure(ApiException("google", "Google sign-in failed")))
                        }
                    } else {
                        done(Result.failure(ApiException("google", "Google sign-in failed")))
                    }
                }

                override fun onError(e: GetCredentialException) {
                    done(
                        Result.failure(
                            if (e is GetCredentialCancellationException) ApiException("cancelled", "Cancelled")
                            else ApiException("google", "Google sign-in failed. " + (e.message ?: ""))
                        )
                    )
                }
            }
        )
    }

    private fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.getString(k)

    fun login(idToken: String): GoogleResult {
        val j = Api.call(
            "POST", "/v1/auth/google",
            JSONObject().put("id_token", idToken).put("device", Build.MODEL),
            auth = false
        )
        if (j.optBoolean("new_user")) {
            return GoogleResult.NewUser(j.getString("ticket"), str(j, "email"), str(j, "name"))
        }
        val u = j.getJSONObject("user")
        return GoogleResult.Logged(
            j.getString("token"),
            User(u.getString("id"), u.getString("username"), u.optString("display_name", u.getString("username")))
        )
    }

    fun complete(ticket: String, username: String): Pair<String, User> {
        val j = Api.call(
            "POST", "/v1/auth/google/complete",
            JSONObject().put("ticket", ticket).put("username", username).put("device", Build.MODEL),
            auth = false
        )
        val u = j.getJSONObject("user")
        return j.getString("token") to User(u.getString("id"), u.getString("username"), u.optString("display_name", u.getString("username")))
    }

    fun link(idToken: String): String? {
        val j = Api.call("POST", "/v1/me/google", JSONObject().put("id_token", idToken))
        return str(j, "google_email")
    }

    fun unlink() {
        Api.call("DELETE", "/v1/me/google")
    }
}
