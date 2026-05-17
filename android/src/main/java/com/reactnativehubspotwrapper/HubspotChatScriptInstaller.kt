package com.marcinolek.reactnativehubspotwrapper

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import org.json.JSONObject

/**
 * Installs the small JavaScript bridge we need inside HubSpot's compiled
 * [HubspotWebActivity], which cannot be modified directly from the wrapper.
 *
 * HubSpot Android SDK 1.0.8 uploads chat properties only after its native JS
 * bridge receives a conversation id. The bundled script forwards
 * `userSelectedThread`, but not `conversationStarted`, so newly-created threads
 * never trigger the metadata POST. This installer forwards that one event
 * without changing HubSpot's visible chat UI.
 */
internal object HubspotChatScriptInstaller {
  private const val TAG = "HubspotWrapper"
  private const val JS_BRIDGE_NAME = "rnHubspotWrapper"

  /**
   * The fully-qualified name of HubSpot's chat activity in the AAR. Matched by
   * string so we don't have to import / depend on it for compile-time resolution.
   */
  private const val WEB_ACTIVITY_NAME = "com.hubspot.mobilesdk.HubspotWebActivity"

  /** How often we re-fire `evaluateJavascript` to catch late-loaded iframes. */
  private const val RESCAN_INTERVAL_MS = 250L

  /**
   * Total time we keep re-firing after the activity resumes. The chat iframe
   * normally appears within ~2s; we give a generous margin and then stop to
   * avoid burning CPU forever if the user leaves the chat open for a long time.
   */
  private const val MAX_SCAN_DURATION_MS = 10_000L

  @Volatile
  private var installed = false

  private val handler = Handler(Looper.getMainLooper())
  private val scheduledInjections = WeakHashMap<WebView, Runnable>()
  private val conversationBridges = WeakHashMap<WebView, ConversationBridge>()

  @Synchronized
  fun installOnce(app: Application) {
    if (installed) return
    installed = true

    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      override fun onActivityResumed(activity: Activity) {
        if (activity.javaClass.name != WEB_ACTIVITY_NAME) return
        val webView = findWebView(activity.window.decorView)
        if (webView == null) {
          Log.w(TAG, "installChatScripts: WebView not found in $WEB_ACTIVITY_NAME view tree")
          return
        }
        Log.i(TAG, "installChatScripts: scheduling conversation bridge on $webView")
        installNativeBridge(webView)
        scheduleScriptInjection(webView)
      }

      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
      override fun onActivityStarted(activity: Activity) {}
      override fun onActivityPaused(activity: Activity) {
        if (activity.javaClass.name == WEB_ACTIVITY_NAME) {
          cancelScriptInjection(findWebView(activity.window.decorView))
        }
      }
      override fun onActivityStopped(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {
        if (activity.javaClass.name == WEB_ACTIVITY_NAME) {
          val webView = findWebView(activity.window.decorView)
          cancelScriptInjection(webView)
          if (webView != null) {
            conversationBridges.remove(webView)
          }
        }
      }
    })
  }

  private fun findWebView(view: View?): WebView? {
    if (view is WebView) return view
    if (view is ViewGroup) {
      for (i in 0 until view.childCount) {
        val found = findWebView(view.getChildAt(i))
        if (found != null) return found
      }
    }
    return null
  }

  private fun scheduleScriptInjection(webView: WebView) {
    cancelScriptInjection(webView)

    val start = System.currentTimeMillis()
    val runnable = object : Runnable {
      override fun run() {
        try {
          webView.evaluateJavascript(CONVERSATION_STARTED_BRIDGE_JS, null)
        } catch (error: Exception) {
          Log.w(TAG, "evaluateJavascript failed", error)
        }
        if (System.currentTimeMillis() - start < MAX_SCAN_DURATION_MS) {
          handler.postDelayed(this, RESCAN_INTERVAL_MS)
        } else {
          scheduledInjections.remove(webView)
        }
      }
    }
    scheduledInjections[webView] = runnable
    handler.post(runnable)
  }

  private fun installNativeBridge(webView: WebView) {
    if (conversationBridges.containsKey(webView)) return
    val bridge = ConversationBridge(WeakReference(webView))
    conversationBridges[webView] = bridge
    webView.addJavascriptInterface(bridge, JS_BRIDGE_NAME)
  }

  private fun cancelScriptInjection(webView: WebView?) {
    if (webView == null) return
    val runnable = scheduledInjections.remove(webView) ?: return
    handler.removeCallbacks(runnable)
  }

  private fun forwardConversationIdToNativeApp(webView: WebView, conversationId: String) {
    val quotedId = JSONObject.quote(conversationId)
    handler.post {
      try {
        webView.evaluateJavascript(
          """
            (function() {
              if (window.nativeApp && typeof window.nativeApp.postConversationId === 'function') {
                window.nativeApp.postConversationId($quotedId);
              }
            })();
          """.trimIndent(),
          null
        )
      } catch (error: Exception) {
        Log.w(TAG, "postConversationId bridge failed", error)
      }
    }
  }

  private class ConversationBridge(private val webViewRef: WeakReference<WebView>) {
    @JavascriptInterface
    fun postConversationId(conversationId: String?) {
      val id = conversationId?.takeIf { it.isNotBlank() } ?: return
      val webView = webViewRef.get() ?: return
      forwardConversationIdToNativeApp(webView, id)
    }
  }

  private const val CONVERSATION_STARTED_BRIDGE_JS = """
    (function() {
      function bootstrap(win) {
        var doc;
        try { doc = win.document; } catch (_) { return; }
        if (!doc) return;

        if (doc.__rnHubspotConversationStartedBridgeInstalled) return;
        doc.__rnHubspotConversationStartedBridgeInstalled = true;

        function extractConversationId(payload) {
          try {
            var conversation = payload && payload.conversation;
            var id = conversation && (
              conversation.conversationId ||
              conversation.threadId ||
              conversation.id
            );
            if (id === undefined || id === null || id === '') return null;
            return String(id);
          } catch (_) {
            return null;
          }
        }

        function postConversationId(payload) {
          var id = extractConversationId(payload);
          if (!id) return;
          try {
            if (win.rnHubspotWrapper && typeof win.rnHubspotWrapper.postConversationId === 'function') {
              win.rnHubspotWrapper.postConversationId(id);
            }
          } catch (_) {}
        }

        function configureHubspotConversations() {
          if (!win.HubSpotConversations) return;
          try {
            win.HubSpotConversations.on('conversationStarted', postConversationId);
          } catch (_) {}
        }

        if (win.HubSpotConversations) {
          configureHubspotConversations();
        } else if (Array.isArray(win.hsConversationsOnReady)) {
          win.hsConversationsOnReady.push(configureHubspotConversations);
        } else {
          win.hsConversationsOnReady = [configureHubspotConversations];
        }
      }

      function walkAllFrames(win) {
        if (!win) return;
        bootstrap(win);
        try {
          for (var i = 0; i < win.frames.length; i++) {
            walkAllFrames(win.frames[i]);
          }
        } catch (_) {}
      }

      walkAllFrames(window);
    })();
  """
}
