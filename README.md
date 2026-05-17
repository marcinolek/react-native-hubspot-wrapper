# react-native-hubspot-wrapper

TurboModule-only React Native wrapper for the HubSpot Mobile Chat SDK.

> Unofficial. Not affiliated with, endorsed by, or sponsored by HubSpot, Inc.
> "HubSpot" is a trademark of HubSpot, Inc.

## Status

Early release (`0.x`). The public API may evolve before `1.0`.

Currently supported:

- `initialize()`
- `openChat(chatflow, options?)`
- `setIdentity({ identityToken, email? })`
- `setProperties(properties)`
- `clearUserData()`

Not yet implemented (planned):

- Push notifications (FCM token registration on Android, APNs token on iOS,
  deep-linking from a notification into the relevant chat)
- Chat lifecycle events (open / close / message-received callbacks exposed to JS)
- Custom theming hooks beyond what the HubSpot dashboard chatflow already provides

PRs welcome.

## Requirements

- React Native 0.81+
- New Architecture enabled
- iOS 15+
- Android minSdk 26

## Installation

```sh
yarn add react-native-hubspot-wrapper
```

Then install iOS dependencies:

```sh
cd ios && pod install
```

## Configuration

- iOS: include `Hubspot-Info.plist` in your app target.
- Android: include `android/app/src/main/assets/hubspot-info.json`.

## Usage

```ts
import HubspotWrapper from 'react-native-hubspot-wrapper';

await HubspotWrapper.initialize();
await HubspotWrapper.setIdentity({ identityToken: 'token', email: 'user@example.com' });
await HubspotWrapper.setProperties([{ name: 'plan', value: 'pro' }]);
await HubspotWrapper.openChat('support');
```

`openChat` keeps HubSpot's stock conversations navigation visible. Older
versions accepted `hideBackToInboxButton`; the option is still accepted for
compatibility, but it no longer changes the HubSpot UI.

## API

- `initialize(): Promise<void>`
- `openChat(chatflow: string, options?: { hideBackToInboxButton?: boolean }): Promise<void>`
- `setIdentity({ identityToken, email? }): Promise<void>`
- `setProperties(properties): Promise<void>`
- `clearUserData(): Promise<void>`

### `openChat(chatflow, options?)`

Opens the HubSpot chat UI for the provided chatflow.

- `hideBackToInboxButton` is deprecated and ignored.

### `clearUserData()`

Clears the native SDK's current user/session state and waits for HubSpot chat
visitor cookies/data cleanup to finish before resolving. This is intended for
logout or account-switch flows so the next chat session does not reuse the
previous visitor identity.

## iOS SDK source strategy

This package vendors HubSpot iOS SDK source files under `ios/HubspotMobileSDK`.
The files are intentionally committed to git for reproducible CocoaPods builds,
since React Native does not yet integrate Swift Package Manager natively.

Current vendored source metadata is tracked in `HUBSPOT_IOS_SDK_VERSION.json`.
The upstream `LICENSE.txt` is preserved alongside the vendored sources at
`ios/HubspotMobileSDK/LICENSE.txt`, in compliance with the MIT license HubSpot
ships their iOS SDK under.

When React Native adds first-class SwiftPM support, the vendored sources can be
removed and replaced with a `Package.swift` dependency without any consumer-facing
API changes.

## Updating vendored HubSpot iOS SDK

Use the helper script:

```sh
yarn update:hubspot:ios
```

Note: the update script also reapplies a small CocoaPods compatibility patch set
to HubSpot iOS sources (resource bundle + asset/localization access), so the
wrapper remains buildable outside of pure Swift Package Manager integration.

Optional: update to an explicit tag:

```sh
yarn update:hubspot:ios 1.0.7
```

After updating:

1. run `cd ios && pod install` in the consuming app
2. run iOS/Android compile checks
3. commit updated `ios/HubspotMobileSDK` and `HUBSPOT_IOS_SDK_VERSION.json`

## License

This wrapper is released under the [MIT license](./LICENSE).

The vendored HubSpot iOS SDK source under `ios/HubspotMobileSDK/` is also
MIT-licensed by HubSpot, Inc. — see `ios/HubspotMobileSDK/LICENSE.txt`.

The HubSpot Android SDK is not redistributed by this package; it is fetched
from Maven Central by the consuming app under HubSpot's terms.
