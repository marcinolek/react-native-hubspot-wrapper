require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "ReactNativeHubspotWrapper"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.description  = package["description"]
  s.homepage     = "https://github.com/marcinolek/react-native-hubspot-wrapper"
  s.license      = package["license"]
  s.authors      = "Marcin Olek"
  s.platforms    = { :ios => "15.0" }
  s.source       = { :path => "." }

  s.source_files = "ios/**/*.{h,m,mm,swift}"
  s.private_header_files = "ios/**/*.h"
  s.resource_bundles = {
    "HubspotMobileSDKResources" => ["ios/HubspotMobileSDK/Resources/**/*"]
  }

  if respond_to?(:install_modules_dependencies, true)
    install_modules_dependencies(s)
  else
    s.dependency "React-Core"
  end
end
