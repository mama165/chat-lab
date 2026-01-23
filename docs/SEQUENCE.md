```mermaid
sequenceDiagram
participant S as Scanner (Client)
participant G as gRPC Server
participant O as Orchestrator
participant C as Coordinator
participant P as Python Sidecar

    S->>G: 1. Send Meta-data (FileAnalyse)
    G->>O: 2. Dispatch to fileAnalyzeChan
    O->>C: 3. ShouldRequestContent(mime)?
    C-->>O: 4. Yes (is text/pdf)
    O->>G: 5. Trigger Pull Command
    G->>S: 6. SEND_CONTENT Command
    S->>G: 7. Push Content (Bytes)
    G->>O: 8. Update Event with Content
    O->>C: 9. ProcessFileEnrichment()
    C->>P: 10. AnalyzeAll (Fan-out)
    P-->>C: 11. Analysis Results
    C-->>O: 12. Enriched Event
    O->>Sink: 13. Async Save (Upsert)
````