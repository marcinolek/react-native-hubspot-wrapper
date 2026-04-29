import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type HubspotProperty = {
  name: string;
  value: string;
};

export interface Spec extends TurboModule {
  initialize(): Promise<void>;
  openChat(chatflow: string, hideBackToInboxButton: boolean): Promise<void>;
  setIdentity(identityToken: string, email: string | null): Promise<void>;
  setProperties(properties: ReadonlyArray<HubspotProperty>): Promise<void>;
  clearUserData(): Promise<void>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('NativeHubspotWrapper');
