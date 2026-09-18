- [x] Verify that the copilot-instructions.md file in the .github directory is created.
- [x] Clarify Project Requirements: Android-first private one-to-one chat; user requirements and threat model control scope.
- [x] Scaffold the Project: Java 21 Maven relay/client-core, native Android client, TLS local infrastructure.
- [x] Customize the Project: official libsignal integration, opaque relay, protected local storage, expiry and access control; production review remains required.
- [x] Install Required Extensions: no extensions required by the selected setup guidance.
- [x] Compile the Project: JVM security tests, Android APK and lint, emulator Keystore/native-protocol tests.
- [x] Create and Run Task: Vanishr: Verify Security Foundations runs scripts/verify.ps1 -Android successfully.
- [x] Launch the Project: local HTTPS relay, verified-TLS Redis/PostgreSQL and isolated test emulator; FCM credentials not configured.
- [x] Ensure Documentation is Complete: README and architecture, API, threat model, crypto, verification/limitations docs.

## Project rules

- Read docs/THREAT-MODEL.md before changes to security-sensitive code.
- Private keys and plaintext content belong only in client code. Never add a production dependency from relay to client-core.
- Use official libsignal for sessions; never implement a custom encryption protocol.
- Never log bodies, content, keys, tokens, request headers or raw exceptions containing inputs.
- Every ephemeral record needs a bounded TTL in the same operation that creates it.
- Fail closed on unknown/changed identities, expired content, failed authentication, and unavailable secure storage.
- No sensitive backups, analytics, plaintext cache files, cleartext network fallback or committed secrets.
- Tests and documentation must distinguish demonstrated behavior from unverified production claims.
- Cloud changes are restricted to vanishr-dev-rg in the approved Visual Studio subscription. Never modify, reuse, stop or delete resources in other groups. Keep the subscription spending limit on.
- The approved Azure dev/test planning budget is INR 1,200/month (not a hard cap); the current fixed baseline is about INR 901/month. Do not enlarge the VM or enable paid add-ons without approval. Shared credit exhaustion can affect other groups.
- Vercel hosting is static-only on the Vanishr Hobby team. Upload only the audited distribution folder, never the repository root, .secrets, signing keys or server configuration.