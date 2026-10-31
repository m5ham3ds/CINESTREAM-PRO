# CINESTREAM-PRO — Phase 26.11
## AniYomi-style Cookie/Session Recovery and Shared Cloudflare Challenge Flow

**Base:** Phase 26.10 source archive supplied in this conversation  
**Mode:** Source changes in a separate working copy; original archives were not edited  
**Build status:** NOT VERIFIED — Gradle Wrapper could not resolve `services.gradle.org`  
**Generated date:** 2026-10-29

## 1. Executive summary

This phase implements the cookie/session details identified in the supplied AniYomi source. Android `CookieManager` is the live cookie authority for WebView and shared HTTP requests. Protected requests read cookies for the exact URL and redirects are evaluated separately by the cookie jar. The app no longer synthesizes outbound Cookie headers from a stored session snapshot.

The old session files are migrated to a metadata-only v2 format. Their raw cookie values are not written into the new file; non-canonical legacy files are removed after the clean record is written. The challenge flow fingerprints the old `cf_clearance` value before deleting it, waits for cookie-manager deletion callbacks where possible, and does not treat a surviving/reused old clearance value as proof of success. A site response remains authoritative: the original operation is retried and the interactive dialog remains available if the site presents another confirmed challenge.

## 2. Changes in this phase

### Shared HTTP transport

- Added an OkHttp `CookieJar` backed by `CookieManager.getCookie(exactUrl)` so Android's Domain, Path, Secure and expiry rules decide which live cookies apply to each request/redirect.
- The shared network interceptor forwards each raw `Set-Cookie` field to `CookieManager` at the response URL instead of reconstructing it from `name=value`; this preserves the attributes supplied by the site.
- The Jsoup-compatible request adapter carries request method, form fields, headers, referrer and timeout into the shared transport.
- It does not forward a manually supplied `Cookie` header and disables HTTPS-to-HTTP downgrade redirects.
- A bare 403/503 no longer proves Cloudflare. Detection looks for `cf-mitigated: challenge`, explicit challenge DOM/body/URL markers, or a Cloudflare response with challenge markers.

### Session store and challenge confirmation

- Session files use `protected-session-v2`, cookie names only, bounded User-Agent metadata, and a local metadata expiry. The native cookie expiry and the provider's response remain authoritative.
- On migration from the legacy format, only cookie names and safe User-Agent metadata are retained; legacy files with a non-canonical name are removed after the new file is written. A regression test was added for raw cookie removal.
- The rejected clearance fingerprint is captured before deletion. It is never logged or persisted as a raw value.
- Rejected `cf_clearance` deletion is scoped to the actual challenged host, cookie path variants, and the provider domain scope only when the challenge host is inside that provider's domain tree.
- The WebView detector requires a clean page state and a fresh clearance fingerprint when a prior cookie existed (or an explicit completed Turnstile signal); the cookie's mere existence does not suffice.
- The app-wide localized challenge dialog remains independent of the player view. The original operation runs again on the same extension after user verification. If it is challenged again, the dialog stays retryable rather than allowing silent fallback to another extension.

### Challenge propagation in scrapers

- The five original bundled providers and the three ported sources already present in Phase 26.10 continue to route HTTP through the common transport.
- EgyDead's fallback does not swallow a confirmed challenge from POST and hide it behind a GET fallback.
- EgyDead and QFilm static extraction fallback paths return confirmed challenge errors to the common runtime instead of swallowing all exceptions as media-not-found.
- The declaration order of QFilm functions remains unchanged; its changes are constrained to the shared HTTP execution calls and the challenge propagation in its fallback.

## 3. Files changed

Changed files compared to the Phase 26.10 baseline are recorded in `phase2611-static-check-results.json` inside the validation-log archive. The principal areas are:

- `runtime/web/ProtectedSiteHttpClient.kt`
- `runtime/web/PerSiteSessionStore.kt`
- `runtime/web/ProtectedSiteSession.kt`
- `runtime/web/InteractiveChallengeController.kt`
- `runtime/web/ProtectedWebRuntime.kt`
- `ui/components/InteractiveChallengeWebView.kt`
- provider scraper HTTP adapters and two extraction fallback error paths
- corresponding `ProtectedSiteHttpClientTest`, `PerSiteSessionStoreRecoveryTest`, and `InteractiveChallengeControllerRecoveryTest`

## 4. Protected files and invariants

Static byte-for-byte comparisons against Phase 26.10 passed for `.firebaserc`, `firebase.json`, `firestore.rules`, `app/google-services.json`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `PlaybackAttempt.kt`, `SearchOrderRepository.kt`, and `DefaultControlledManagedExtensionRuntime.kt`. QFilm's function declaration/order check also passed. Its source necessarily differs only where network calls are routed through the shared client and the known challenge-swallowing fallback is fixed.

No Firebase/Firestore writes, rule deployments, migrations, or live provider operations were performed.

## 5. Validation results and limits

- **51/51 scripted static checks passed.** These include resource XML parse, Arabic/English challenge-key parity, provider transport routing, absence of direct `.get()/.post()/.execute()` terminals inside scraper classes, cookie persistence policy, legacy migration cleanup, exact URL cookie reads, challenge detection rules, retry wiring and protected-file invariants.
- New/updated unit and Robolectric test sources are included, notably a regression test that checks legacy cookie *values* are absent after migration. These tests were **not executed** because Gradle could not start.
- A real command was run: `bash gradlew --no-daemon --stacktrace :app:testDebugUnitTest :app:assembleDebug`. It stopped in the wrapper before Gradle startup with `java.net.UnknownHostException: services.gradle.org` while attempting to download Gradle 8.9.
- A standalone `kotlinc` screening without the app's Android/Gradle dependency classpath is not a valid app build and produced unresolved-dependency diagnostics; it is not counted as a passed compilation or test.
- No APK was produced. No live phone/WebView/provider challenge was exercised. Build, test execution, and phone validation remain open.

## 6. Important behavior/limits

- Cloudflare can reject a session at any time regardless of the cookie's nominal lifetime. The client does not assume that a cookie existing or being younger than the local metadata TTL means the provider accepts it.
- A website can vary its checks by path, IP, User-Agent, or browser state. A confirmed HTTP test is not a substitute for exercising each provider on the target Android phone.
- A successfully displayed challenge page does not prove success. The original request must be retried and must not return a second confirmed challenge before the UI closes.
- The shared cookie jar uses the native `CookieManager` result for each URL; cookies for a provider domain are not manually copied to unrelated embed domains.

## 7. Required next verification

Run from a machine/environment that can download Gradle 8.9 and resolve dependencies:

```bash
bash gradlew --no-daemon --stacktrace :app:testDebugUnitTest
bash gradlew --no-daemon --stacktrace :app:assembleDebug
```

Then install the generated debug APK on Android and run the included checklist for Anime4Up, EgyDead, QFilm, Witanime, AnimeBlkom, AnimeLek, ArabAnime, and Asia2TV wherever each is active and reachable. Collect full Gradle output and provider test results. Do not mark the phase complete for release until compilation/tests pass and live challenge/resume behavior is confirmed.
