# Native Purroxy integration (in progress)

Nekopur generates `Nekopurr.yaml` in the server working directory on startup.
Discovery defaults off. This implementation targets the version-1 control
protocol in the companion Purroxy fork, independently of player connections.

## Configuration

```yaml
enabled: true
purroxy:
  ip: proxy.internal.example
  port: 25565
  # Optional. Uploading purroxy.challenge into this directory is the normal path.
  pairing-token: ''
server:
  name: auto
  host: auto
  port: 0
resume:
  network-gamemode: Hub
  map: none
  player-safe-limit: 50
  region: US-West
transfers:
  enabled: false
```

Enable discovery with `authentication = "pairing"` in Purroxy. It generates its TLS
certificate and writes `purroxy-pairing/purroxy.challenge`. Upload that file into each
Nekopur server directory, beside `Nekopurr.yaml`. The same challenge enrolls any number
of backends, so a fleet can be deployed in parallel from one egg or image; pasting it
into `purroxy.pairing-token` still works and takes precedence over the file.
The challenge includes the exact proxy certificate fingerprint: the backend verifies
that certificate before transmitting an enrollment secret. No backend certificate,
keystore password environment variable or CA file is needed.

Each backend receives its own permanent credential at enrollment and **deletes its copy
of the challenge** once that credential is stored, so the shared secret does not linger
across the fleet. A backend that already holds a credential never needs the challenge again.

Administrators revoke on the proxy. `/purroxy pairing rotate` publishes a replacement
challenge and stops the previous one from enrolling anything, without disturbing backends
that are already enrolled. `/purroxy pairing revoke <server>` deletes one backend's
credential and drops its control connection, so a single compromised instance can be cut
off without reprovisioning the rest. `/purroxy pairing` lists what is currently enrolled.
Both commands require the `purroxy.admin.pairing` permission.

The proxy address must be reachable, not `0.0.0.0`. `server.name: auto` derives a
numbered name from the group; `hub-*` requests that prefix explicitly. A fixed name
also works. Assignment survives restart and does not shift when another server goes
offline. Nekopur stores its permanent instance ID, certificate pin, assigned name
and reconnect credential in `nekopurr-network/identity.json`; the enrollment secret is
removed from that file after pairing and may also be cleared from the YAML.
Keep this folder on persistent storage, but do not clone it into a new instance.
Keep Purroxy's `purroxy-pairing` folder persistent too.

An authenticated backend's unknown group is reported to administrators and leaves the
challenge usable. Groups must already exist. Pairing currently authorizes
the enrolled endpoint and group; changing them requires re-enrollment. Changing a
name setting does not rename an already paired instance. Discovery does not create
Pterodactyl instances.

`server.host: auto` uses the network peer address Purroxy observes. Override it if
the Minecraft endpoint uses another host. `server.port: 0` uses the listening port. Set an explicit
allocation port for NAT. The resume's hard limit uses the running player list's
configured maximum, sourced from `server.properties`; the example safe limit 50
does not change that maximum. Metadata is captured at startup; restart to change
the advertised metadata in this revision.

## Sleep and readiness

Pairing mode delegates active/spare selection to Purroxy. New empty instances may
start sleeping; Purroxy wakes one when a group has no ready backend and wakes more
when capacity is needed. After five idle minutes, an empty instance can return to
sleep if another ready instance in its group and region has sufficient spare capacity.
Purroxy blocks new admissions before sending the sleep request. Nekopur confirms or
vetoes it on the main thread; a lost acknowledgment forces control reauthentication.

Nekopur reuses its native
empty-server pause branch: world simulation pauses, while networking, scheduled
tasks, chunk housekeeping and the dedicated control thread continue. Paper's
plugin sleep veto and sprinting prevent sleep. An automatic startup spare that cannot
sleep becomes READY instead. Occupied servers and servers with pending handoff work
are never put to sleep.

Purroxy wakes an existing spare when its routing policy requires capacity. The
backend reports WAKING and then READY after map preparation, and Purroxy separately
probes its Minecraft endpoint. Repeated wake requests are idempotent.

Existing certificate setups remain supported: set `purroxy.authentication: certificates`
and retain `tls.key-store`, `tls.password-environment`, `tls.proxy-ca` and the proxy's
manual identity policy. Existing files containing `tls.key-store` default to this
legacy mode; to migrate them, explicitly set `purroxy.authentication: pairing` and upload
a challenge file. Old `identity.*`, `sleep.start-sleeping` and `handoff.*` keys remain
readable. Automatic sleep management takes precedence in pairing mode.

When enabled, native `pause-when-empty-seconds` does not independently pause a
READY backend. When disabled, normal server pause behavior is unchanged. Control
heartbeats continue every five seconds with no connected players. A stalled main
thread causes REGISTERING heartbeats after ten seconds instead of advertising
healthy readiness indefinitely. Authentication/connect failures retry every five
seconds; warnings are rate limited to once per thirty seconds.

## Maps and transfers

`map: none` leaves arrival-world decisions to the normal server and plugins.
A named map must already contain `level.dat` inside the world container; missing
folders are never intentionally created. The existing world is loaded and spawn
chunks prepared before readiness. Failed preparation leaves the backend
REGISTERING. Unloading the destination withdraws readiness.

A named map overrides the initial player spawn destination after plugin spawn
events and before destination chunks are prepared. This revision performs normal
Minecraft arrival at that map's spawn. The opt-in [hub-position handoff](HANDOFF_PROTOCOL.md)
adds journalled source coordinates, orientation and velocity, with a generation
marker in the player's saved data. It requires a coordinating Purroxy implementation.
The client loading screen is **not** suppressed by this backend capability.
The newer configuration calls this `transfers`. Keep it disabled for ordinary hub
deployment. Opt-in coordinated position transfers require `transfers.map-id` and
`transfers.map-revision` shared by matching replicas; the legacy `handoff` fields
remain aliases. `resume.map: none` still uses the normal default world, usually `world`.

## Remaining work and verification

- Coordinated drain before stopping, including timeout and evacuation policy.
  Current shutdown immediately closes discovery so Purroxy withdraws admissions;
  it does not wait for player evacuation.
- Proxy integration and live crash-injection verification of the new backend
  hub-position handoff, then native 26.2 same-map seamless transfer qualification.
- Protocol/capability negotiation and later ViaVersion/ViaBackwards qualification.
- Actual Pterodactyl deployment with two backends, plugin sleep vetoes, map loading,
  readiness probing, reconnects and the 70% wake/soft-overflow routing policy.

### Verification on 2026-09-08

The runnable 26.2 bundler built successfully. All eleven focused tests passed:
three YAML tests, three TLS transport tests and five lifecycle tests. These cover
hostname/session rejection, empty-server control messages, sleep vetoes, wake
ordering, duplicate wakes, stale readiness and unavailable-map readiness.

An isolated runtime boot generated the dedicated YAML and reached the ordinary
empty-server pause path with discovery disabled. A second boot enabled discovery
against a local mutually authenticated TLS test peer. The running server advertised
its actual hard limit of 73 and port 25589, sent two SLEEPING heartbeats with zero
players, accepted a wake and reported WAKING followed by READY. Both test processes
were stopped afterward. This peer verifies the backend protocol; it is not the
actual Purroxy routing implementation and does not replace a real client transfer
test.

Minecraft changes are delivered through the generated per-file patches for
`MinecraftServer` and `PrepareSpawnTask`. Current paperweight uses
`fixupMinecraftSourcePatches` followed by `rebuildMinecraftSourcePatches`.
In this checkout the fixup task left the generated repository's `file` tag pointing
at the old file-patch commit. It was advanced to the new `purpur File Patches`
commit before rebuilding. Always verify that the exported patches contain the
new hooks rather than relying on the Gradle success message alone.
