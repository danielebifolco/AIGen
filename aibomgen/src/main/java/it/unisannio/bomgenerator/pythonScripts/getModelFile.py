import argparse as ap

import mlflow as ml

# retrieves a model from the MLflow tracking server and downloads it to a local directory

parser = ap.ArgumentParser(description="Test MLflow model download")
parser.add_argument("tracking_server_IP", type=str, help="MLflow tracking server URI")
parser.add_argument("tracking_server_port", type=str, help="MLflow tracking server port")
parser.add_argument("model_name", type=str, help="Name of the MLflow model")
parser.add_argument("model_version", type=str, help="Version of the MLflow model")

args = parser.parse_args()

ml.set_tracking_uri(f"http://{args.tracking_server_IP}:{args.tracking_server_port}")

arti = ml.artifacts.download_artifacts(
    artifact_uri=f"models:/{args.model_name}/{args.model_version}", dst_path="./tmp")
