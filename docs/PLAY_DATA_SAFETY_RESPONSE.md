# Evidence for Google Play Data safety review

Use only after uploading the0.1.2 Preview AAB (versionCode3). This is a technical response for the two flagged categories, not a pre-filled declaration for every data type. It does not assert that Google has approved the replacement.

## Suggested review response

We investigated the Installed apps and Device or other IDs warnings reported for com.ccs.sprig, versionCode2.

The previous app selector called PackageManager.getInstalledApplications locally, only after the user selected Choose apps. Application labels and package names populated the picker; selected package names were stored in app-private preferences and passed to Android VpnService.Builder.addAllowedApplication. We did not find any path that adds this inventory to network requests, HAR exports, report exports or support intents. VersionCode3 replaces that unnecessarily broad query with an ACTION_MAIN/CATEGORY_LAUNCHER query and deduplicates package entries. The picker explains its local-only purpose.

The getDeviceId references in the shipped versionCode2 DEX belong to android.view.KeyEvent and android.view.MotionEvent, used by AndroidX keyboard/scroll/haptic handling. They are not TelephonyManager device identifiers. Our source/dependency/DEX audit found no Android ID, advertising ID, IMEI, SIM/subscriber identifier or hardware MAC/serial collection path, and no advertising/analytics SDK or developer telemetry endpoint. We have not performed a new on-device packet capture for this investigation, so we do not present static analysis as proof that no arbitrary data can ever leave the device.

We also removed the default AndroidX downloadable emoji-font initializer and set a constant Sprig-Collaborator/1 User-Agent for optional Collaborator API operations to avoid Android's default OS/model/build-bearing header. A local HTTP fixture verified the management requests. An explicit data-handling disclosure now precedes VPN capture, and the privacy policy describes local storage separately from network forwarding, exports, optional services and email support.

Sprig is a network inspection tool: it forwards user-selected apps' requests to their original destinations, and user-started testing tools send configured requests to selected targets. Such user traffic can contain information or identifiers supplied by the user or other apps. We do not claim that Sprig has no network transmissions, or that all Data safety categories can therefore be marked No.

Please review versionCode3 and the attached audit evidence. If Installed apps or persistent Device or other IDs are still identified as transmitted, please provide the destination endpoint, transmitted data field and attributed SDK/API path so we can investigate the specific discrepancy.

## Evidence links

- Source/data-flow and dependency audit: https://github.com/MrmaderatorYT/Sprig/blob/main/docs/PRIVACY_AUDIT.md
- Baseline DEX references and exact APK hash: https://github.com/MrmaderatorYT/Sprig/blob/main/docs/privacy/0.1.1-dex.json
- Replacement DEX references and hash: https://github.com/MrmaderatorYT/Sprig/blob/main/docs/privacy/0.1.2-dex.json
- Replacement release/source/checksums: https://github.com/MrmaderatorYT/Sprig/releases/tag/v0.1.2-preview
- Privacy policy EN/UK: https://mrmaderatoryt.github.io/Sprig/privacy.html

## Console steps

1. Replace the code2 bundle with the signed code3 AAB; a changed local APK does not update the bundle already in Play Console.
2. Set the privacy-policy URL to the correct Sprig policy, and review all active tracks/versions and current Data safety answers. Do not declare an inventory/identifier upload that is not actually implemented just to dismiss the warning.
3. Ensure disclosures cover your actual target/server/support operations. Encryption-in-transit cannot be claimed universally for plaintext HTTP/ws traffic.
4. If the specific warning remains, submit the technical evidence/request for clarification above. Neither this document nor an updated bundle guarantees approval. The Console declaration and review submission remain under the developer's control.
