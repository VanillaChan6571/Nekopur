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

Discovery-only peers omit it. `seamless` is advisory: Purroxy parses it into
`HandoffCapabilities` but never reads it, and `matches()` compares only version,
profile, world identity and map revision. Whether a client reset is suppressed is
decided per switch by the proxy, from the negotiated configuration and the entity ID
the destination actually reserved — never from this flag.

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
absence of the player, then prepares landing chunks and persists STAGED. Repeating the
same stage request returns the persisted reservation after validating that its immutable
metadata and snapshot still match; it never consumes a second fallback entity ID.

`stage` may also carry `requestedEntityId`: the entity ID the client already holds on
the source. It is optional; absent or `0` means the destination allocates its own, which
is the ordinary visible switch. A negative value is rejected. The destination reserves
the id during staging — before the player's connection opens — and the `requestedEntityId`
field of the returned `entry` reports the id it will actually use. An id different from
the one asked for is a valid reply, not an error; it tells the proxy the client must
still be reset.

Player entity IDs are allocated from a block assigned at enrollment (`entityIdBase` in
the registration reply), not from the world's shared entity counter, so an arriving
player's id is not already held by a mob, dropped item, hologram or NPC. The block is
persisted beside the pairing credential and reapplied at startup.
`server.entity-id-base` remains a manual floor for backends that are not paired.

`fence`, `commit`, `seamless`, `visible`, `abort`, `release` and `status` require session, request,
transfer, player and generation only. Every action is tied to that exact journal
entry. Duplicate actions are idempotent; changing a snapshot under the same
transfer ID or replaying an old ownership generation is rejected.

Replies have `type: handoff-result`, session, request and `status: OK` with an
`entry` object, or `status: REJECTED` and a bounded generic reason. Entries include
the transfer metadata, snapshot, backend role, persisted phase and `requestedEntityId`.

A `fence` reply may carry `trackedEntities` **beside** `entry`, not inside it: the ids
the source removed from the client (see ownership ordering, step 3). It is omitted when
the list is empty.
`visible` is accepted only on a destination already COMMITTED or ACTIVATED. It durably
sets `visibleArrival` on that journal entry, forcing the ordinary arrival-position sync
when a detached CONFIG probe must fall back to the client-visible path.
`seamless` is accepted only while the destination is COMMITTED or ACTIVATED and not marked visible.
It durably approves arrival-sync suppression. The proxy sends it only after detached
CONFIG matches and before acknowledging configuration finish, so the destination cannot
emit JoinGame first. Missing or legacy approval therefore defaults safely to a visible
arrival, including after coordinator restart.
`status` returns `NOT_FOUND` when that exact player/transfer/generation is absent;
this allows recovery to distinguish an operation that never arrived from an
unreachable backend or a rejected rollback of committed state.

## Ownership ordering

1. The coordinator journals the transaction before issuing `export`.
2. Source EXPORTED; destination STAGED, with landing chunks prepared.
3. Source `fence` persists FENCED, removes the old client entities, then acknowledges.
   Fences do not expire. After the phase is durable, the source removes every entity it had
   shown it — taken from the entity tracker, so the set is exact, and including other
   players — and reports those ids as `trackedEntities`. This happens while the player
   is frozen and before the destination sends anything, so the client's entity table is
   empty when the destination begins populating it. Without it the source's entities
   remain in the client's table and their ids alias the destination's own, which leaves
   NPCs visible but unclickable. Purroxy records the reported ids for diagnosis but does
   not act on them; removal is the source's. Persistence happens first so a failure after
   clearing cannot leave an unfenced owner with a partially cleared client. Validation
   still precedes both operations, so a stale or superseded fence cannot blank a client's
   entity table on its way to being rejected.
4. The coordinator durably records COMMIT. From this point it must never send
   `abort` or reactivate the old source, even if an acknowledgment is lost.
5. Destination `commit` persists COMMITTED. Only then may the coordinator open
   the player's destination Minecraft connection.
6. Destination spawn preparation persists ACTIVATED before applying the profile.
   The player's normal save contains `nekopurr:handoff_generation`. Rejoining with
   that generation already saved does not repeatedly apply the old snapshot. A
   crash before that save leaves the journal snapshot available for recovery.
   When `transfers.sync-arrival-position` is false, an arrival reached through a seamless
   handoff is not teleported to the snapshot position only if its requested entity ID was
   actually retained, the proxy durably approved seamless arrival, and it has not marked
   the transaction visible. The client kept moving while
   the source froze it, so correcting it pulls a moving player backwards. The first movement
   packet may skip only the moved-too-quickly check, and only within 64 blocks of the frozen
   snapshot. Loaded-chunk, collision and plugin movement handling still run. An out-of-range
   report follows the ordinary correction path, and all later packets use ordinary validation.
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
