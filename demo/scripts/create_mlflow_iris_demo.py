import time
import mlflow
import mlflow.sklearn

from mlflow.tracking import MlflowClient
from sklearn.datasets import load_iris
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, f1_score
from sklearn.model_selection import train_test_split

TRACKING_URI = "http://127.0.0.1:5000"
MODEL_NAME = "aigen_demo_iris"

mlflow.set_tracking_uri(TRACKING_URI)
client = MlflowClient(TRACKING_URI)

X, y = load_iris(return_X_y=True)
X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.25, random_state=42, stratify=y
)

clf = LogisticRegression(max_iter=200, random_state=42)
clf.fit(X_train, y_train)

pred = clf.predict(X_test)
accuracy = accuracy_score(y_test, pred)
f1 = f1_score(y_test, pred, average="macro")

with mlflow.start_run(run_name="aigen_iris_demo_run") as run:
    mlflow.log_param("model_family", "logistic_regression")
    mlflow.log_param("dataset", "iris")
    mlflow.log_param("max_iter", 200)
    mlflow.log_param("random_state", 42)

    mlflow.log_metric("accuracy", accuracy)
    mlflow.log_metric("macro_f1", f1)

    mlflow.sklearn.log_model(
        sk_model=clf,
        artifact_path="model",
        registered_model_name=MODEL_NAME,
    )

for _ in range(30):
    versions = client.search_model_versions(f"name='{MODEL_NAME}'")
    if versions:
        break
    time.sleep(1)

versions = client.search_model_versions(f"name='{MODEL_NAME}'")
latest = max(versions, key=lambda v: int(v.version))
version = latest.version

description = """
Demo Iris classifier used to showcase AIGen AIBOM generation.

~spdxStart~
~spdxF~domain:classification~tabular data
~spdxF~informationAboutApplication:Predicts iris species from sepal and petal measurements
~spdxF~informationAboutTraining:Trained on the Iris dataset using logistic regression
~spdxF~typeOfModel:Logistic Regression classifier
~spdxF~limitation:Demonstration model only; not intended for production use
~spdxF~modelExplainability:Coefficient inspection
~spdxF~standardCompliance:SPDX 3.0.1
~spdxEnd~
"""

client.update_model_version(
    name=MODEL_NAME,
    version=version,
    description=description,
)

client.set_model_version_tag(MODEL_NAME, version, "declaredLicense", "MIT")
client.set_model_version_tag(MODEL_NAME, version, "downloadLocation", f"models:/{MODEL_NAME}/{version}")
client.set_model_version_tag(MODEL_NAME, version, "useSensitivePersonalInformation", "false")

print(f"Registered MLflow model: {MODEL_NAME}")
print(f"Version: {version}")
print(f"Accuracy: {accuracy:.4f}")
print(f"Macro F1: {f1:.4f}")
