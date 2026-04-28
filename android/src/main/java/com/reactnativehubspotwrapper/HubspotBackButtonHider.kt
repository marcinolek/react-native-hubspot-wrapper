package com.marcinolek.reactnativehubspotwrapper

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

/**
 * Hides the HubSpot chat widget's "back to inbox" / conversations-list navigation
 * inside [HubspotWebActivity] (which lives in HubSpot's compiled `.aar` and which
 * we therefore can't modify directly).
 *
 * Approach:
 *
 * 1. We register a single [Application.ActivityLifecycleCallbacks] from the wrapper
 *    module's init.
 * 2. Each time `com.hubspot.mobilesdk.HubspotWebActivity` resumes, we depth-first
 *    walk its decor view to find the `WebView`.
 * 3. We `evaluateJavascript` a heuristic-based hider that walks all same-origin
 *    sub-frames (the chat UI itself lives in an iframe on `app-na2.hubspot.com`)
 *    and hides any `<button>`/`<a>`/`role="button"` whose accessible label,
 *    `data-test-id`, title, id, class or short visible text normalizes (camelCase
 *    -> spaces, `_-` -> spaces, lowercase) to something matching
 *    `\b(back|inbox|previous|conversations)\b`. A `MutationObserver` is then
 *    attached so late-mounted buttons get hidden too.
 * 4. We re-fire `evaluateJavascript` periodically for a few seconds so the iframe
 *    walk catches the chat iframe as soon as it mounts (it's loaded asynchronously
 *    by HubSpot's `project.js` bundle after the activity opens).
 *
 * The implementation is intentionally identical in spirit to the iOS hider in
 * `HubspotWrapperImpl.swift` so the same JS works on both platforms.
 */
internal object HubspotBackButtonHider {
  private const val TAG = "HubspotWrapper"

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

  @Synchronized
  fun installOnce(app: Application) {
    if (installed) return
    installed = true

    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      override fun onActivityResumed(activity: Activity) {
        if (activity.javaClass.name != WEB_ACTIVITY_NAME) return
        val webView = findWebView(activity.window.decorView)
        if (webView == null) {
          Log.w(TAG, "installBackButtonHider: WebView not found in $WEB_ACTIVITY_NAME view tree")
          return
        }
        Log.i(TAG, "installBackButtonHider: scheduling JS injection on $webView")
        scheduleHiderInjection(webView)
      }

      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
      override fun onActivityStarted(activity: Activity) {}
      override fun onActivityPaused(activity: Activity) {}
      override fun onActivityStopped(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {}
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

  private fun scheduleHiderInjection(webView: WebView) {
    val handler = Handler(Looper.getMainLooper())
    val start = System.currentTimeMillis()
    val runnable = object : Runnable {
      override fun run() {
        try {
          webView.evaluateJavascript(BACK_BUTTON_HIDER_JS, null)
        } catch (error: Exception) {
          Log.w(TAG, "evaluateJavascript failed", error)
        }
        if (System.currentTimeMillis() - start < MAX_SCAN_DURATION_MS) {
          handler.postDelayed(this, RESCAN_INTERVAL_MS)
        }
      }
    }
    handler.post(runnable)
  }

  /**
   * Heuristic-based hider for the chat widget's back/inbox button. Identical in
   * shape to the iOS version in `HubspotWrapperImpl.swift`. Designed to be safe
   * to invoke many times: a `__rnHubspotHiderInstalled` per-document flag short-
   * circuits re-installation, while the periodic re-fire from
   * [scheduleHiderInjection] still allows newly-mounted iframes to be picked up.
   */
  private const val BACK_BUTTON_HIDER_JS = """
    (function() {
      function bootstrap(win) {
        var doc;
        try { doc = win.document; } catch (_) { return; }
        if (!doc || doc.__rnHubspotHiderInstalled) return;
        doc.__rnHubspotHiderInstalled = true;

        var BACK_PATTERN = /\b(back|inbox|previous|conversations)\b/i;

        function normalize(s) {
          if (!s) return '';
          return String(s)
            .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
            .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
            .replace(/[_-]+/g, ' ')
            .toLowerCase();
        }

        function looksLikeBackControl(el) {
          if (!el || el.nodeType !== 1) return false;
          if (el.getAttribute('data-rn-hubspot-wrapper-hidden') === '1') return false;
          var tag = el.tagName;
          if (tag !== 'BUTTON' && tag !== 'A' && el.getAttribute('role') !== 'button') return false;
          var attrs = [
            el.getAttribute('aria-label'),
            el.getAttribute('data-test-id'),
            el.getAttribute('data-test'),
            el.getAttribute('title'),
            el.getAttribute('id'),
            el.getAttribute('class')
          ];
          for (var i = 0; i < attrs.length; i++) {
            if (attrs[i] && BACK_PATTERN.test(normalize(attrs[i]))) return true;
          }
          var text = (el.textContent || '').trim();
          if (text && text.length <= 32 && BACK_PATTERN.test(normalize(text))) return true;
          return false;
        }

        function hide(el) {
          try {
            el.setAttribute('data-rn-hubspot-wrapper-hidden', '1');
            el.style.setProperty('display', 'none', 'important');
            el.style.setProperty('visibility', 'hidden', 'important');
            el.style.setProperty('pointer-events', 'none', 'important');
          } catch (_) {}
        }

        // Hide `el`, then walk up to a few ancestors and also hide any wrapper
        // that becomes empty (no remaining unhidden element children) as a
        // result. HubSpot's chat header has a back-button slot whose padding
        // / min-width / flex-basis stays around even when the inner button is
        // `display:none`, which shows up as the avatar shifting right after the
        // first message is sent. Capping at depth 3 keeps us inside the header.
        function hideUpwards(el) {
          hide(el);
          var current = el;
          for (var depth = 0; depth < 3; depth++) {
            var parent = current.parentElement;
            if (!parent) break;
            if (parent === doc.documentElement || parent === doc.body) break;
            var visibleCount = 0;
            var children = parent.children;
            for (var i = 0; i < children.length; i++) {
              if (children[i].getAttribute('data-rn-hubspot-wrapper-hidden') !== '1') {
                visibleCount++;
              }
            }
            if (visibleCount > 0) break;
            hide(parent);
            current = parent;
          }
        }

        var observed = new WeakSet();
        function scanRoot(root) {
          if (!root) return;
          try {
            var nodes = root.querySelectorAll('button, a, [role="button"]');
            for (var i = 0; i < nodes.length; i++) {
              if (looksLikeBackControl(nodes[i])) hideUpwards(nodes[i]);
            }
            var all = root.querySelectorAll('*');
            for (var j = 0; j < all.length; j++) {
              var sr = all[j].shadowRoot;
              if (sr) {
                scanRoot(sr);
                attachObserver(sr);
              }
            }
          } catch (_) {}
        }

        function attachObserver(root) {
          if (!root || observed.has(root)) return;
          observed.add(root);
          try {
            var mo = new MutationObserver(function() { scanRoot(root); });
            mo.observe(root, { childList: true, subtree: true, attributes: true, attributeFilter: ['aria-label', 'data-test-id', 'data-test', 'title', 'class'] });
          } catch (_) {}
        }

        function go() {
          scanRoot(doc);
          attachObserver(doc.documentElement || doc);
        }

        if (doc.readyState === 'loading') {
          doc.addEventListener('DOMContentLoaded', go, { once: true });
        } else {
          go();
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
