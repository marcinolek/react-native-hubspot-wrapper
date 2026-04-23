# Migration from react-native-hubspot-chat

## Import change

```ts
// before
import HubspotChat from 'react-native-hubspot-chat';

// after
import HubspotWrapper from 'react-native-hubspot-wrapper';
```

## API mapping

- `HubspotChat.init()` -> `HubspotWrapper.initialize()`
- `HubspotChat.open(chatflow)` -> `HubspotWrapper.openChat(chatflow)`
- `HubspotChat.identify(token, email)` -> `HubspotWrapper.setIdentity({ identityToken: token, email })`
- `HubspotChat.setProperties(properties)` -> `HubspotWrapper.setProperties(properties)`
- `HubspotChat.endSession()` -> `HubspotWrapper.clearUserData()`
