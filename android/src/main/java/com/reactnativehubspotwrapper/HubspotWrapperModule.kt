package com.marcinolek.reactnativehubspotwrapper

import android.app.Application
import android.content.Intent
import android.util.Log
import android.webkit.CookieManager
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.hubspot.mobilesdk.HubspotManager
import com.hubspot.mobilesdk.HubspotWebActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class HubspotWrapperModule(reactContext: ReactApplicationContext) :
  NativeHubspotWrapperSpec(reactContext) {

  private val appContext = reactContext.applicationContext
  private lateinit var hubspotManager: HubspotManager

  init {
    // Install the chat script bridge as soon as the wrapper module is created.
    // The bridge only does work when `HubspotWebActivity` actually resumes, so this
    // is a cheap one-time `Application.ActivityLifecycleCallbacks` registration.
    // See `HubspotChatScriptInstaller` for why this lives outside the activity itself.
    (appContext as? Application)?.let { HubspotChatScriptInstaller.installOnce(it) }
  }

  override fun getName(): String = NAME

  override fun initialize(promise: Promise) {
    try {
      hubspotManager = HubspotManager.getInstance(appContext)
      hubspotManager.enableLogs()
      hubspotManager.configure()
      promise.resolve(null)
    } catch (error: Exception) {
      promise.reject("INIT_ERROR", "Failed to initialize HubSpot SDK", error)
    }
  }

  @Suppress("UNUSED_PARAMETER")
  override fun openChat(chatflow: String, hideBackToInboxButton: Boolean, promise: Promise) {
    try {
      val intent = Intent(appContext, HubspotWebActivity::class.java)
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      intent.putExtra("chatflow", chatflow)
      appContext.startActivity(intent)
      promise.resolve(null)
    } catch (error: Exception) {
      promise.reject("OPEN_CHAT_ERROR", "Failed to open HubSpot chat", error)
    }
  }

  override fun setIdentity(identityToken: String, email: String?, promise: Promise) {
    try {
      hubspotManager.setUserIdentity(email ?: "", identityToken)
      promise.resolve(null)
    } catch (error: Exception) {
      promise.reject("IDENTITY_ERROR", "Failed to set HubSpot identity", error)
    }
  }

  override fun setProperties(properties: ReadableArray, promise: Promise) {
    try {
      val mapped = mutableMapOf<String, String>()
      for (i in 0 until properties.size()) {
        val item = properties.getMap(i) ?: continue
        val name = item.getString("name") ?: continue
        val value = item.getString("value") ?: ""
        mapped[name] = value
      }
      hubspotManager.setChatProperties(mapped)
      promise.resolve(null)
    } catch (error: Exception) {
      promise.reject("SET_PROPERTIES_ERROR", "Failed to set HubSpot chat properties", error)
    }
  }

  override fun clearUserData(promise: Promise) {
    CoroutineScope(Dispatchers.Main).launch {
      try {
        Log.i(TAG, "clearUserData: invoked")
        hubspotManager.logout()
        clearHubspotSessionCookies()
        Log.i(TAG, "clearUserData: done")
        promise.resolve(null)
      } catch (error: Exception) {
        Log.e(TAG, "clearUserData: failed", error)
        promise.reject("CLEAR_USER_DATA_ERROR", "Failed to clear HubSpot user data", error)
      }
    }
  }

  /**
   * Mirror the iOS SDK's `cookiesToDeleteWhenClearingData` behavior: only drop the cookies that
   * tie the embedded chat WebView to a previous HubSpot visitor identity (`hubspotutk`,
   * `messagesUtk`). Anything else - Cloudflare bot-management cookies, future preference cookies,
   * etc. - is intentionally preserved so the next chat session starts with a fresh identity but
   * doesn't pay any unrelated cold-start tax.
   *
   * Implementation notes for Android:
   *
   * - `CookieManager.getCookie(url)` only returns `name=value` pairs - no `Domain`/`Path`
   *   metadata - so to delete a cookie reliably we have to overwrite it with an expired date
   *   under every plausible scope (host-only, exact host `Domain`, parent `Domain`). Writes
   *   whose `Domain` doesn't match an existing cookie are silently rejected by the cookie store,
   *   so issuing all three is safe.
   *
   * - The chat URL's host is computed from `hubspot-info.json` using the same rule as the SDK's
   *   internal `Hublet`/`Environment` value classes (which are package-private):
   *
   *     `https://app[-<hublet>].hubspot[qa].com/`
   *
   *   For example: hublet `na2` + env `prod` -> `https://app-na2.hubspot.com/`.
   *   Hublet `na1` is special-cased to `app.hubspot.com`. Env `qa` adds the `qa` suffix
   *   to the apex (`hubspotqa.com`).
   *
   * - If `HubspotManager` hasn't been configured yet, we fall back to `removeAllCookies()` so
   *   we never leak chat identity even in misconfigured states.
   *
   * Caveat (matches iOS): clearing `messagesUtk` resets the visitor identity, which causes
   * HubSpot's chat embed to re-show its cookie consent banner on the next session. This is by
   * design on HubSpot's side - their chat treats every new visitor id as a new visitor that
   * must consent. There is no way to keep both "fresh chat" and "no consent reprompt" without
   * also leaving the previous conversation thread visible to the user.
   */
  private suspend fun clearHubspotSessionCookies() {
    val hubletId = runCatching { hubspotManager.getHublet() }.getOrNull()
    val environment = runCatching { hubspotManager.getEnvironment() }.getOrNull()

    if (hubletId.isNullOrEmpty() || environment.isNullOrEmpty()) {
      Log.w(TAG, "clearUserData: missing hublet/environment, falling back to removeAllCookies()")
      clearAllWebViewCookies()
      return
    }

    val cookieManager = CookieManager.getInstance()
    val urlSuffix = if (environment.equals("qa", ignoreCase = true)) "qa" else ""
    val rootDomain = "hubspot$urlSuffix.com"
    val appsSubDomain = if (hubletId.equals("na1", ignoreCase = true)) "app" else "app-$hubletId"
    val chatHost = "$appsSubDomain.$rootDomain"
    val chatUrl = "https://$chatHost/"

    val existingCookieNames = cookieManager.getCookie(chatUrl).orEmpty()
      .split(";")
      .mapNotNull { it.substringBefore("=").trim().takeIf(String::isNotEmpty) }
      .toSet()

    val toDelete = COOKIES_TO_CLEAR.filter { it in existingCookieNames }
    if (toDelete.isEmpty()) {
      Log.i(TAG, "clearUserData: no chat-identity cookies present at $chatHost, nothing to delete")
      return
    }

    Log.i(TAG, "clearUserData: deleting cookies=$toDelete on $chatHost")
    val expiry = "Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT"
    toDelete.forEach { name ->
      cookieManager.setCookie(chatUrl, "$name=; $expiry")
      cookieManager.setCookie(chatUrl, "$name=; Domain=$chatHost; $expiry")
      cookieManager.setCookie(chatUrl, "$name=; Domain=.$rootDomain; $expiry")
    }

    suspendCancellableCoroutine<Unit> { continuation ->
      cookieManager.flush()
      continuation.resume(Unit)
    }
  }

  private suspend fun clearAllWebViewCookies() {
    suspendCancellableCoroutine<Unit> { continuation ->
      val cookieManager = CookieManager.getInstance()
      cookieManager.removeAllCookies {
        cookieManager.flush()
        continuation.resume(Unit)
      }
    }
  }

  companion object {
    const val NAME = "NativeHubspotWrapper"

    private const val TAG = "HubspotWrapper"

    /**
     * Cookies that bind the embedded chat WebView to a specific HubSpot visitor identity.
     * Matches the iOS SDK's `cookiesToDeleteWhenClearingData`.
     * See https://knowledge.hubspot.com/privacy-and-consent/what-cookies-does-hubspot-set-in-a-visitor-s-browser
     */
    private val COOKIES_TO_CLEAR = listOf("hubspotutk", "messagesUtk")
  }
}
