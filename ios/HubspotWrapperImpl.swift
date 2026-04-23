import Foundation
import SwiftUI
import UIKit

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

  public func clearUserData() {
    Task {
      await HubspotManager.shared.clearUserData()
    }
  }

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
