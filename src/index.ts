import NativeHubspotWrapper, { HubspotProperty } from './specs/NativeHubspotWrapper';

export type { HubspotProperty };

export type SetIdentityParams = {
  identityToken: string;
  email?: string | null;
};

function ensureNonEmpty(value: string, fieldName: string): void {
  if (!value || !value.trim()) {
    throw new Error(`\`${fieldName}\` must be a non-empty string.`);
  }
}

const HubspotWrapper = {
  initialize(): Promise<void> {
    return NativeHubspotWrapper.initialize();
  },

  openChat(chatflow: string): Promise<void> {
    ensureNonEmpty(chatflow, 'chatflow');
    return NativeHubspotWrapper.openChat(chatflow);
  },

  setIdentity(params: SetIdentityParams): Promise<void> {
    ensureNonEmpty(params.identityToken, 'identityToken');
    const email = params.email?.trim() || null;
    return NativeHubspotWrapper.setIdentity(params.identityToken, email);
  },

  setProperties(properties: HubspotProperty[]): Promise<void> {
    return NativeHubspotWrapper.setProperties(properties);
  },

  clearUserData(): Promise<void> {
    return NativeHubspotWrapper.clearUserData();
  }
};

export default HubspotWrapper;
