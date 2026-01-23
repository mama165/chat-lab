```mermaid
graph BT
%% Zones
    subgraph Layer3 ["Layer 3: Storage & Search (Top)"]
        Badger[("🦡 BadgerDB")]
        Bluge[("🔍 Bluge Index")]
        Sink[("🛡️ AnalysisSink")]

        Sink --> Badger
        Badger -.-> Bluge
    end

    subgraph Layer2 ["Layer 2: Intelligence & Brain"]
        Coord[("🧠 Coordinator")]

        subgraph Sidecars ["Async Sidecars (Python/Docker)"]
            PythonAI[("🤖 Toxicity / Sentiment / AI")]
        end

        Coord <--> PythonAI
    end

    subgraph Layer1 ["Layer 1: Ingestion (Bottom)"]
        gRPC_Srv[("🚀 gRPC Server (Stream Bidi)")]
        Fanout[("⚡ Event Fanout Worker")]

        gRPC_Srv --> Fanout
    end

    subgraph Client ["Client Zone (Remote PC)"]
        Scanner[("📂 Disk Scanner")]
    end

%% Flows - Ingestion Initiale
    Scanner -- "1. Meta-data" --> gRPC_Srv
    Fanout -- "2. Notify Decision" --> Coord

%% Flows - La Boucle de "Pull" (Bleue)
    Coord -.-> |"3. ShouldRequestContent?"| gRPC_Srv
    gRPC_Srv -.-> |"4. Command: SEND_CONTENT"| Scanner
    Scanner -.-> |"5. Push Bytes"| gRPC_Srv

%% Flows - Enrichissement et Stockage
    gRPC_Srv -- "6. Full Data" --> Coord
    Coord -- "7. Enriched Event" --> Sink

%% Styling
    style Scanner fill:#f9f,stroke:#333
    style Coord fill:#ffd,stroke:#333,stroke-width:3px
    style gRPC_Srv fill:#dfd,stroke:#333
    style Sink fill:#bbf,stroke:#333

    linkStyle 2,3,4 stroke:#3498db,stroke-width:2px;
````