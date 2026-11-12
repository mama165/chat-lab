import argparse
import io
import logging
import sys
import traceback
from concurrent import futures

import grpc
from PIL import Image

# Import generated files
from proto.specialist import specialist_service_pb2 as pb
from proto.specialist import specialist_service_pb2_grpc as pb_grpc

log = logging.getLogger("image")


class ImageSpecialist(pb_grpc.SpecialistServiceServicer):

    def AnalyzeStream(self, request_iterator, context):
        # The Go side streams a metadata message, then the file in chunks.
        data = bytearray()
        file_name = "unknown"

        try:
            for request in request_iterator:
                field = request.WhichOneof('entry')
                if field == 'metadata':
                    file_name = request.metadata.file_name or file_name
                elif field == 'chunk':
                    data.extend(request.chunk)

            log.info("Reading image %s (%d bytes)", file_name, len(data))

            # Pillow reads the header only: the pixels are not decoded.
            with Image.open(io.BytesIO(data)) as img:
                return pb.SpecialistResponse(
                    image=pb.ImageData(
                        width=img.width,
                        height=img.height,
                        format=img.format or "",
                        mode=img.mode,
                    )
                )

        except Exception as e:
            log.error("Unable to read image %s: %s", file_name, e)
            traceback.print_exc(file=sys.stdout)
            # Propagate gRPC error to the client
            context.abort(grpc.StatusCode.INTERNAL, str(e))


def serve():
    parser = argparse.ArgumentParser()
    parser.add_argument('-id', type=str, default='image')
    parser.add_argument('-port', type=int, default=50057)
    parser.add_argument('-type', type=str)
    parser.add_argument('-level', type=str, default='INFO')
    args = parser.parse_args()

    # stdout, not stderr: the Go program logs the standard error of a specialist as an error.
    logging.basicConfig(stream=sys.stdout, level=args.level.upper(), format='%(message)s')

    server = grpc.server(futures.ThreadPoolExecutor(max_workers=5))
    pb_grpc.add_SpecialistServiceServicer_to_server(ImageSpecialist(), server)
    server.add_insecure_port(f'[::]:{args.port}')

    log.info("Image specialist (%s) ready on port %d", args.id, args.port)
    server.start()
    server.wait_for_termination()


if __name__ == '__main__':
    serve()
