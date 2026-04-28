#import "RNHubspotWrapper.h"
#import <UserNotifications/UserNotifications.h>
#import "ReactNativeHubspotWrapper-Swift.h"

@implementation RNHubspotWrapper {
  HubspotWrapperImpl *_impl;
}

RCT_EXPORT_MODULE(NativeHubspotWrapper)

- (instancetype)init
{
  self = [super init];
  if (self) {
    _impl = [HubspotWrapperImpl new];
  }
  return self;
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeHubspotWrapperSpecJSI>(params);
}

- (void)initialize:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  NSError *error = nil;
  BOOL didInitialize = [_impl initialize:&error];
  if (didInitialize) {
    resolve(nil);
    return;
  }
  reject(@"INIT_ERROR", @"Failed to initialize HubSpot SDK", error);
}

- (void)openChat:(NSString *)chatflow resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  NSError *error = nil;
  BOOL didOpen = [_impl openChat:chatflow error:&error];
  if (didOpen) {
    resolve(nil);
    return;
  }
  reject(@"OPEN_CHAT_ERROR", @"Failed to open HubSpot chat", error);
}

- (void)setIdentity:(NSString *)identityToken email:(NSString * _Nullable)email resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [_impl setIdentity:identityToken email:email];
  resolve(nil);
}

- (void)setProperties:(NSArray<NSDictionary *> *)properties resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [_impl setProperties:properties];
  resolve(nil);
}

- (void)clearUserData:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  // Important: do not resolve until the impl reports completion. The impl awaits the
  // actual chat-identity cookie deletion before invoking this block. Resolving sooner
  // creates a race where JS-level `await clearUserData()` returns before HubSpot's
  // visitor cookies are gone, and the next `openChat` re-uses the previous identity.
  [_impl clearUserData:^{
    resolve(nil);
  }];
}

@end
