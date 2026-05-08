# AIGen

## Description

**AIGen** is a Java-based tool for generating AI Bills of Materials (AIBOMs)
using the SPDX 3.0.1 JSON-LD format. The tool collects metadata from common
MLOps and development platforms through configurable builders, combines the
retrieved fields into AI and dataset SPDX packages, and serializes the resulting
AIBOM to a machine-readable JSON-LD document.

The current implementation provides builders for:

- **MLflow**: extracts structured model metadata, run metadata, parameters, and
  metrics.
- **GitHub**: retrieves training scripts and documentation, optionally using a
  local LLM to synthesize natural-language AIBOM fields.
- **Hugging Face Hub**: retrieves model repository metadata, model-card content,
  license tags, framework tags, and evaluation metrics.
- **Kaggle**: retrieves dataset metadata and dataset samples to populate dataset
  package fields.

## Prerequisites

- Java 21
- Maven
- An MLflow tracking server containing the model configured in the MLflow agent
  YAML file
- Ollama, if LLM-assisted fields are enabled in the pipeline configuration
- Kaggle API access, if a Kaggle dataset builder is enabled
- GitHub repository access, if a GitHub builder is enabled
- Hugging Face Hub access, if a Hugging Face builder is enabled for private or
  gated models

Optional external services depend on the builders selected in `AIbomPipe.yaml`.
For example, a pipeline that only uses MLflow does not need Kaggle credentials.

## Installation

Clone the repository and compile the Maven project:

```sh
git clone https://github.com/maelstromdat/SPDX-AIBOM-generator.git
cd SPDX-AIBOM-generator
mvn -f aibomgen/pom.xml -q -DskipTests compile
```

To check that the command-line entry point is available:

```sh
mvn -f aibomgen/pom.xml -q exec:java -Dexec.args="--help"
```

## Configuration Overview

AIGen is driven by a two-level YAML configuration:

1. **Pipeline configuration**: defines the global execution plan.
2. **Agent configuration files**: define source-specific connection parameters.

By default, the pipeline configuration is:

```text
aibomgen/src/main/java/it/unisannio/bomgenerator/AIbomPipe.yaml
```

The agent-specific configuration files are stored in:

```text
aibomgen/src/main/java/it/unisannio/bomgenerator/agentsConfig/
```

When a custom `AIbomPipe.yaml` is passed with `--config`, AIGen first looks for
an `agentsConfig` directory next to that pipeline file. If none exists, it falls
back to the default `agentsConfig` directory above.

Configuration values may reference environment variables with `${NAME}` syntax.
For example:

```yaml
token: "${KAGGLE_TOKEN}"
```

## Environment Variables

- `KAGGLE_TOKEN`: value used by the Kaggle builder for the HTTP
  `Authorization: Basic` header. If you use standard Kaggle username/key
  credentials, set this to the base64-encoded `username:key` value.
- `AIGEN_LLM_TIMEOUT_SECONDS`: timeout in seconds for Ollama HTTP requests.
  Defaults to `60`.
- `HF_TOKEN`: optional Hugging Face access token used for private or gated model
  repositories.

Example:

```sh
export KAGGLE_TOKEN="<base64-encoded-kaggle-credentials>"
export AIGEN_LLM_TIMEOUT_SECONDS=60
export HF_TOKEN="<hugging-face-access-token>"
```

If you configure a GitHub access token in an agent file, you can also use an
environment variable, for example:

```yaml
accessToken: "${GITHUB_TOKEN}"
```

## Pipeline Configuration

`AIbomPipe.yaml` defines authors, the SPDX version, the optional LLM backend,
and the teams of builders to execute.

Example:

```yaml
authors:
  - name: "Author One"
    email: "author.one@example.org"

LLMClient: "OllamaClient"
LLMServer: "http://localhost:11434"
LLMModel: "deepseek-r1"
spdxVersion: "3"

teams:
  - teamName: "ModelTeam"
    type: "AI"
    agents:
      - agentName: "MLFlowAIBuilder"
        codeName: "agent1"
        priority: 0
        goals:
          - fieldName: "*"
            priority: 1

      - agentName: "SpdxV3GitHubAIBuilder"
        codeName: "agent2"
        priority: 0
        goals:
          - fieldName: "*"
            priority: 0

  - teamName: "DatasetPipe"
    type: "Dataset"
    tags:
      - "test"
    agents:
      - agentName: "KaggleDatasetBuilder"
        codeName: "agent3"
        priority: 0
        goals:
          - fieldName: "*"
            priority: 0
```

### Pipeline Fields

- `authors`: list of creators added to the SPDX creation information.
- `LLMClient`: Java class name of the LLM client. Currently supported:
  `OllamaClient`.
- `LLMServer`: URL of the LLM server, for example `http://localhost:11434`.
- `LLMModel`: model name served by Ollama.
- `spdxVersion`: SPDX serializer version. Currently supported: `3`.
- `teams`: logical groups of builders. Each team produces one SPDX package.
- `type`: package type produced by the team. Supported values: `AI`,
  `Dataset`.
- `tags`: dataset role used to create relationships from the AI package to the
  dataset package. Supported values: `train`, `test`.
- `agentName`: Java builder class to instantiate.
- `codeName`: name of the builder-specific YAML file without extension. For
  example, `codeName: "agent1"` maps to `agentsConfig/agent1.yaml`.
- `goals`: fields the builder should produce.
- `fieldName`: SPDX/AIBOM field name without the `add` prefix. Use `*` to invoke
  all implemented fields for that builder.
- `priority`: lower values run first when multiple builders can produce the same
  field. If a lower-priority-number builder successfully produces a field, later
  builders are skipped for that field.

## Agent Configuration

Each agent file configures a concrete builder. The file name must match the
`codeName` used in `AIbomPipe.yaml`.

### MLflow Builder

Default file:

```text
aibomgen/src/main/java/it/unisannio/bomgenerator/agentsConfig/agent1.yaml
```

Example:

```yaml
modelName: "your_registered_model_name"
modelVersion: "1"
mlFlowServerIP: "localhost"
mlFlowServerPort: "5000"
declaredLicense: ""
trainingScriptsPath: ""
applicationDocumentationPath: ""
```

Fields:

- `modelName`: registered MLflow model name.
- `modelVersion`: model version to retrieve.
- `mlFlowServerIP`: MLflow tracking server host.
- `mlFlowServerPort`: MLflow tracking server port.
- `declaredLicense`: optional declared license override. If empty, AIGen checks
  MLflow model-version tags such as `declaredLicense`, `license`, and
  `licenseName`, then falls back to license artifacts when available.
- `trainingScriptsPath`: optional local directory containing training scripts
  associated with the model.
- `applicationDocumentationPath`: optional local directory containing
  application documentation associated with the model.

The MLflow builder currently extracts fields such as package name, version,
build time, release time, supplier, declared license, hyperparameters, metrics,
download location, primary purpose, sensitive-personal-information tag, and
optional text fields encoded in the model description.

### GitHub Builder

Default file:

```text
aibomgen/src/main/java/it/unisannio/bomgenerator/agentsConfig/agent2.yaml
```

Example:

```yaml
userRepoString: "owner/repository"
branch: "main"
accessToken: "${GITHUB_TOKEN}"

trainingFiles:
  - "path/to/training_script.py"
  - "path/to/notebook.ipynb"

preprocessingFiles:
  - "path/to/preprocessing.py"

applicationFiles:
  - "README.md"
  - "docs/model_usage.md"
```

Fields:

- `userRepoString`: GitHub repository in `owner/repo` format.
- `branch`: branch to read files from.
- `accessToken`: optional GitHub token. Leave empty for public repositories, or
  use `${GITHUB_TOKEN}`.
- `trainingFiles`: source files used by the LLM to infer model domain, training
  information, model type, limitations, and explainability fields.
- `preprocessingFiles`: preprocessing files to retrieve. This field is currently
  reserved for preprocessing-specific extensions.
- `applicationFiles`: documentation files used to infer application-level
  information.

The GitHub builder supports plain text/code files and Jupyter notebooks. For
`.ipynb` files, AIGen extracts only `code` and `markdown` cell sources before
sending the content to the LLM.

### Hugging Face Hub Builder

Default file:

```text
aibomgen/src/main/java/it/unisannio/bomgenerator/agentsConfig/agent5.yaml
```

Example:

```yaml
modelId: "owner/model-name"
revision: "main"
token: "${HF_TOKEN}"
hubBaseUrl: "https://huggingface.co"
configFile: "config.json"
timeoutSeconds: 30

buildTime: ""
releaseTime: ""
declaredLicense: ""
suppliedBy: ""
domains: []
modelTypes: []
informationAboutTrainingData: ""
informationAboutApplication: ""
limitations: ""
```

Fields:

- `modelId`: Hugging Face model repository identifier in `owner/model-name`
  format.
- `revision`: branch, tag, or commit hash to inspect. Use a commit hash when you
  need a reproducible AIBOM for a fixed model state.
- `token`: optional Hugging Face token. Leave empty for public repositories, or
  use `${HF_TOKEN}` for private/gated repositories.
- `hubBaseUrl`: Hugging Face Hub base URL. Defaults to
  `https://huggingface.co`.
- `configFile`: model configuration file to inspect for framework/model-type
  hints. Defaults to `config.json`.
- `timeoutSeconds`: HTTP timeout for Hugging Face API requests.
- `buildTime`, `releaseTime`, `declaredLicense`, `suppliedBy`, `domains`,
  `modelTypes`, `informationAboutTrainingData`, `informationAboutApplication`,
  and `limitations`: optional overrides used when Hub metadata or model-card
  sections are missing or too generic.

To enable the builder, add it to an AI team in `AIbomPipe.yaml`:

```yaml
- agentName: "HuggingFaceAIBuilder"
  codeName: "agent5"
  priority: 0
  goals:
    - fieldName: "*"
      priority: 0
```

The Hugging Face builder retrieves model metadata from the Hub API and reads the
model card when available. It can populate package name, package version,
download location, declared license, supplier, build/release time, domain,
model type, training information, application information, limitations, and
model-card metrics. Framework support is metadata-based: PyTorch, TensorFlow,
Transformers, Diffusers, ONNX, and related frameworks are detected from Hub tags
or model configuration files without loading model weights.

### Kaggle Dataset Builder

Default file:

```text
aibomgen/src/main/java/it/unisannio/bomgenerator/agentsConfig/agent3.yaml
```

Example:

```yaml
datasetName: "your-dataset-slug"
userName: "dataset-owner"
token: "${KAGGLE_TOKEN}"
```

Fields:

- `datasetName`: Kaggle dataset slug.
- `userName`: Kaggle dataset owner.
- `token`: authorization token used by the Kaggle API request. The default
  configuration expects `${KAGGLE_TOKEN}`.

The Kaggle builder retrieves dataset metadata, downloads the dataset archive,
extracts a small CSV head, and uses metadata/LLM inference to populate dataset
fields such as package name, version, originator, release time, build time,
download location, declared license, dataset type, dataset size, intended use,
anonymization method, and data collection process.

## How to Use

1. Start the external services required by your pipeline.

   For the default configuration, this means:

   - an MLflow tracking server at `http://localhost:5000`;
   - an Ollama server at `http://localhost:11434` serving the configured model;
   - valid Kaggle credentials in `KAGGLE_TOKEN`;
   - network access to the configured GitHub repository.

2. Fill or copy the pipeline file:

   ```text
   aibomgen/src/main/java/it/unisannio/bomgenerator/AIbomPipe.yaml
   ```

3. Fill the agent files referenced by `codeName`:

   ```text
   aibomgen/src/main/java/it/unisannio/bomgenerator/agentsConfig/agent1.yaml
   aibomgen/src/main/java/it/unisannio/bomgenerator/agentsConfig/agent2.yaml
   aibomgen/src/main/java/it/unisannio/bomgenerator/agentsConfig/agent3.yaml
   ```

   If you enable the Hugging Face builder, also fill:

   ```text
   aibomgen/src/main/java/it/unisannio/bomgenerator/agentsConfig/agent5.yaml
   ```

4. Run AIGen:

   ```sh
   mvn -f aibomgen/pom.xml exec:java \
     -Dexec.args="--config aibomgen/src/main/java/it/unisannio/bomgenerator/AIbomPipe.yaml --output AIBOM.json"
   ```

5. Inspect the generated AIBOM:

   ```sh
   less AIBOM.json
   ```

Logs are printed to the console and written to:

```text
logs/bomMaker.log
```

## Command-Line Options

```text
Usage: mvn exec:java -Dexec.args="[options]"

Options:
  -c, --config <path>   Pipeline YAML configuration path.
  -o, --output <path>   Output SPDX JSON-LD file path.
  -h, --help            Show help.
```

Example with a custom configuration and output path:

```sh
mvn -f aibomgen/pom.xml exec:java \
  -Dexec.args="--config examples/my-pipeline/AIbomPipe.yaml --output out/my-aibom.json"
```

## Evaluation Data

The `evaluation/` directory contains the preliminary evaluation material used
for the paper:

- `lists_of_projects.csv`: projects included in the preliminary evaluation.
- `Quality_AI.csv`: quality ratings for generated AI-package fields.
- `Quality_Datasets.csv`: quality ratings for generated dataset-package fields.

The rating legend is documented in `evaluation/README.md`.

## License

This project is licensed under the Apache License 2.0. See `LICENSE`.

Third-party dependencies are resolved through Maven and remain under their
respective licenses.
