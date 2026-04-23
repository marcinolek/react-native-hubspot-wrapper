package com.marcinolek.reactnativehubspotwrapper

import com.facebook.react.TurboReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class HubspotWrapperPackage : TurboReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
    return if (name == HubspotWrapperModule.NAME) {
      HubspotWrapperModule(reactContext)
    } else {
      null
    }
  }

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider {
    return ReactModuleInfoProvider {
      mapOf(
        HubspotWrapperModule.NAME to ReactModuleInfo(
          HubspotWrapperModule.NAME,
          HubspotWrapperModule.NAME,
          false,
          false,
          false,
          true
        )
      )
    }
  }
}
