package com.marcinolek.reactnativehubspotwrapper

import android.content.Intent
import android.webkit.CookieManager
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.hubspot.mobilesdk.HubspotManager
import com.hubspot.mobilesdk.HubspotWebActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
class HubspotWrapperModule(reactContext: ReactApplicationContext) :
  NativeHubspotWrapperSpec(reactContext) {

  private val appContext = reactContext.applicationContext
  private lateinit var hubspotManager: HubspotManager

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

  override fun openChat(chatflow: String, promise: Promise) {
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
      hubspotManager.setUserIdentity(identityToken, email ?: "")
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
        hubspotManager.logout()
        clearHubspotCookies()
        promise.resolve(null)
      } catch (error: Exception) {
        promise.reject("CLEAR_USER_DATA_ERROR", "Failed to clear HubSpot user data", error)
      }
    }
  }

  private fun clearHubspotCookies() {
    val cookieManager = CookieManager.getInstance()
    val hubspotDomains = listOf("https://app.hubspot.com", "https://app.hubapi.com")

    for (domain in hubspotDomains) {
      val cookiesString = cookieManager.getCookie(domain) ?: continue
      cookiesString.split(";").forEach { cookiePair ->
        val cookieName = cookiePair.trim().split("=").firstOrNull()?.trim() ?: return@forEach
        if (cookieName in COOKIES_TO_DELETE) {
          cookieManager.setCookie(domain, "$cookieName=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
        }
      }
    }

    cookieManager.flush()
  }

  companion object {
    const val NAME = "NativeHubspotWrapper"

    // Mirrors iOS HubspotManager.cookiesToDeleteWhenClearingData — only these cookies
    // are needed to reset chat session state; removing all cookies is too destructive.
    private val COOKIES_TO_DELETE = setOf("hubspotutk", "messagesUtk")
  }
}
