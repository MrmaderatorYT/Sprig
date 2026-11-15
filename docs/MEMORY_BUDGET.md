# Shared retained-memory admission

Stage 0.4 introduces `core.memory.MemoryBudget`. This is an ownership-based reservation limit for selected Sprig buffers. It is **not** a measurement or a hard cap on the entire Java/native heap.

## API and runtime wiring

`tryReserve(Category, bytes)` returns an idempotently closeable lease or `null` immediately when capacity is unavailable. `resize(bytes)` changes ownership atomically and preserves the previous reservation on failure. Negative/overflowing admission cannot wrap counters. Snapshots expose limit, current/peak reserved bytes, denied admissions and fixed per-category totals; they contain no captured URLs, headers or bodies.

The Android application supplies one shared budget to `SessionStore`. The proxy passes that same instance to WebSocket and Intruder. Existing standalone constructors retain isolated defaults for backwards compatibility; creating unrelated default instances does not magically impose a process-wide cap.

## Accounted owners

| Owner | Admission / release |
| --- | --- |
| HTTP history | Body bytes, string-size estimates and metadata are reserved before defensive body clones. Replacement, count/byte eviction and Clear release leases. Shared pressure evicts only this history's own oldest records; if it still cannot admit the capture, `droppedRecords()` increases. |
| Owned HTTP capture | `recordOwned()` reuses already captured read-only body arrays, avoiding duplicate clones. The caller must not mutate transferred arrays after publication. General `record()` remains defensive. Snapshots follow the existing read-only array contract; Java does not enforce it. |
| WebSocket session metadata | A worst-case bounded metadata lease is acquired together with the session slot, before connecting/upgrading, then shrunk to the actual bounded headers/URL. Closed session metadata stays charged until eviction/Clear. |
| WebSocket history | Reserve before capture copies; count, local bytes and shared capacity all apply. Eviction/Clear/Close release leases. Shared pressure can omit a history capture without rewriting wire traffic; `droppedHistoryMessages()` records omissions. |
| WebSocket delivery | Queued/in-flight payloads remain charged through delivery or drop. Manual-send clones are allocated only after admission. Termination drains queued leases under the same lock as enqueue, preventing a close/enqueue leak. |
| WebSocket interception | Original held copies and edited copies have separate leases. A failed edit remains pending and can be retried/dropped. Leases survive removal from the pending UI list and are released only after delivery finishes or the hold fails/cancels. |
| Intruder configuration | Retained template/plan, built-in payload-source state, processor captures and small configuration metadata are estimated before runtime-plan allocation. Admission failure does not start a transport. Prefix/suffix/replace buffers and numeric-source overhead are included. |
| Intruder results | Retained evidence/results compete for shared and per-run limits. Old results are evicted first; otherwise the new result is explicitly omitted. Completion retains the configured plan/results for Repeater/export; registry eviction or Forget releases ownership. |

A component never silently evicts another tool's data. Network-data admission failures are explicit. Local body/message/count limits remain necessary even when shared capacity is available. Built-in processors bound their retained byte arguments; custom Java sources/processors must report captured state through `estimatedBytes()`.

## Important boundaries

Protocol parsing/fragment assembly, TLS/QUIC buffers, temporary rendering/parsing allocations, thread stacks and caller-held snapshots are not all covered by these retained-owner leases. Existing local/count/timeout limits still apply to those paths. A result may be constructed in bounded worker-local temporary memory before the retained-history admission decision. Once a caller retains an exported/snapshot reference outside the owning store, its lifetime belongs to that caller.

Consequently the broad roadmap item for a global allocator across **all** history, bodies, WebSocket and tools must remain open until remaining transient-buffer paths and lifetimes are integrated and profiled. A configured byte limit or host JVM test is not proof of Android RSS, GC or battery performance.

## Validation

Regression fixtures cover atomic resize/refusal, idempotent release, concurrent owners, overflow-safe accounting, competing history stores, replacement/eviction/oversize/clear, owned-capture reuse, WebSocket slot refusal, held/edit lifetime, failed edits, delivery pressure/close cleanup and Intruder configuration/result/Forget ownership. Existing protocol, interception and Intruder tests remain required alongside these accounting checks.
