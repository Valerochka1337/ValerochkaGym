# PLAN-01 — Android integration boundary

This appendix implements the accepted training-proposals plan and fixture
`65254ebf9aaa4062ebf8ef71df99c76876685ce10ec0bd2fa8645fced56ea998`.
It adds no product scope. Implementation follows Health acceptance, with the actual
Room predecessor frozen by shared_owner. That owner retains all Room, domain/data,
sync, DI, navigation and version files; the UI writer owns screens/ViewModels/tests.

## Server routes and strict models

The recipient root is `/v1/training-proposals`. BackendApi supplies `/v1`, so client
paths start at `/training-proposals`. GET list takes limit 1–50 and optional cursor;
GET detail uses `/{proposalId}`. Approve, reject and accepted-result are children of
that detail route. There is no operation-ID lookup endpoint. Recovery retains the
local operation ID and queries accepted-result by proposal ID.

Use a dedicated strict serializer over BackendResponse.rawBody, enforcing fixture
keys, types, nullable-required fields, bounds and cross-field rules. Do not decode
through the shared permissive BackendApi Json. The fixture defines the exact
Proposal, ProposalSnapshot, Author, ApprovalDraft, PlannedExercise and PlannedSet
models. Source equals author kind; currentVersion equals snapshot.version.

AcceptedResult contains exactly proposalId, version, routineId, calendarPlanId,
revision and approvedAt. Its revision is receipt metadata, never a sync cursor.
Validate result proposal/version against the durable local operation before applying.

## Durable approval and authoritative application

The repository binds every opened editor and asynchronous action to owner and session
epoch. Room stores the edited draft by owner/proposal/version; SavedStateHandle stores
only route identifiers. A newer author version or expiry invalidates the old preview
without remapping its draft. Unapproved proposals are absent from PortableData.

Before first approval HTTP, atomically persist operation ID, canonical raw UTF-8 bytes
and SHA-256 with the bound owner/proposal/version. The generated kotlinx.serialization
serializer must match every fixture byte/hash vector, including explicit nulls,
property order, JVM Double formatting and negative-zero normalization. Retry sends
the stored bytes literally through owner/epoch-bound raw transport with no 401 retry.
Cancellation rethrows and keeps recovery evidence. No request rewrite is allowed.

After a valid accepted result, a narrow BackendSync method holds its existing mutex
and fetches a full authoritative sync response. Reuse its receive/merge/apply/baseline
path, preserving the existing generic outbox bytes. Do not run push/capture/delete
steps as a side effect of proposal application. Verify the server routine and calendar
plan stable UUIDs, then insert the proposal projection marker in the same Room
transaction as PortableData.apply and the authoritative baseline/cursor update.
PortableData already imports routine before calendar_plan and resolves the UUID link.

Recheck owner, epoch and active workout before HTTP, after response, inside the commit
transaction and before publishing success. An unrelated manual merge conflict leaves
the operation and projection marker unchanged and exposes retry. No direct call to
CalendarPlanRepository.createPlan, local result UUID allocation, duplicate outbound
aggregate or navigation into an active workout belongs to this path.

## UI and later reuse

Calendar hosts an inbox entry and pushed recipient detail/editable-preview routes.
The UI shows author/source/version, original versus edited values, validation,
loading/empty/error/retry, stale/expired, rejected and applied states. Apply, Reject
and Back are separate actions; Back decides nothing. Edited fields cover the bounded
routine and one-off slot, retaining explicit valid user weights.

Calendar-AI creates a server proposal and opens this recipient preview by proposal ID.
Coach authoring uses its relation-scoped create/revise/revoke endpoints and the same
draft model; recipient reads and decisions continue through this common path.
No source-specific local calendar insertion is introduced.

## Required regression evidence

- Exact first-send vectors and literal retries; changed-body denial before network.
- Edited draft recreation, newer version invalidation, expiry and owner A→B→A.
- POST response loss followed by accepted-result recovery and one projection.
- Active workout starting after GET and before the Room transaction.
- Pre-existing exact outbox preserved; full snapshot includes unrelated remote records.
- Manual conflict preserves journal and leaves projection marker absent.
- Strict extra/missing/null/type response rejection and stale accepted-result binding.
- Distinct Apply/Reject/Back, no automatic workout navigation, 48dp/font-scale-2 and
  compact/expanded semantics against the real production content.

Read-only preflight is complete. Source implementation and its gates remain pending.
