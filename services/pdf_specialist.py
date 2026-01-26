import grpc
import fitz  # PyMuPDF
from concurrent import futures
import traceback

# Import generated files
from proto.specialist import specialist_service_pb2 as pb
from proto.specialist import specialist_service_pb2_grpc as pb_grpc

class MonitorService(pb_grpc.SpecialistServiceServicer):

    def AnalyzeStream(self, request_iterator, context):
        # Buffer to accumulate PDF binary data from stream
        pdf_data = bytearray()
        file_metadata = {"message_id": "N/A", "file_name": "unknown"}

        try:
            print("--- 📥 Stream received from Go Master ---")

            for request in request_iterator:
                # Identify which field of the 'entry' oneof is set
                field = request.WhichOneof('entry')

                if field == 'metadata':
                    file_metadata["message_id"] = getattr(request.metadata, 'message_id', 'N/A')
                    file_metadata["file_name"] = getattr(request.metadata, 'file_name', 'unknown')
                    print(f"📄 Processing document: {file_metadata['file_name']}")

                elif field == 'chunk':
                    # Append incoming bytes to our buffer
                    pdf_data.extend(request.chunk)

            # Once the stream is closed, process the complete PDF from memory
            print(f"⚙️ Extracting data from PDF ({len(pdf_data)} bytes)...")

            # Open PDF using stream instead of a physical file
            with fitz.open(stream=pdf_data, filetype="pdf") as doc:
                pages_list = []

                # Extract text from the first 5 pages for testing purposes
                for i in range(min(len(doc), 5)):
                    page = doc.load_page(i)
                    pages_list.append(pb.Page(
                        number=i + 1,
                        content=page.get_text()
                    ))

                print(f"✅ Successfully extracted {len(doc)} pages.")

                # Return DocumentData matching your Go ToResponse expectations
                return pb.SpecialistResponse(
                    document_data=pb.DocumentData(
                        title=doc.metadata.get('title') or file_metadata["file_name"],
                        author=doc.metadata.get('author') or "Unknown",
                        page_count=len(doc),
                        language="fr",
                        pages=pages_list
                    )
                )

        except Exception as e:
            print(f"❌ CRITICAL ERROR: {e}")
            traceback.print_exc()
            # Propagate gRPC error to the client
            context.abort(grpc.StatusCode.INTERNAL, str(e))

def serve():
    # Initialize gRPC server with a thread pool
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    pb_grpc.add_SpecialistServiceServicer_to_server(MonitorService(), server)

    server.add_insecure_port('[::]:50055')
    print("🚀 Python PDF Specialist ready on port 50055")
    server.start()
    server.wait_for_termination()

if __name__ == '__main__':
    serve()