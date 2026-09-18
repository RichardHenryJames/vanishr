# Vanishr Release Candidate Distribution

Vanishr 0.2.0, 2026-09-16, is offered under the GNU Affero General Public
License version 3. The complete license is in LICENSE. This application is
provided without warranty, including any implied warranty of merchantability
or fitness for a particular purpose. You may use, modify and redistribute the
covered source under that license.

Vanishr is an independent application, not a product of Signal Messenger.
It uses unmodified official libsignal 0.102.3, Copyright Signal Messenger,
LLC, licensed under AGPLv3. Matching upstream source and its original native
dependency acknowledgments accompany the download:
https://github.com/signalapp/libsignal/tree/v0.102.3

The source bundle contains Vanishr's application code and build scripts,
resolved dependency coordinates, available Maven source artifacts, and
embedded dependency license notices. Third-party components retain their
respective licenses and copyrights. Firebase/Google Play services components
also carry Google's applicable SDK terms; inspect their original artifacts
and licensing information before redistribution.

Download the application source and the matching libsignal source from the
same page as the APK, without charge. Rebuilding uses Java 21, Android SDK 36,
the pinned Gradle distribution and the dependency repositories in the build
files. Supply your own signing key with scripts/package-apk.ps1. Private
signing keys and service credentials are intentionally not distributed.
The source build does not prevent users from installing their own modified
build signed with their own key; changing the signing identity may require
uninstalling the previous app and independently reverifying device identities.

This is a release candidate on development/test hosting. Source availability and a valid APK
signature are not an independent security audit or legal compliance review.
Google sign-in and FCM need owner configuration and live acceptance testing.
Do not use it for real sensitive content before the documented release gates.