import Foundation
import SwiftUI
import UIKit
import WebKit

@objcMembers
public class HubspotWrapperImpl: NSObject {
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

  public func openChat(_ chatflow: String, hideBackToInboxButton _: Bool, error outError: NSErrorPointer) -> Bool {
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

  /// Clear the SDK's in-memory identity/property state and synchronously wait for HubSpot's
  /// visitor-identity cookies to be removed before invoking `completion`.
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
  /// Broader WebKit website-data cleanup is intentionally avoided here. Both
  /// `dataRecords(ofTypes:)` and `removeData(ofTypes:modifiedSince:)` have crashed in
  /// production inside WebKit's `allDataStores()` path on iOS 18 simulators.
  public func clearUserData(_ completion: @escaping () -> Void) {
    Task { @MainActor in
      HubspotManager.shared.clearUserData()
      await Self.deleteHubspotIdentityCookies()
      completion()
    }
  }

  private static func deleteHubspotIdentityCookies() async {
    let cookieStore = WKWebsiteDataStore.default().httpCookieStore
    let matchingCookies = await cookieStore.allCookies().filter {
      hubspotIdentityCookieNames.contains($0.name)
    }
    for cookie in matchingCookies {
      await cookieStore.deleteCookie(cookie)
    }
  }

  private static let hubspotIdentityCookieNames = Set(["hubspotutk", "messagesUtk"])

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
