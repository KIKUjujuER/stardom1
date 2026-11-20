# Privacy Policy — WhereQ-DOSBox

**Effective date:** 18 November 2026
**Applies to:** WhereQ-DOSBox for Android, package `com.whereq.dosbox`, and
every build published under that package name.
**Developer:** WhereQ — <googol.zhang@gmail.com>
**This document:** [`docs/PRIVACY.md`](https://github.com/whereq/whereq-dosbox/blob/main/docs/PRIVACY.md)
**Source code and licences:** <https://github.com/whereq/whereq-dosbox>

This is the privacy policy for **WhereQ-DOSBox**, the Android port of DOSBox.
The app links to this page from the footer of its launcher, and the release
page for the app points at the copy in the repository.

WhereQ-DOSBox is free and open-source software. The licence it is distributed
under, and the notices that go with it, live in the repository next to this
document — see [COPYING](../COPYING) for the GNU General Public License
version 2 and [NOTICE](../NOTICE) for the attribution notices. Nothing about
the licence says anything about your data; this document does.

## The short version

WhereQ-DOSBox **does not collect, transmit, share or sell any data**. It has
no network access at all: the app does not request the `INTERNET` permission,
so it cannot send anything anywhere even if it wanted to. Everything the app
writes stays on your device, in the place you chose for it.

## What the app stores, and where

WhereQ-DOSBox writes only to your device's own storage:

| What | Where | Why |
|---|---|---|
| Installed game bundles | the app's private storage | the launcher unpacks a `.zip` bundle you picked and keeps the game there |
| Save-state slots | the app's private storage | the snapshot of a running DOS machine |
| A mirror of the save states | **the folder you picked** (Config → Saves folder) | so your saves survive a reinstall, a new phone or an uninstall |
| The emulator configuration | the app's private storage | the DOSBox configuration and the mapper settings |

The **durable saves folder** is the one place the app writes outside its own
private storage. It is a folder *you* chose on your phone, through the
system's own folder picker. WhereQ-DOSBox only writes save files into it, and
it never reads, uploads or otherwise touches anything else in it. If you
delete the app, the folder and everything in it stays exactly where it was —
that is the point of it.

## What the app does not do

* It does not collect personal or sensitive user data.
* It does not transmit anything off the device. There is no network
  permission, no analytics, no crash reporting, no advertising SDK and no
  third-party service of any kind in the build.
* It does not share data with anyone, and it does not sell anything.
* It does not show ads.
* It does not read your contacts, messages, location, photos, media library
  or device identifiers.
* It does not require an account, and there is no account to delete.

## The permissions the app asks for

| Permission | Why |
|---|---|
| `VIBRATE` | the virtual keyboard gives haptic feedback when a key is pressed |
| `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` | the emulator keeps running while you switch to another app, so you come back to the exact game state — DOSBox has no save-state of its own |
| `POST_NOTIFICATIONS` | the ongoing notification that belongs to that foreground service (Android 13 and newer) |

No permission is used to read data about you. The app is granted access to
folders only through the system's own file picker, and only to the folder you
select there.

## Children

WhereQ-DOSBox is not directed at children and is not part of a children's app
category. It collects no data from anyone, children included.

## Third parties

The app bundles no third-party SDK that collects data. Its only runtime
dependencies are the Kotlin standard library and AndroidX support libraries,
which do not collect or transmit anything.

The emulator core is DOSBox, which is free software under the GNU General
Public License version 2 — see [COPYING](../COPYING) and
[NOTICE](../NOTICE).

## Data retention and deletion

Because no data is collected, there is nothing for us to retain, back up or
delete on our side.

On your side:

* **Uninstalling WhereQ-DOSBox** removes everything in the app's private
  storage — the installed bundles and the local save slots.
* **The durable saves folder is yours.** WhereQ-DOSBox never deletes it.
  Delete the folder, or the files in it, whenever you want; the app only
  writes new save files into it.
* **To remove an individual game**, use *Remove* in the launcher.

## Security

Everything stays on your device, so there is no data in transit to protect
and no server to attack. The app is signed with a release key held by the
project; the published APK is checksummed next to the download link so you
can verify that what you install is the file that was published.

## Changes to this policy

If this policy changes, the new text is committed to the project's source
tree together with the change that caused it, and the effective date at the
top is updated. Because the policy lives in the repository, its history is
public: every version of this document is one commit away.

## Contact

Questions about this policy:

* **Email:** <googol.zhang@gmail.com>
* **This document:**
  <https://github.com/whereq/whereq-dosbox/blob/main/docs/PRIVACY.md>
* **Source code and licences:** <https://github.com/whereq/whereq-dosbox>
