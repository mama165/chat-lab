```mermaid
classDiagram
direction LR

    class Specialist_gRPC_Proto {
        <<Protobuf>>
        +DocumentData document_data
        +Page[] pages
        +string title
        +string author
    }

    class Domain_Specialist {
        <<Go Struct>>
        +DocumentData DocumentData
        +Page[] Pages
        +string Title
        +string Author
    }

    class Storage_Analysis_Proto {
        <<Protobuf>>
        +string ID (UUID)
        +string EntityId (UUID)
        +string Summary
        +oneof Payload
        +FileDetails file
    }

    class Storage_FileDetails_Proto {
        <<Protobuf>>
        +string filename
        +string content (Extracted Text)
        +string author
        +int32 page_count
    }

    class Infrastructure_Repository {
        <<Go Repository>>
        +Store(Analysis)
        +ToAnalysis(pb.Analysis)
        +fromAnalysis(Analysis)
    }

    %% Relations de flux
    Specialist_gRPC_Proto ..> Domain_Specialist : "1. ToResponse() mapping"
    Domain_Specialist ..> Storage_Analysis_Proto : "2. Integration via AnalysisSink"
    Storage_Analysis_Proto *-- Storage_FileDetails_Proto : "Contains"
    Storage_Analysis_Proto ..> Infrastructure_Repository : "3. Marshal / Unmarshal"
    Infrastructure_Repository --> BadgerDB : "4. KV Store (Binary)"
    Infrastructure_Repository --> Bluge : "5. Full-Text Index (Search)"

    note for Specialist_gRPC_Proto "Used for Go <-> Python communication"
    note for Storage_Analysis_Proto "Persistent format in BadgerDB"
    note for Infrastructure_Repository "Converts Domain <-> Proto <-> Search Index"
````
