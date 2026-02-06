```mermaid
graph BT
%% Zones
    subgraph Layer3 ["Layer 3: Storage & Search (Persistence)"]
        Badger[("🦡 BadgerDB")]
        Bluge[("🔍 Bluge Index")]
        Sink[("🛡️ AnalysisSink")]

        Sink --> Badger
        Badger -.-> Bluge
    end

    subgraph Layer2 ["Layer 2: Intelligence & Brain"]
        Coord[("🧠 Coordinator")]
        
        %% Nouveau composant de monitoring
        Monitor[("📊 Health Monitor / Telemetry")]

        subgraph Sidecars ["Async Sidecars (Python/CGo)"]
            PythonAI[("🤖 Audio / PDF / Business Specialists")]
        end

        Coord <--> PythonAI
        
        %% Flux de monitoring (Priorité 3 & 4)
        Monitor -- "Watch PID (CPU/RAM)" --> PythonAI
        Monitor -- "Lead Time / Health" --> Coord
    end

    subgraph Layer1 ["Layer 1: Ingestion (Real-time)"]
        gRPC_Srv[("🚀 gRPC Server (Stream Bidi)")]
        Fanout[("⚡ Event Fanout Worker")]

        gRPC_Srv --> Fanout
    end

    subgraph Client ["Client Zone (Mobile / Remote)"]
        Scanner[("📂 Disk Scanner")]
    end

%% Flows - Ingestion
    Scanner -- "1. Meta-data" --> gRPC_Srv
    Fanout -- "2. Notify Decision" --> Coord

%% Flows - La Boucle de "Pull" (Bleue)
    Coord -.-> |"3. ShouldRequestContent?"| gRPC_Srv
    gRPC_Srv -.-> |"4. Command: SEND_CONTENT"| Scanner
    Scanner -.-> |"5. Push Bytes"| gRPC_Srv

%% Flows - Enrichissement et Télémétrie
    gRPC_Srv -- "6. Full Data" --> Coord
    Coord -- "7. Enriched Event" --> Sink
    
    %% Backpressure (Priorité 5)
    Coord -.-> |"8. Slow Down / Backpressure"| gRPC_Srv

%% Styling
    style Scanner fill:#f9f,stroke:#333
    style Coord fill:#ffd,stroke:#333,stroke-width:3px
    style Monitor fill:#ff9,stroke:#f66,stroke-dasharray: 5 5
    style gRPC_Srv fill:#dfd,stroke:#333
    style Sink fill:#bbf,stroke:#333
    style PythonAI fill:#e1f5fe,stroke:#01579b

    linkStyle 2,3,4 stroke:#3498db,stroke-width:2px;
    linkStyle 9,10 stroke:#e74c3c,stroke-width:2px;
````