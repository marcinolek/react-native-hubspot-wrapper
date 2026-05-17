#!/usr/bin/env bash

set -euo pipefail

REPO_URL="https://github.com/HubSpot/mobile-chat-sdk-ios.git"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
TARGET_DIR="${ROOT_DIR}/ios/HubspotMobileSDK"
VERSION_FILE="${ROOT_DIR}/HUBSPOT_IOS_SDK_VERSION.json"

resolve_latest_tag() {
  git ls-remote --tags --refs "${REPO_URL}" \
    | awk -F'/' '{print $3}' \
    | sort -V \
    | tail -n 1
}

apply_cocoapods_compat_patches() {
  python3 - "${TARGET_DIR}" <<'PY'
from pathlib import Path
import sys

target_dir = Path(sys.argv[1])

def replace_once(path: Path, old: str, new: str) -> None:
    content = path.read_text()
    if old not in content:
        raise RuntimeError(f"Expected snippet not found in {path}")
    path.write_text(content.replace(old, new, 1))

# 1) Replace SPM-only asset symbol usage in FloatingActionButton.
replace_once(
    target_dir / "Views/Buttons/FloatingActionButton.swift",
    "Image(.genericChatIcon)",
    "Image.hubspotChat",
)

# 2) Replace SPM-only asset/localization usage in TextChatButton.
replace_once(
    target_dir / "Views/Buttons/TextChatButtonChatButton.swift",
    "Image(.genericChatIcon)",
    "Image.hubspotChat",
)
replace_once(
    target_dir / "Views/Buttons/TextChatButtonChatButton.swift",
    'Text("chat.label", bundle: .module)',
    'Text("chat.label", bundle: .hubspotResources)',
)

# 3) Add Bundle helper + shared image helper in HubspotManager.
hubspot_manager = target_dir / "HubspotManager.swift"
content = hubspot_manager.read_text()
old_block = """extension Image {
    /// Exporting chat icon - initially for demo use - but maybe sharing some resources that aren't buttons or views might be needed eventually, if so refactor this
    public static var hubspotChat: Image {
        Image(.genericChatIcon)
    }
}
"""
new_block = """extension Image {
    /// Exporting chat icon - initially for demo use - but maybe sharing some resources that aren't buttons or views might be needed eventually, if so refactor this
    public static var hubspotChat: Image {
        Image("GenericChatIcon", bundle: .hubspotResources)
    }
}

extension Bundle {
    static var hubspotResources: Bundle? {
        // CocoaPods bundles resources into a separate bundle, unlike Swift Package's `Bundle.module`.
        Bundle.main.url(forResource: "HubspotMobileSDKResources", withExtension: "bundle")
            .flatMap { Bundle(url: $0) }
    }
}
"""
if old_block not in content:
    raise RuntimeError("Expected Image extension block was not found in HubspotManager.swift")
hubspot_manager.write_text(content.replace(old_block, new_block, 1))

PY
}

TAG="${1:-}"
if [[ -z "${TAG}" ]]; then
  TAG="$(resolve_latest_tag)"
  if [[ -z "${TAG}" ]]; then
    echo "Could not determine latest tag from ${REPO_URL}" >&2
    exit 1
  fi
fi

TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

echo "Cloning ${REPO_URL} at tag ${TAG}..."
git clone --depth 1 --branch "${TAG}" "${REPO_URL}" "${TMP_DIR}/upstream"

if [[ ! -d "${TMP_DIR}/upstream/Sources/HubspotMobileSDK" ]]; then
  echo "Expected Sources/HubspotMobileSDK was not found in tag ${TAG}" >&2
  exit 1
fi

echo "Updating vendored iOS SDK sources..."
rm -rf "${TARGET_DIR}"
mkdir -p "$(dirname "${TARGET_DIR}")"
cp -R "${TMP_DIR}/upstream/Sources/HubspotMobileSDK" "${TARGET_DIR}"

# Drop upstream Xcode documentation bundle: ~800KB of PNGs and markdown files
# that the podspec does not pick up and that aren't needed by consumers.
rm -rf "${TARGET_DIR}/Documentation.docc"

# MIT requires distributing the upstream license alongside the vendored sources.
UPSTREAM_LICENSE=""
for candidate in LICENSE.txt LICENSE LICENSE.md; do
  if [[ -f "${TMP_DIR}/upstream/${candidate}" ]]; then
    UPSTREAM_LICENSE="${TMP_DIR}/upstream/${candidate}"
    break
  fi
done
if [[ -z "${UPSTREAM_LICENSE}" ]]; then
  echo "Could not find an upstream LICENSE file at tag ${TAG}" >&2
  exit 1
fi
cp "${UPSTREAM_LICENSE}" "${TARGET_DIR}/LICENSE.txt"

echo "Applying CocoaPods compatibility patches..."
apply_cocoapods_compat_patches

UPSTREAM_COMMIT="$(git -C "${TMP_DIR}/upstream" rev-parse HEAD)"

cat > "${VERSION_FILE}" <<EOF
{
  "sourceRepository": "${REPO_URL}",
  "tag": "${TAG}",
  "commit": "${UPSTREAM_COMMIT}"
}
EOF

echo "Updated:"
echo "  - ${TARGET_DIR}"
echo "  - ${VERSION_FILE}"
