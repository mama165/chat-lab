import argparse
import os
import tempfile
import grpc
from concurrent import futures
from faster_whisper import WhisperModel

# Import generated files
from proto.specialist import specialist_service_pb2 as pb
from proto.specialist import specialist_service_pb2_grpc as pb_grpc

class AudioSpecialist(pb_grpc.SpecialistServiceServicer):
    def __init__(self):
        # 'base' is fast and light. 'int8' allow to run on CPU without GPU
        print("⏳ Loading Whisper model...")
        self.model = WhisperModel("base", device="cpu", compute_type="int8")
        print("✅ Model loaded.")

    def AnalyzeStream(self, request_iterator, context):
        data_buffer = bytearray()
        metadata = None

        for req in request_iterator:
            if req.HasField('metadata'):
                metadata = req.metadata
            if req.HasField('chunk'):
                data_buffer.extend(req.chunk)

        # Whisper needs a physical file to work
        with tempfile.NamedTemporaryFile(suffix=".tmp", delete=False) as tmp:
            tmp.write(data_buffer)
            tmp_path = tmp.name

        try:
            # Automatic transcription (auto detect the language)
            segments, info = self.model.transcribe(tmp_path, beam_size=5)
            full_text = " ".join([segment.text for segment in segments])

            #  Return the result as DocumentData (Pages) which is understandable for Go
            return pb.SpecialistResponse(
                audio=pb.AudioDetails(
                    transcription=full_text.strip(),
                    duration_sec=0 # ou la vraie durée si tu l'as
                )
            )
        finally:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)

def serve():
    parser = argparse.ArgumentParser()
    parser.add_argument("-port", type=int, default=50056)
    parser.add_argument("-id", type=str)
    parser.add_argument("-type", type=str)
    parser.add_argument("-level", type=str)
    args = parser.parse_args()

    server = grpc.server(futures.ThreadPoolExecutor(max_workers=5))
    pb_grpc.add_SpecialistServiceServicer_to_server(AudioSpecialist(), server)
    server.add_insecure_port(f'[::]:{args.port}')

    print(f"🎙️ Audio Specialist ({args.id}) started on port {args.port}")
    server.start()
    server.wait_for_termination()

if __name__ == "__main__":
    serve()