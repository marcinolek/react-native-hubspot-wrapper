import Foundation
import SwiftUI
import UIKit
import WebKit

@objcMembers
public class HubspotWrapperImpl: NSObject {
  /// Substrings used to match `WKWebsiteDataRecord.displayName` (which is the host /
  /// eTLD+1, e.g. `hubspot.com`, `hs-banner.com`, `hsadspixel.net`) for the various
  /// domains the chat widget loads from. Anything matching is wiped on `clearUserData`.
  ///
  /// We deliberately use substrings rather than exact hosts because the widget pulls
  /// resources from a long tail of HubSpot CDNs - `js.hs-banner.com`, `js.hubspot.com`,
  /// `static.hsappstatic.net`, `app-na2.hubspot.com`, `*.hubspotqa.com`, etc. - and any
  /// of those can hold cookies / localStorage / IndexedDB that re-attach a returning
  /// visitor or surface the unsent draft message on the next open.
  private static let chatDataDomainMatches: [String] = [
    "hubspot",
    "hsforms",
    "hs-banner",
    "hs-scripts",
    "hsadspixel",
    "hsappstatic",
    "usemessages",
  ]

  public func initialize(_ outError: NSErrorPointer) -> Bool {
    let semaphore = DispatchSemaphore(value: 0)
    var configureError: NSError?

    Task { @MainActor in
      do {
        try HubspotManager.configure()
      } catch let error {
        configureError = error as NSError
      }
      semaphore.signal()
    }

    semaphore.wait()

    if let configureError {
      outError?.pointee = configureError
      return false
    }
    return true
  }

  public func openChat(_ chatflow: String, error outError: NSErrorPointer) -> Bool {
    var didSucceed = false
    let presentBlock = {
      guard let rootVC = Self.topViewController() else {
        outError?.pointee = NSError(
          domain: "HubspotWrapper",
          code: 1,
          userInfo: [NSLocalizedDescriptionKey: "No root view controller available"]
        )
        return
      }

      let chatView = HubspotChatView(chatFlow: chatflow)
      let hostingController = UIHostingController(rootView: chatView)
      rootVC.present(hostingController, animated: true)
      didSucceed = true
    }

    if Thread.isMainThread {
      presentBlock()
    } else {
      DispatchQueue.main.sync(execute: presentBlock)
    }

    return didSucceed
  }

  public func setIdentity(_ identityToken: String, email: String?) {
    let semaphore = DispatchSemaphore(value: 0)
    Task { @MainActor in
      HubspotManager.shared.setUserIdentity(identityToken: identityToken, email: email ?? "")
      semaphore.signal()
    }
    semaphore.wait()
  }

  public func setProperties(_ properties: [NSDictionary]) {
    var mapped: [String: String] = [:]

    for item in properties {
      guard
        let name = item["name"] as? String,
        let value = item["value"] as? String
      else {
        continue
      }
      mapped[name] = value
    }

    Task {
      await HubspotManager.shared.setChatProperties(data: mapped)
    }
  }

  /// Clear the SDK's in-memory identity/property state and synchronously wait for ALL
  /// HubSpot website data (cookies, localStorage, sessionStorage, IndexedDB, service
  /// workers, etc.) to be removed from the shared `WKWebsiteDataStore` before invoking
  /// `completion`.
  ///
  /// Why we do our own clearing instead of relying on the SDK:
  ///
  /// 1. `HubspotManager.clearUserData()` is synchronous, but the cookie removal it
  ///    performs is dispatched into a fire-and-forget `Task { ... }` that we have no
  ///    handle to. JS code doing
  ///    `await HubspotWrapper.clearUserData(); await HubspotWrapper.openChat(...)`
  ///    races the still-in-flight cookie deletion, so the next chat session re-uses
  ///    the previous visitor identity.
  ///
  /// 2. Even when the SDK's cookie deletion *does* finish, it only removes the two
  ///    visitor-identity cookies (`hubspotutk`, `messagesUtk`). The chat widget also
  ///    persists the unsent draft message and other state in `localStorage` /
  ///    `sessionStorage` / `IndexedDB`. Cookie-only clearing leaves the draft visible
  ///    on the next open, which is exactly the bug we kept seeing.
  ///
  /// We therefore enumerate `WKWebsiteDataStore` records, filter to records whose
  /// host matches a HubSpot domain (see `chatDataDomainMatches`) and remove ALL data
  /// types for those records. We only resolve the JS promise once that's complete.
  public func clearUserData(_ completion: @escaping () -> Void) {
    Task { @MainActor in
      HubspotManager.shared.clearUserData()
      await Self.deleteAllChatWebsiteData()
      completion()
    }
  }

  private static func deleteAllChatWebsiteData() async {
    let store = WKWebsiteDataStore.default()
    let allTypes = WKWebsiteDataStore.allWebsiteDataTypes()
    let records = await store.dataRecords(ofTypes: allTypes)
    let matching = records.filter { record in
      let host = record.displayName.lowercased()
      return chatDataDomainMatches.contains(where: host.contains)
    }
    guard !matching.isEmpty else { return }
    await store.removeData(ofTypes: allTypes, for: matching)
  }

  /// Install a `WKUserScript` on `controller` that hides any "back to inbox" /
  /// conversations-list navigation rendered by the HubSpot chat widget.
  ///
  /// Called from the patched `HubspotChatWebView.WebviewCoordinator.setupScripts()`
  /// (see `update-hubspot-ios-sdk.sh` for the patch that re-applies that hook on
  /// every SDK sync). The actual heuristic-based hide JS lives here in our own
  /// non-vendored file so SDK updates don't churn the implementation.
  ///
  /// We can't rely on a single CSS selector because:
  ///   - HubSpot's widget uses minified/hashed class names that change across
  ///     releases.
  ///   - Parts of the widget render inside shadow roots, which a top-level
  ///     `<style>` doesn't pierce.
  ///   - The button can mount after first paint as the widget hydrates.
  ///
  /// The injected script installs a `MutationObserver` that walks the full tree
  /// (recursing into open shadow roots) and hides anything that *behaves* like a
  /// back button - matched on `aria-label`, `data-test-id`, `title`, `id`, `class`,
  /// and visible text. Re-scans periodically for the first few seconds to cover
  /// late-mounted shadow roots. Injected at document start in every frame.
  @objc public static func installBackButtonHider(on controller: WKUserContentController) {
    let script = WKUserScript(
      source: backButtonHiderJS,
      injectionTime: .atDocumentStart,
      forMainFrameOnly: false
    )
    controller.addUserScript(script)
  }

  /// Heuristic-based hider for the chat widget's back/inbox button. Designed to
  /// work both as a `WKUserScript` (per-frame at `.atDocumentStart`) and as a
  /// one-shot `evaluateJavascript` payload from the Android side - hence the
  /// `walkAllFrames` / `bootstrap` split with the per-document install guard.
  /// Keep this string in lockstep with `HubspotBackButtonHider.BACK_BUTTON_HIDER_JS`
  /// in the Android wrapper. Insert spaces at camelCase boundaries and collapse
  /// underscores/dashes to spaces so word-boundary matching catches class names
  /// like `ConversationView__BackButton`. (In JS regex, `_` is a word char, so
  /// without this `\\bback\\b` would fail against `..._back_...`.)
  private static let backButtonHiderJS: String = """
    (function() {
      function bootstrap(win) {
        var doc;
        try { doc = win.document; } catch (_) { return; }
        if (!doc || doc.__rnHubspotHiderInstalled) return;
        doc.__rnHubspotHiderInstalled = true;

        var BACK_PATTERN = /\\b(back|inbox|previous|conversations)\\b/i;

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

  private static func topViewController(
    base: UIViewController? = UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
      .flatMap { $0.windows }
      .first(where: \.isKeyWindow)?
      .rootViewController
  ) -> UIViewController? {
    if let navigationController = base as? UINavigationController {
      return topViewController(base: navigationController.visibleViewController)
    }
    if let tabBarController = base as? UITabBarController,
       let selected = tabBarController.selectedViewController {
      return topViewController(base: selected)
    }
    if let presented = base?.presentedViewController {
      return topViewController(base: presented)
    }
    return base
  }
}
