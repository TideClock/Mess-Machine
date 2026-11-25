# Getting the iPhone app onto the App Store

Nov 24 2026. For the iOS chat and for Tyler. What has to be true before the first TestFlight build and the first
review. Bundle ID: `app.shotgunstudio.messmachine` (the same as Android's applicationId).

## Only Tyler can do these

1. **Join the Apple Developer Program** (99 US dollars a year). [Likely] An individual account shows his own legal
   name on the store; "Shotgun Studio" as the seller name needs an organization account, which needs a legal entity
   and a D-U-N-S number (free, takes days to weeks). Decide this before enrolling: changing an individual account to
   an organization later is a support request.
2. **Register the bundle ID** `app.shotgunstudio.messmachine` and create the app record in App Store Connect.
3. **No Mac needed for builds** if he wants: GitHub's Mac runners can archive and upload to TestFlight. That needs an
   App Store Connect API key (Users and Access, Integrations) saved by Tyler as repository secrets in
   `hubbub0/Mess-Machine-iOS`. Claude cannot make the key or the secrets. Without them, a Mac with Xcode does it.
4. **Store listing text he signs off on**: name, subtitle, description, keywords, support URL and privacy policy URL
   (shotgunstudio.app once he owns it), age rating answers, price.

## The build (iOS chat)

- [Certain] Since April 28 2026, uploads must be built with Xcode 26 or later and the iOS 26 SDK.
- Info.plist: `NSMicrophoneUsageDescription` (Workbench and Auto sample record), `UIBackgroundModes` with `audio`
  (keeps playing with the screen off, as Android's Stop notification does), `ITSAppUsesNonExemptEncryption` = NO
  (only HTTPS, which is exempt).
- `PrivacyInfo.xcprivacy`: declare the required-reason APIs the app and its libraries touch (UserDefaults for
  settings, file timestamps for the mess list). Check Xcode's privacy report on the archive.
- The app icon at 1024 by 1024 with no transparency (from `tools/icons`).

## Review rules that touch this app

- [Certain from the accounts spec] Sign-in is an emailed code, with no Google or Facebook sign-in, so Sign in with
  Apple is not required. If a social sign-in is ever added, Sign in with Apple must come with it.
- Accounts can be deleted from inside the app (`delete-account`, ACCOUNTS-SPEC.md): Apple requires that for any app
  that makes accounts. The iOS Account page must show the same Delete control.
- Shared messes and the Vault are user content: the report and block controls (Send and block) must be on iOS too.
- The bug button posts notes with device details: list "Diagnostics" and "User content" in the App Privacy answers,
  and account email under "Contact info" once accounts are live.
- A reviewer login: the same demo account the Play Store kit uses.

## Screenshots

- iPhone 6.9 inch (1320 by 2868) is the one set required; smaller sizes scale from it.
- iPad 13 inch only if the app runs on iPad. The wide layouts (560 dp and up) already exist on Android, so iPad
  support is close to free once the shared screens land (docs/CMP-PLAN.md).

## Order

1. Shared screens (docs/CMP-PLAN.md steps 2 to 7). Until then the iPhone app is the iOS chat's own SwiftUI build.
2. TestFlight build for Tyler's own phone.
3. Listing, privacy answers, screenshots, review.
