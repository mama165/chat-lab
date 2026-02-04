# services/image_specialist.py
import grpc
from concurrent import futures
import io
from PIL import Image

# Importing generated gRPC classes
from proto.specialist import specialist_service_pb2
from proto.specialist import specialist_service_pb2_grpc

class ImageSpecialist(specialist_service_pb2_grpc.SpecialistServiceServicer):
    def Analyze(self, request, context):
        # Business metadata extraction
        print(f"📸 Received image: {len(request.content)} bytes")
        try:
            img = Image.open(io.BytesIO(request.content))
            width, height = img.size

            # Priority 2: Business Statistics
            analysis_result = f"Format: {img.format} | Res: {width}x{height} | Mode: {img.mode}"

            return specialist_service_pb2.AnalyzeResponse(
                success=True,
                analysis_result=analysis_result,
                error_message=""
            )
        except Exception as e:
            return specialist_service_pb2.AnalyzeResponse(
                success=False,
                error_message=str(e)
            )

def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=5))
    specialist_service_pb2_grpc.add_SpecialistServiceServicer_to_server(ImageSpecialist(), server)
    server.add_insecure_port('[::]:50057')
    print("🚀 Image Specialist ready on port 50057")
    server.start()
    server.wait_for_termination()

if __name__ == "__main__":
    serve()