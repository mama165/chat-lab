```mermaid
graph LR
    subgraph Ingestion_Layer ["Layer 1: Ingestion"]
        GRPC[gRPC Stream Server]
    end

    subgraph Dispatch_Buffer ["Buffering (Channel Buffers)"]
        MsgChan["messageChan<br/>(Size: 1000)"]
        FileChan["fileAnalyzeChan<br/>(Size: 100)"]
    end

    subgraph Worker_Pool ["Parallel Processing (Workers)"]
        direction TB
        W1[Worker 1]
        W2[Worker 2]
        WN[Worker N]
    end

    subgraph Safety_Mechanisms ["Control & Telemetry"]
        Monitor["Channel Monitor<br/>(Check: 5s)"]
        Sema["Semaphore<br/>(Max In-Flight)"]
    end

    subgraph Downstream ["Consumers & Enrichment"]
        Coord["🧩 SpecialistCoordinator<br/>(Broadcast)"]
        Sink["🛡️ AnalysisSink<br/>(Upsert Badger/Bluge)"]
    end

%% Flows
    GRPC -->|Push| MsgChan
    GRPC -->|Push| FileChan

    MsgChan -.->|Read| W1 & W2 & WN
    FileChan -.->|Read| W1 & W2 & WN

    W1 & W2 & WN <-->|Acquire/Release| Sema
    W1 & W2 & WN -->|Broadcast| Coord
    W1 & W2 & WN -->|Batch Store| Sink

    Monitor -.->|Observe Capacity| MsgChan
    Monitor -.->|Observe Capacity| FileChan
````