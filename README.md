# chat-lab 💬🧪

chat-lab is a **learning and experimentation lab** built around a minimal distributed chat system.
The goal is not to build a “complete” chat application, but to explore core concepts of
**event-driven systems**, **convergence**, and **state observation**.

---

## Why this project exists 🚀

This project is a direct continuation of a previous exercise involving multiple “robots” 🤖
communicating with each other to reconstruct a shared state.

Here, a chat system is used as a more **concrete and readable medium** to:

* 🌐 Explore distributed systems without strict central coordination
* 📩 Reason in terms of events rather than mutable shared state
* 👀 Observe progressive convergence of local views
* 🧱 Enforce clear separation between domain, runtime, and UI

chat-lab is first and foremost a **learning playground** 🎓.

---

## What chat-lab is NOT ❌

* ❌ A production-ready chat application
* ❌ A system with strong global consistency guarantees
* ❌ A Slack, Discord, or Matrix clone
* ❌ A feature-driven or UX-focused project
* ❌ A complex multi-room messaging system

The objective is **conceptual clarity**, not feature completeness ✨.

---

## High-level overview 🗺️

chat-lab is structured as an **event-driven runtime** around a small, explicit domain core.

```
┌────────────┐        Commands        ┌──────────────┐
│   Client   │ ───────────────────▶ │   Runtime     │
│ (future UI)│                      │ (Orchestrator)│
└────────────┘                      └──────┬────────┘
                                             │
                                             │ emits Events
                                             ▼
                                      ┌──────────────┐
                                      │    Domain     │
                                      │ (pure logic) │
                                      └──────┬───────┘
                                             │
                   ┌─────────────────────────┼─────────────────────────┐
                   ▼                         ▼                         ▼
           ┌──────────────┐         ┌────────────────┐        ┌────────────────┐
           │  Projections │         │   Moderation   │        │   Persistence  │
           │ (Timelines)  │         │   Pipeline     │        │  (Badger / FS) │
           └──────────────┘         └────────────────┘        └────────────────┘
                   │
                   ▼
           ┌────────────────┐
           │ Observers / UI │
           │ (read-only)    │
           └────────────────┘
```

Key ideas:

* 📢 The **domain emits events**, never side effects
* 🧠 State is derived through **local projections**
* 🔌 IO, storage, and UI live at the edges
* 🧱 The runtime wires everything together

---

## Conceptual model 🧩

The system is built around a small set of core concepts:

* **Participant** 👤
  An actor in the system, uniquely identified, that can be active or inactive.

* **Message** ✉️
  An immutable event emitted by a participant at a given point in time.

* **Timeline** 🕒
  A local projection of known messages. It may be incomplete, out of order, or temporarily unstable.

* **Presence** ⚡
  A derived piece of information (active / inactive), itself based on events.

None of these concepts are designed as a globally shared state.

---

## Events, not state 📢

chat-lab follows a strict principle:

> **The system produces events.
> State is a local, reversible projection.**

* 📝 Messages are never modified or deleted
* 🔄 Timelines are reconstructed from observed events
* 👥 Multiple projections may coexist
* ⏳ Global ordering is not guaranteed

This approach allows the system to reason about:

* ⚠️ Message loss
* 🔁 Duplication
* ⏱ Delayed delivery
* 🛠 Reconciliation

---

## Convergence and uncertainty 🌊

A participant’s timeline:

* may be incomplete
* may evolve over time
* may converge without ever being “final”

The system explicitly embraces:

* ❌ Absence of global ordering
* ❌ Absence of guaranteed completeness
* ⚖️ Uncertainty as a normal state

A **quiet period** 💤 (an interval with no newly observed relevant events)
is used as a heuristic for local stability, never as an absolute truth.

---

## Runtime and supervision 🧠🛡️

The runtime is responsible for:

* 🧵 Running concurrent workers
* 📬 Dispatching commands
* 📢 Broadcasting events
* ♻️ Restarting failed components

Supervision is explicit: failures are expected, isolated, and observable.

---

## Moderation pipeline 🧹

Incoming messages may pass through a moderation pipeline:

* 🔤 Text normalization
* 🚫 Pattern matching / filtering
* ✂️ Censoring or rejection

Moderation **does not mutate past events** — it only affects whether new events are emitted.

---

## Persistence and Protobuf 📦

Some parts of the system rely on **Protocol Buffers** for message serialization,
not as a network contract, but as a **stable and explicit disk representation**.

The Protobuf definitions live under the `proto/` directory.

### Generate Protobuf code

The Go code is generated using `protoc` via Docker:

```bash
docker run --rm -v "$PWD:/defs" protoc-image \
  -I . \
  --go_out=paths=source_relative:. \
  --go-grpc_out=paths=source_relative:. \
  proto/message.proto
```

This keeps the environment reproducible and avoids installing protoc locally.

---

## Observation and UI 👀🖥️

The user interface (to be introduced later) is treated as:

* 👁 An observer
* 📡 An event consumer
* 🚫 Never a decision-maker for the domain

It does not control the system. It reflects a local, potentially imperfect view.

This separation is deliberate and fundamental 🧱.

---

## Current project status 🛠️

The project is actively evolving:

* ✅ Domain and runtime implemented
* ✅ Event flows observable
* 🧪 Focus on robustness, tests, and invariants
* 🖥️ UI planned as a thin observational layer

---

## Inspirations 💡

* 🤖 Distributed “robot” secret reconstruction exercise
* 🌐 Event-driven systems
* 📡 Gossip and anti-entropy protocols
* 🔄 Eventually consistent architectures
* 👁 Observable and reactive UIs (e.g., TUIs)
