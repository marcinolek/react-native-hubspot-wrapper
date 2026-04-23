# react-native-hubspot-wrapper

TurboModule-only React Native wrapper for HubSpot mobile chat SDK.

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

## API

- `initialize(): Promise<void>`
- `openChat(chatflow: string): Promise<void>`
- `setIdentity({ identityToken, email? }): Promise<void>`
- `setProperties(properties): Promise<void>`
- `clearUserData(): Promise<void>`

## iOS SDK source strategy

This package vendors HubSpot iOS SDK source files under `ios/HubspotMobileSDK`.
The files are intentionally committed to git for reproducible CocoaPods builds.

Current vendored source metadata is tracked in `HUBSPOT_IOS_SDK_VERSION.json`.

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
