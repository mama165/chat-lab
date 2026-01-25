import grpc
from concurrent import futures
import traceback
from proto.analysis import specialist_service_pb2 as pb
from proto.analysis import specialist_service_pb2_grpc as pb_grpc

class MonitorService(pb_grpc.SpecialistServiceServicer):

    def AnalyzeStream(self, request_iterator, context):
        total_bytes = 0
        chunks_count = 0
        try:
            print("--- 📥 Requête reçue du Master Go ---")

            for request in request_iterator:
                field = request.WhichOneof('entry')

                print(f"DEBUG: Champ reçu = '{field}'") # <--- LANCE ET REGARDE CE LOG

                if field == 'metadata':
                    # Utilisons getattr pour être sûrs de ne pas crasher si le nom varie
                    m_id = getattr(request.metadata, 'message_id', 'N/A')
                    f_name = getattr(request.metadata, 'file_name', 'unknown')
                    print(f"📄 Metadata reçue -> ID: {m_id}, Fichier: {f_name}")

                elif field == 'chunk':
                    # Vérifie si le champ s'appelle 'Chunk' ou 'chunk' dans ton proto
                    chunk_data = request.chunk
                    total_bytes += len(chunk_data)
                    chunks_count += 1
                    if chunks_count % 10 == 0: # Évite de polluer si trop de chunks
                        print(f"📥 Reçu {chunks_count} chunks...")

            print(f"✅ Stream terminé. Total: {total_bytes} octets, {chunks_count} chunks.")

            return pb.SpecialistResponse(
                score=pb.Score(
                    score=0.95,
                    label=f"Analyse réussie ({total_bytes} octets)"
                )
            )

        except Exception as e:
            print(f"❌ ERREUR CRITIQUE : {e}")
            traceback.print_exc()
            context.abort(grpc.StatusCode.INTERNAL, str(e))

def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    pb_grpc.add_SpecialistServiceServicer_to_server(MonitorService(), server)
    server.add_insecure_port('[::]:50051')
    print("🚀 Spécialiste Python prêt sur le port 50051")
    server.start()
    server.wait_for_termination()

if __name__ == '__main__':
    serve()