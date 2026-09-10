# Coordinated hub handoff, version 1

This is an opt-in extension of the existing mutually authenticated discovery
connection. It does not use player plugin messages. The first profile is
`hub-position`: replica world identity, map revision, position, orientation and
velocity. Inventory, XP, health, permissions, economy and arbitrary plugin data
are not transferred by this profile.

Enable `handoff.enabled` in `Nekopurr.yaml`, and assign identical
`handoff.world-identity` and `handoff.map-revision` to compatible replicas.
The default profile is `hub-position`. A map name is not a replica identity.

The resume includes a top-level `handoff` object:

```json
{"version":1,"profile":"hub-position","worldIdentity":"hub","mapRevision":"christmas-v1","seamless":false}
```

Discovery-only peers omit it. `seamless:false` means coordinated handoff must
use the normal visual switch; it never authorizes suppressing client resets.

## Requests and replies

Every request uses the current authenticated discovery session, a new request
UUID, a stable transfer UUID, the player UUID and a positive ownership generation:

```json
{"type":"handoff","session":"<session UUID>","request":"<request UUID>","action":"export","transfer":"<transfer UUID>","player":"<player UUID>","generation":1,"source":"hub-1","destination":"hub-2","profile":"hub-position","expiresAtMillis":0}
```

The example expiry must be replaced with a real deadline, at most sixty seconds
ahead of the backend clock. Deployments must synchronize host clocks. `export`
captures the source player on the server thread and persists EXPORTED before
acknowledging. It rejects dead players, vehicles, open containers and players
outside the configured world. Source movement/simulation is held while exporting;
an unfenced export stops holding movement after its deadline.

`stage` carries the same metadata and the source reply's `snapshot`. The
destination validates replica identity, revision, coordinates, world bounds and
absence of the player, then prepares landing chunks and persists STAGED.

`fence`, `commit`, `abort`, `release` and `status` require session, request,
transfer, player and generation only. Every action is tied to that exact journal
entry. Duplicate actions are idempotent; changing a snapshot under the same
transfer ID or replaying an old ownership generation is rejected.

Replies have `type: handoff-result`, session, request and `status: OK` with an
`entry` object, or `status: REJECTED` and a bounded generic reason. Entries include
the transfer metadata, snapshot, backend role and persisted phase.
`status` returns `NOT_FOUND` when that exact player/transfer/generation is absent;
this allows recovery to distinguish an operation that never arrived from an
unreachable backend or a rejected rollback of committed state.

## Ownership ordering

1. The coordinator journals the transaction before issuing `export`.
2. Source EXPORTED; destination STAGED, with landing chunks prepared.
3. Source `fence` persists FENCED and acknowledges. Fences do not expire.
4. The coordinator durably records COMMIT. From this point it must never send
   `abort` or reactivate the old source, even if an acknowledgment is lost.
5. Destination `commit` persists COMMITTED. Only then may the coordinator open
   the player's destination Minecraft connection.
6. Destination spawn preparation persists ACTIVATED before applying the profile.
   The player's normal save contains `nekopurr:handoff_generation`. Rejoining with
   that generation already saved does not repeatedly apply the old snapshot. A
   crash before that save leaves the journal snapshot available for recovery.
7. After destination connection success and closure of the old connection,
   `release` marks the old source RELEASED. This terminal state allows a later,
   ordinary login there; only unresolved ownership fences block arrival.

Before COMMIT, the coordinator first journals ABORT and then sends `abort` to
both peers, including a source whose fence acknowledgment was lost. After COMMIT,
recovery retries destination commit and routes reconnects to the recorded owner.
Backend restart reads the journal; corruption is an error, not an empty history.
SOURCE and STAGED records cannot admit an unrelated login while unresolved.

Journal writes run on a dedicated executor. Each file is flushed before atomic
replacement, and replies follow persistence. These local journals assume one
coordinating Purroxy and persistent storage. Multiple coordinating proxies require
shared ownership storage and are outside this version.

The coordinator must stop before committing if its deadline expires, a reservation
is lost, the player disconnects, a plugin redirects the destination, or either
backend session changes. A post-commit connection failure requires recovery at
the destination; it cannot silently resume a fenced source.
