# WearTAK Plugin Lattice

## Purpose and Capabilities

Separate, **read-only** WearTAK plugin for authorized Lattice point entities.
The host discovers and controls the plugin through its signature-protected SDK
service. Its native WearTAK-style DayNight screen configures reception and shows
validated track identity, affiliation, environment, platform type, coordinates,
observation time, expiry, and simulation marking.

Reception requires both host `startPlugin()` and an explicit **Receive tracks**
tap while the native screen is visible. Host stop/shutdown/death/unbind, screen
closure, process death, transport failure, or invalid data ends reception and
clears the in-memory view. Backgrounding never leaves a hidden connection.
Configuration is retained, not an enabled/reconnect preference.

No position/health publishing, Lattice entity writes, tasks, commands, effectors,
CoT dispatch, alerts, map injection, or host marker calls are implemented.

## Status

First-phase foundation; not a deployed or operationally validated integration.
Synthetic JVM/Android contract fixtures cover ingestion, lifecycle, errors,
expiry, and source isolation. Local debug and unsigned release builds are development
outputs, **not approved install/release artifacts**. Protected-main CI separately
requires the existing production signer before emitting an install candidate.
No live endpoint, credentials,
physical watch, host-signature compatibility, TLS deployment, accessibility
screen-reader, or battery/endurance verification has been supplied.

**Host map integration is blocked:** current SDK exposes
`requestMarkerPublish/Update/Clear`, but does not guarantee local-only rendering
without CoT/network egress or define acknowledged, source-scoped snapshot/expiry
semantics. `MarkerContract` exposes only type, remark, latitude, and longitude.
`IWearTakMapLayerControl` is opaque and phase-gated, not a documented track
ingestion protocol. The plugin does not infer a safe contract from these calls.
Required before map integration: a negotiated, authenticated local-only layer
API with source/session identity, stable track IDs, acknowledged upsert/removal,
expiry and disconnect cleanup, limits, and explicit no-egress behavior. No host
or SDK repository is modified by this foundation.

## Point of Contacts

Not supplied.

## Ports Required

Outbound HTTPS/TCP 443 to the operator-supplied authorized Lattice origin.
An explicitly configured HTTPS port is also accepted. No inbound listener,
discovery broadcast, phone bridge, or additional telemetry endpoint.
Builds resolve dependencies through configured TAK Artifactory/public Gradle
repositories and read pinned SDK source from TAK.gov.

## Equipment Required

Wear OS device running Android API 30 or later; compatible WearTAK host and plugin
signing identity; network route to an authorized Lattice deployment supporting
REST v1 entity SSE; operator-provided environment bearer token and optional
sandbox bearer token. Deployment must permit this device to receive that data.
Private deployments also need a valid system-trusted chain or approved CA PEM.

## Equipment Supported

Compile SDK/target SDK 34, minimum SDK 30, plugin base version `5.8.4`.
No device model, deployment, or host build is certified. This plugin uses
`externalMainActivity`, not an invented host menu or credential-input extension.
Native controls use scrolling, round-screen insets, system font scaling,
48dp input/button targets, labelled inputs, and DayNight theme.

### Official icon status

Launcher, adaptive, round, and package icons use the authentic Anduril company
symbol from its [official website asset](https://www.anduril.com/assets/favicon/favicon-512.xxjGh-Lg5Y.png),
with proportional adaptive-icon padding and no artwork redrawing. This is the
requested company-icon fallback: no standalone Lattice product icon was verified
in the checked public official sources. The user attested permission from
Lattice for this plugin's icon use. Vendor artwork/trademarks remain vendor
property, not GPL-licensed project artwork; see `NOTICE`. Permission does not
imply endorsement, certification, or operational readiness.

The pinned SDK's `PluginInfoParcel` contains no icon field. Hosted settings
also have no verified arbitrary vendor-icon surface. Package `android:icon`
uses the same adaptive icon as the launcher and round launcher. Android
package/plugin identity is unchanged; no unsupported host icon contract is added.

## Compilation

Requirements: JDK 17, Android SDK API 34 / Build Tools 34.0.0, Git, Python 3,
and read access to canonical SDK source. Run from this isolated checkout:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
python -B ci\prepare_sdk.py
python -B -m unittest discover -s ci -p "test_*.py"
.\gradlew.bat --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:packageDebugArtifact :app:assembleRelease
```

Non-Windows runners use `./gradlew` with the same task selectors. SDK source is
pinned in `sdk-revision.txt` to
`af3f6763d75265415a324f8a616c7b00747e182a` from
`https://git.tak.gov/weartak-plugins/weartak-plugin-sdk.git`, project 6927.
Preparation refuses a different/dirty checkout (including untracked source) rather than overwriting
it or silently accepting template AARs. Android `preBuild` independently checks
the pin and license. SDK build outputs live under ignored `.dependencies/sdk`.
Never edit a primary checkout to prepare this build.

Gradle first uses TAK Artifactory when a complete credential pair is present.
Username aliases, in order: `ARTIFACTORY_CONSUME_USER`, `ARTIFACTORY_USER`,
`TAK_USERNAME`; password aliases: `ARTIFACTORY_CONSUME_PASSWORD`,
`ARTIFACTORY_PASSWORD`, `TAK_PASSWORD`. No credential values are logged.
Google/Maven Central/plugin portal remain normal fallbacks. Robolectric uses
the paired private repository when configured.

Debug package:
`app\build\outputs\lattice\debug\WearTAK-Plugin-Lattice-5.8.4-p0.apk`.
`CI_PIPELINE_IID` replaces `0` in CI and supplies its Android version code;
base version remains `5.8.4`. Unsigned release validation output:
`app\build\outputs\apk\release\app-release-unsigned.apk`.
Without signing environment variables, local release output remains unsigned.
Debug's ordinary development signature is not asserted compatible with host.
GPL, project notices, and Apache license text are packaged under APK
`assets/legal/`. Gradle distribution checksum is pinned in the wrapper.

`.gitlab-ci.yml` separates Python/shell contract fixtures into the official
`python:3.12-bookworm` image. Android validation and release use the canonical
Gradle launcher, which lacks Python and has obsolete apt package indexes.
The equivalent `ci/prepare_sdk.sh` entry point avoids installing packages into
that image. Both preparation paths enforce the same source pin, clean checkout,
and license; Android `preBuild` independently repeats these checks. CI's
ephemeral job token authenticates only to canonical TAK.gov SDK reads via a
child-process Git header, not a credential-bearing URL or persistent Git config.
No SDK source is uploaded as a job artifact. The Gradle image must provide Git,
JDK 17, and Android tools. Configuration validation alone does not verify runner
execution or SDK source access.

After both validation jobs pass, a push on the protected default branch runs
`main_release` using the existing inherited protected/masked variables:
`WEARTAK_RELEASE_KEYSTORE_B64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
and `RELEASE_KEY_PASSWORD`. Missing/invalid signing fails explicitly, never
falls back to debug signing. `WEARTAK_RELEASE_KEYSTORE_FILE` points Gradle to
the restored ignored keystore, created with mode 0600 and removed by
`after_script`; passwords remain environment-only, not signing-properties files
or command-line arguments. No keys/secrets are committed or provisioned.

Only a cryptographically verified, single-signer release APK with package
`com.weartak.plugin.lattice`, version `5.8.4`, version code `CI_PIPELINE_IID`,
and production certificate SHA-256
`1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d`
is copied to the release artifact directory. Artifact name:
`WearTAK-Plugin-Lattice-5.8.4-p<CI_PIPELINE_IID>.apk`, alongside its SHA-256
checksum and source-SHA/pipeline/job/package/version/signer provenance.
Artifacts expire after 30 days. This is CI artifact delivery, not a tagged
release or automatic installation/activation. Installation still requires
device-side package/version/signer compatibility checks and explicit user
approval; preserving data never permits uninstall/downgrade/signature bypass.

`python -B -m unittest discover -s ci -p "test_*.py"` runs Python preparation
fixtures locally; Linux CI additionally runs shell pin/dirty-source, signing
failure/cleanup, certificate/version, protected-branch, and provenance fixtures.
Those Linux-specific fixtures explicitly skip on Windows, not count as passes.

SDK project 6927 is private with inbound job-token scope enabled. With explicit
user approval, project 6952 was added to its inbound project allowlist for CI
SDK-source reads; existing entries 6927/6933 were preserved. No group-wide,
visibility, static-token, or protection change was made. Source access remains
subject to GitLab job-token permissions and must be confirmed by actual CI.
Local verification uses existing authorized developer Git access.

## Developer Notes

### Direct watch architecture

`core` contains Android-compatible Kotlin/JVM parsing, validation, lifecycle
store, and a single `HttpsURLConnection` receive operation. `app` owns native
configuration, AndroidKeyStore persistence, host Binder authorization, and the
foreground session gate. No Anduril Java/gRPC SDK, generated schema, Google
Nearby library, companion app, or server is bundled or required.

Direct REST SSE is the smallest available receive path: API 30 supplies TLS,
HTTP streaming, UTF-8, and `java.time`. It avoids gRPC/generated SDK size and a
new bridge product. Foreground-only reception, explicit reconnect, bounded
state, and no retry loop limit watch radio/CPU use; battery cost remains
unmeasured. Environments requiring managed OAuth provisioning, a phone-mediated
credential flow, sustained background reception, or narrower upstream filtering
need a separately approved product/host contract, not an implicit new service.

### Protocol and validation

Official REST **v1** `POST /api/v1/entities/stream` establishes an SSE read
subscription (`Accept: text/event-stream`, JSON body
`{"heartbeatIntervalMS":15000,"preExistingOnly":false}`).
This POST does not publish a track. The quickstart's `PUT /api/v1/entities`
publishes and is **not used**. SDK language package versions are not REST API
versions.

Only HTTP 200 **and** `text/event-stream` enter parsing. Neither HTTP success,
`{}`, nor a heartbeat claims a complete snapshot. REST documentation does not
give this implementation a completion barrier or usable resume cursor. UI
therefore labels its list a **partial view**, even after valid events.

Supported `entity` event types:
`EVENT_TYPE_PREEXISTING`, `EVENT_TYPE_CREATED`, `EVENT_TYPE_UPDATE`, and
`EVENT_TYPE_DELETED`. Heartbeat `timestamp` is optional per REST reference;
when present, it must be fresh RFC3339. A valid heartbeat is only connection
liveness, never a verified entity or snapshot.
Live display supports `TEMPLATE_TRACK` / `TEMPLATE_ASSET` with either
`location.position` or
`kinematics.kinematicsGeodetic.location` WGS84 latitude/longitude.
No ECEF conversion, polygons, bearing-only tracks, altitude, correlation, or
partial-component merging is claimed. Recognized unsupported templates,
geocentric shapes, and missing point locations remove any prior displayed
identity and increment a visible unsupported count. Missing required data,
ambiguous coordinates, unknown event/enumeration values, invalid types, and
oversized data fail reception visibly and clear the whole view.

Display validation requires nonblank bounded string identity; explicit known
military disposition/environment and ontology platform type; live Boolean;
finite latitude in [-90,90] and longitude in [-180,180]; valid
`provenance.sourceUpdateTime`; and future expiry, unless `noExpiry` is explicitly
true. Zero coordinates remain valid; missing coordinates never become zero.
Missing display name falls back to the validated identity; absent simulation
indicator means not marked simulated. A malformed supplied component never
silently becomes a default.

Local expiry is the earlier of server expiry and 120 seconds after the oldest
of source update, optional tracking measurement, and optional geodetic
measurement times. `noExpiry` cannot evade local freshness. Future observations
or event times beyond 30 seconds skew fail validation. Event-time ordering and
tombstones prevent older/duplicate updates from resurrecting deleted tracks;
newer delivery cannot overwrite a newer source observation. Source-time
watermarks remain in the bounded identity table after expiry or deletion, so
later-delivered older observations cannot resurrect cleared tracks.
Expiry is checked on UI refresh even without more events.

Every connection has a fresh store. A reconnect always discards old state and
requests a new pre-existing stream; SSE `id` / `retry` fields are not treated as
an undocumented reliable resume contract. Changes to endpoint/configuration
stop reception before saving. No state is merged across origins or sessions.

Budgets: 256 displayed tracks, 512 unique identities including tombstones per
connection, 65,536-byte SSE parsing frame budget, JSON depth 32, 256-character
entity IDs, 128-character names/platform types, 100 parsed events per second,
15-second connection timeout, 45-second socket timeout and monotonic deadline
for a verified event. Budget exhaustion fails visibly, never silently evicts.
All components are requested because no deployment-specific component/filter
contract has been verified; dense environments can hit these explicit limits.
There is one receive worker; an old closing worker must exit before another
connection starts. Errors require an explicit retry and clear displayed tracks.

### Credentials, trust, and lifecycle

Use the native plugin screen only. Enter an HTTPS **origin**, raw environment
token (no `Bearer ` prefix), optional raw sandbox token, and optional CA PEM.
Saving replaces token fields, so re-enter tokens when changing configuration.
Nothing automatically connects on save, host startup, activity recreation,
or process restart.

Static environment tokens are supported. Official OAuth client credentials
remain beta/not universally deployed; this plugin does not store a client
secret, request OAuth grants, or invent an OAuth endpoint. Rotate environment
and sandbox tokens through the authorized deployment process.

Configuration is AES-GCM encrypted with an AndroidKeyStore non-exportable key,
random IV, application/version AAD, bounded atomic file in `noBackupFilesDir`,
and read-back verification before reporting save success. Backups and device
transfer are excluded. Plaintext credential fields disable saved-instance-state,
autofill, and personalized keyboard learning; activity uses `FLAG_SECURE` and
clears fields when backgrounded. Tokens never enter host settings/schema/state,
action arguments, logs, URLs, source fixtures, or analytics. Track state remains
in memory only. Forget stops reception and removes configuration/key.
Keystore invalidation/corruption is an error, not empty successful settings.
Device-keystore encryption and keyboard behavior require physical validation.

Manual deployment-token enrollment is a foundation limitation, not consumer
account sign-in. `WearPasswordInput` lint is suppressed only on the masked
configuration-field helper, with this rationale. A production-friendly secure
phone enrollment flow needs an actual supported contract before implementation.
No phone clipboard or remote provisioning channel is invented.

TLS uses platform trust/hostname verification by default. Optional CA PEM
must contain 1-8 currently valid CA certificates with CA basic constraints;
it creates a dedicated trust store, not a trust-all override. Hostname checks
remain untouched. HTTPS redirects are rejected, so credentials cannot be
redirected to a different origin. No cleartext, permissive trust manager,
custom hostname verifier, or certificate-warning bypass exists.

Host service binding requires SDK signature permission. Initialization checks
the actual Binder caller UID, declared host UID/package, expected plugin ID,
and callback ownership, links host-control death, and implements v1/v2/v3
initializers without using event/command extensions. Stop, shutdown, death,
unbind, and service destruction revoke reception. Death callbacks belong to
one host binding: delayed callbacks from replaced bindings cannot revoke the
current host. A failed replacement death registration revokes the old caller's
authorization until initialization succeeds again. Native screen cannot bypass
host startup. Host signature/discovery behavior still needs real-host testing.

### Evidence and acceptance still needed

Synthetic fixtures use invented identity and point data only. Tests exercise
HTTP/auth/error shapes, exact receive-only request, redirects, SSE framing,
UTF-8/depth/frame limits, strict entity fields, geodetic coordinates, freshness,
expiry without traffic, update ordering, deletion/tombstones, resource caps,
source replacement, host lifecycle authorization, explicit start, foreground
stop, bad-data cleanup, heartbeat deadline, secure-window/input flags, and
invalid CA rejection. Mocks make **no live Lattice requests**.

Before operational use, provide an authorized origin and scoped credentials;
validate server event/heartbeat/update shapes and snapshot behavior; confirm
TLS/private CA/hostname failures; verify actual keystore persistence, host
signing/discovery/start-stop/death, watch layout/keyboard/accessibility, and
endurance. Map display remains blocked on the local-only host contract above.
Do not infer outbound authorization from read access or add position publishing
without a separate explicit decision.

### Official references reviewed

- [Quickstart](https://developer.anduril.com/guides/getting-started/quickstart.md)
- [Authentication](https://developer.anduril.com/guides/getting-started/authenticate.md)
- [Watch entities](https://developer.anduril.com/guides/entities/watch.md)
- [REST entity stream](https://developer.anduril.com/reference/rest/entities/stream-entities.md)
- [Documentation index](https://developer.anduril.com/llms.txt)
- [Anduril license](https://developer.anduril.com/license.md), updated July 31, 2026

## License

Original plugin code is GPL-3.0-or-later; full GPLv3 text is in [LICENSE](LICENSE).
Pinned WearTAK SDK's original code has the same express grant in its canonical
README/LICENSE. Third-party components retain their own licenses and notices;
see [NOTICE](NOTICE) and [third_party/Apache-2.0.txt](third_party/Apache-2.0.txt).

No proprietary Anduril SDK code, generated bindings, trademarks/assets, or
runtime binaries are copied or bundled. Independent REST implementation does
not waive service/data terms. Anduril license sections 3.1/3.4 restrict its SDK
grant and redistribution, section 3.5 preserves open-source component grants,
and section 8 requires authorized data use and explicit user consent.
Authorized operators must establish applicable service/data/export permissions;
this repository neither accepts terms for them nor asserts blanket GPL
compatibility for a future bundled Anduril SDK. No live service terms have been
accepted through this implementation.
