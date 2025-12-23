# chat-lab 💬🧪

chat-lab is a **learning and experimentation lab** built around a minimal distributed chat system.  
The goal is not to build a “complete” chat application, but to explore core concepts of
**event-driven systems**, **convergence**, and **state observation**.

---

## Why this project exists 🚀

This project is a direct continuation of a previous exercise involving multiple “robots” 🤖  
communicating with each other to reconstruct a shared state.

Here, a chat system is used as a more **concrete and readable medium** to:

- 🌐 Explore distributed systems without strict central coordination
- 📩 Reason in terms of events rather than mutable shared state
- 👀 Observe progressive convergence of local views
- 🧱 Enforce clear separation between domain, runtime, and UI

chat-lab is first and foremost a **learning playground** 🎓.

---

## What chat-lab is NOT ❌

- ❌ A production-ready chat application
- ❌ A system with strong global consistency guarantees
- ❌ A Slack, Discord, or Matrix clone
- ❌ A feature-driven or UX-focused project
- ❌ A complex multi-room messaging system

The objective is **conceptual clarity**, not feature completeness ✨.

---

## Conceptual model 🧩

The system is built around a small set of core concepts:

- **Participant** 👤  
  An actor in the system, uniquely identified, that can be active or inactive.

- **Message** ✉️  
  An immutable event emitted by a participant at a given point in time.

- **Timeline** 🕒  
  A local projection of known messages. It may be incomplete, out of order, or temporarily unstable.

- **Presence** ⚡  
  A derived piece of information (active / inactive), itself based on events.

None of these concepts are designed as a globally shared state.

---

## Events, not state 📢

chat-lab follows a strict principle:

> **The system produces events.  
> State is a local, reversible projection.**

- 📝 Messages are never modified or deleted
- 🔄 Timelines are reconstructed from observed events
- 👥 Multiple projections may coexist
- ⏳ Global ordering is not guaranteed

This approach allows the system to reason about:

- ⚠️ Message loss
- 🔁 Duplication
- ⏱ Delayed delivery
- 🛠 Reconciliation

---

## Convergence and uncertainty 🌊

A participant’s timeline:

- may be incomplete
- may evolve over time
- may converge without ever being “final”

The system explicitly embraces:

- ❌ Absence of global ordering
- ❌ Absence of guaranteed completeness
- ⚖️ Uncertainty as a normal state

A **quiet period** 💤 (an interval with no newly observed relevant events)  
is used as a heuristic for local stability, never as an absolute truth.

---

## Observation and UI 👀🖥️

The user interface (to be introduced later) is treated as:

- 👁 An **observer**
- 📡 An **event consumer**
- 🚫 Never a decision-maker for the domain

It does not control the system. It reflects a **local, potentially imperfect view**.

This separation is deliberate and fundamental 🧱.

---

## Current project status 🛠️

The project is currently in a **design and exploration phase**:

- 🚫 No domain logic implemented yet
- 📝 Concepts defined before technical optimizations
- 🧭 Architecture prioritized over implementation details

---

## Inspirations 💡

- 🤖 Distributed “robot” secret reconstruction exercise
- 🌐 Event-driven systems
- 📡 Gossip and anti-entropy protocols
- 🔄 Eventually consistent architectures
- 👁 Observable and reactive UIs (e.g., TUIs)

---

## License 📝

Experimental project, free to use in a personal or educational context.
