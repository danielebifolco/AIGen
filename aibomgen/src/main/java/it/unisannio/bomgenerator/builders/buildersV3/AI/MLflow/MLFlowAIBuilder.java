// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.builders.buildersV3.AI.MLflow;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import org.mlflow.api.proto.ModelRegistry.ModelVersion;
import org.mlflow.api.proto.ModelRegistry.ModelVersionTag;
import org.mlflow.api.proto.Service.FileInfo;
import org.mlflow.api.proto.Service.Metric;
import org.mlflow.api.proto.Service.Param;
import org.mlflow.api.proto.Service.Run;
import org.mlflow.tracking.MlflowClient;
import org.mlflow.tracking.MlflowClientException;
import org.mlflow.tracking.ModelVersionsPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spdx.core.InvalidSPDXAnalysisException;
import org.spdx.library.model.v3_0_1.SpdxConstantsV3;
import org.spdx.library.model.v3_0_1.core.CreationInfo;
import org.spdx.library.model.v3_0_1.core.PresenceType;
import org.spdx.library.model.v3_0_1.software.ContentIdentifier;
import org.spdx.library.model.v3_0_1.software.ContentIdentifierType;
import org.spdx.library.model.v3_0_1.software.SoftwarePurpose;
import org.spdx.library.model.v3_0_1.software.ContentIdentifier.ContentIdentifierBuilder;

import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import it.unisannio.bomgenerator.PipeManager;
import it.unisannio.bomgenerator.builders.buildersV3.SpdxV3PackageBuilder;
import it.unisannio.bomgenerator.builders.buildersV3.AI.AIPackageBuilderV3;

/**
 * A specialized PackageBuilder implementation that builds SPDX AI Package
 * components
 * by retrieving model information from MLflow tracking servers. This builder
 * connects
 * to an MLflow server to extract model version data, run information,
 * parameters,
 * metrics, and other metadata required for an AI Software Bill of Materials
 * (SBOM).
 * 
 * <p>
 * The builder supports both direct data extraction from MLflow as well as
 * AI-assisted
 * inference of package metadata using an LLM client (when available). It can
 * extract
 * information such as:
 * </p>
 * 
 * <ul>
 * <li>Model name, version, and creation date</li>
 * <li>Model creator information</li>
 * <li>Hyperparameters and metrics</li>
 * <li>Model domain and type</li>
 * <li>Training information and limitations</li>
 * <li>Content identifiers (GITOID format)</li>
 * <li>Model explainability information</li>
 * <li>License information (when available)</li>
 * </ul>
 * 
 * <p>
 * Configuration for the builder is loaded from a YAML file, which should
 * specify
 * the MLflow server connection details, model name, and other optional
 * settings.
 * </p>
 * 
 * <p>
 * When an LLM client is active in the pipeline, the builder can use it to infer
 * additional metadata by analyzing training scripts and documentation.
 * </p>
 * 
 * @see SpdxV3PackageBuilder
 * @see MlFlowAiBuilderConfig
 */
public class MLFlowAIBuilder extends AIPackageBuilderV3 {

    static final String MANDATORY_DATA_UNAVAILABLE = "FIELD IMPOSSIBLE TO RETRIEVE";

    Logger logger = LoggerFactory.getLogger(this.getClass().getName());
    MlFlowAiBuilderConfig config;
    Dictionary<String, List<String>> modelData;
    MlflowClient client;
    ModelVersion model;
    Run latestRun;

    public MLFlowAIBuilder(String stepname, String codeName) {
        super(stepname, codeName);

        Path path = PipeManager.getAgentConfigPath(codeName + ".yaml");
        MlFlowAiBuilderInit(path.toString());
        logger.info("Connecting to MLflow tracking server at: " + config.mlFlowServerIP + " port: "
                + config.mlFlowServerPort);
        this.client = new MlflowClient("http://" + config.mlFlowServerIP + ":" + config.mlFlowServerPort);
        logger.info("Configuration loaded: " + config.mlFlowServerIP + " port: " + config.mlFlowServerPort);
    }

    /**
     * Builds an AI package with metadata from MLflow and adds it to the
     * SpdxDocumentPack.
     * <p>
     * This method connects to an MLflow tracking server to retrieve information
     * about a machine learning model,
     * including its metadata, parameters, metrics, and run information. It then
     * populates an AI package
     * with this data.
     * <p>
     * If an LLM (Large Language Model) client is active in the pipe, it will use
     * the client to infer
     * additional metadata by analyzing training scripts and application
     * documentation. Otherwise, it will
     * parse the model description to extract metadata.
     * <p>
     * The method sets both mandatory fields (name, built time, version, primary
     * purpose, supplier, release time)
     * and optional fields (hyperparameters, metrics, domain, limitations, training
     * information, etc.).
     * 
     * @param next The SpdxDocumentPack to which the AI package will be added
     * @return The updated SpdxDocumentPack containing the AI package with MLflow
     *         metadata
     * @throws InvalidSPDXAnalysisException If there are issues creating SPDX
     *                                      entities
     */
    @Override
    public boolean initBuilder() {

        // downcast!!

        logger.info("Starting model info retrieval from MLflow tracking server");

        // ------- here the package is created ---------------//
        // data retrieval //
        model = RetrieveModelData();

        if (model == null)
            return false;

        this.latestRun = RetrieveSourceRunInfo(model);

        if (latestRun == null) {
            logger.error("Source run not found for model: " + model.getName() + " version: " + model.getVersion());
            return false;
        }

        // ---------//

        modelData = new Hashtable<String, List<String>>();

        Dictionary<String, List<String>> data = DescriptionParser.parseText(model.getDescription());
        if (data != null) {
            modelData = data;
        }

        return true;
    }

    @Override
    public String addBuildTime() {
        return ConvertToSpdxDate(model.getCreationTimestamp());
    }

    @Override
    public String addDownloadLocation() {
        try {
            return downloadLocation(model, latestRun, retrieveTrainingScripts());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String addPackageVersion() {
        if (model.getVersion() == null)
            return null;

        return model.getVersion();
    }

    @Override
    public PresenceType addUseSensitivePersonalInformation() {
        PresenceType presenceType = retrievePersonalInformationUsage(model, RetrieveSourceRunInfo(model));
        if (presenceType == PresenceType.NO_ASSERTION) {
            return null;
        }
        return presenceType;
    }

    @Override
    public String addPackageName() {
        if (model.getName() == null)
            return null;

        return model.getName();
    }

    @Override
    public String addPrimaryPurpose() {
        return SoftwarePurpose.MODEL.toString();
    }

    @Override
    public String addDeclaredLicense() {
        String configuredLicense = firstNonBlank(config.declaredLicense, config.concludedLicense);
        if (configuredLicense != null) {
            return configuredLicense;
        }

        String tagLicense = getModelTagValue(model, "declaredLicense", "license", "licenseName", "spdxDeclaredLicense");
        if (tagLicense != null) {
            return tagLicense;
        }

        String artifactLicense = checkLicense(model, latestRun);
        return firstNonBlank(artifactLicense, "NOASSERTION");
    }

    @Override
    public String addSuppliedBy() {
        try {
            String userName = GetCreatorName(model, RetrieveSourceRunInfo(model));
            if (userName == null)
                return null;

            return userName;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String addReleaseTime() {
        String releaseTime = GetReleaseTime(model);
        if (releaseTime == null)
            return null;

        return releaseTime;
    }

    @Override
    public Dictionary<String, String> addHyperparameters() {
        Dictionary<String, String> hyperparameters = new Hashtable<>();
        try {
            List<Param> paramList = RetrieveSourceRunInfo(model).getData().getParamsList();
            if (paramList == null || paramList.isEmpty())
                return null;

            for (Param param : paramList) {
                hyperparameters.put(param.getKey(), String.valueOf(param.getValue()));
            }
            return hyperparameters;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

    }

    @Override
    public Dictionary<String, String> addMetrics() {
        Dictionary<String, String> metrics = new Hashtable<>();
        try {
            List<Metric> metricList = RetrieveSourceRunInfo(model).getData().getMetricsList();
            if (metricList == null || metricList.isEmpty())
                return null;

            for (Metric metric : metricList) {
                metrics.put(metric.getKey(), String.valueOf(metric.getValue()));
            }
            return metrics;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String[] addDomain() {
        List<String> domains = modelData.get("domain");
        if (domains == null || domains.isEmpty())
            return null;

        return domains.toArray(new String[0]);
    }

    @Override
    public String addInformationAboutApplication() {

        List<String> infoList = modelData.get("informationAboutApplication");
        if (infoList == null || infoList.isEmpty()) {
            return null;
        }

        return infoList.stream()
                .reduce((accumulated, current) -> {
                    accumulated += ", " + current;
                    return accumulated;
                }).orElse(null);
    }

    @Override
    public String addInformationAboutTrainingData() {
        List<String> trainInfo = modelData.get("informationAboutTraining");
        if (trainInfo == null || trainInfo.isEmpty())
            return null;

        return trainInfo.stream()
                .reduce((accumulated, current) -> {
                    accumulated += ", " + current;
                    return accumulated;
                }).orElse(null);
    }

    @Override
    public String addTypeOfModel() {
        List<String> types = modelData.get("typeOfModel");
        if (types == null || types.isEmpty())
            return null;

        return types.stream()
                .reduce((accumulated, current) -> {
                    accumulated += ", " + current;
                    return accumulated;
                }).orElse(null);
    }

    @Override
    public String addLimitations() {
        List<String> limitations = modelData.get("limitation");
        if (limitations == null || limitations.isEmpty())
            return null;

        return limitations.stream()
                .reduce((accumulated, current) -> {
                    accumulated += ", " + current;
                    return accumulated;
                }).orElse(null);

    }

    @Override
    public String[] addModelExplainability() {
        List<String> explainability = modelData.get("modelExplainability");
        if (explainability == null || explainability.isEmpty())
            return null;

        return explainability.toArray(new String[0]);
    }

    @Override
    public String addStandardCompliance() {
        List<String> standards = modelData.get("standardCompliance");
        if (standards == null || standards.isEmpty())
            return null;

        return standards.stream()
                .reduce((accumulated, current) -> {
                    accumulated += ", " + current;
                    return accumulated;
                }).orElse(null);

    }

    @Override
    public String addDataPreprocessing() {
        List<String> preprocessing = modelData.get("dataPreprocessing");
        if (preprocessing == null || preprocessing.isEmpty())
            return null;

        return preprocessing.stream()
                .reduce((accumulated, current) -> {
                    accumulated += ", " + current;
                    return accumulated;
                }).orElse(null);
    }

    @Override
    public String addSafetyRiskAssessment() {
        List<String> risk = modelData.get("safetyRiskAssessment");
        if (risk == null || risk.isEmpty())
            return null;

        try {
            return risk.stream().reduce((accumulated, current) -> {
                accumulated += ", " + current;
                return accumulated;
            }).orElse(null);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String downloadLocation(ModelVersion model, Run latestRun, File[] trainingScripts) {

        try {
            List<ModelVersionTag> tags = model.getTagsList();
            for (ModelVersionTag tag : tags) {
                if (tag.getKey().equals("downloadLocation")) {
                    return tag.getValue();
                }
            }
        } catch (MlflowClientException e) {
            logger.error(
                    "MLflow download location check is only possible if the tool is executed in a Python environment with mlflow installed.",
                    e);
        }

        return "model not published, no download location available";
    }

    private String retrieveStandardCompliance(ModelVersion model, Run latestRun) {
        // check if the information is provided as tag in MLflow
        try {
            List<ModelVersionTag> tags = model.getTagsList();
            for (ModelVersionTag tag : tags) {
                if (tag.getKey().equals("standardCompliance")) {
                    return tag.getValue();
                }
            }
        } catch (MlflowClientException e) {
            logger.error(
                    "MLflow standard compliance check is only possible if the tool is executed in a Python environment with mlflow installed.",
                    e);
        }

        // if not found, try to retrieve it from the description
        String desc = model.getDescription();
        if (desc != null && desc.contains("standardCompliance:")) {
            return desc.split("standardCompliance:")[1].trim();
        }

        return null; // default to no assertion if not found
    }

    /**
     * Determines whether the model uses sensitive personal information by examining
     * the model tags.
     * This method checks for a specific tag named "useSensitivePersonalInformation"
     * in the MLflow
     * model version tags to determine if sensitive personal information is used.
     *
     * @param model     The MLflow model version to check for personal information
     *                  usage tags
     * @param latestRun The latest run associated with the model (not currently
     *                  used)
     * @return {@link PresenceType#YES} if the model explicitly uses sensitive
     *         personal information,
     *         {@link PresenceType#NO} if the model explicitly does not use such
     *         information,
     *         {@link PresenceType#NO_ASSERTION} if the information cannot be
     *         determined or an error occurred
     */
    private PresenceType retrievePersonalInformationUsage(ModelVersion model, Run latestRun) {
        // check if the information is provided as tag in MLflow
        try {
            List<ModelVersionTag> tags = model.getTagsList();
            for (ModelVersionTag tag : tags) {
                if (tag.getKey().equals("useSensitivePersonalInformation")) {
                    if (tag.getValue().equals("true")) {
                        return PresenceType.YES;
                    } else if (tag.getValue().equals("false")) {
                        return PresenceType.NO;
                    }
                }
            }
        } catch (MlflowClientException e) {
            logger.error(
                    "MLflow sensitive personal information check is only possible if the tool is executed in a Python environment with mlflow installed.",
                    e);
        }

        return PresenceType.NO_ASSERTION; // default to no assertion if not found
    }

    /**
     * Extracts and cleans the description from a ModelVersion by removing the
     * formatted
     * section between the "~spdxStart~" and "~spdxEnd~" markers.
     *
     * @param model The ModelVersion object containing the description to clean
     * @return The cleaned description with the formatted section removed, or null
     *         if
     *         the description is null or doesn't contain the expected markers
     */
    private String GetCleanDescription(ModelVersion model) {
        String desc = model.getDescription();

        if (desc == null)
            return null;

        // remove the formatted section

        String[] descBeforeArr = desc.split("~spdxStart~");
        String descBefore = "";

        if (descBeforeArr.length < 2) {
            logger.warn("Description does not contain the formatted section; returning the original description");
            return null;
        }

        descBefore = descBeforeArr[0];
        String descAfter = desc.split("~spdxEnd~")[1];

        return descBefore + descAfter;

    }

    /**
     * Retrieves a GITOID identifier using a SHA-256 hash of the model file from the
     * model tags
     * 
     * @param model
     * @return
     */
    private ContentIdentifier GetContenIdentifierFromTags(ModelVersion model, CreationInfo info) {
        try {

            String digest = null;
            List<ModelVersionTag> tags = model.getTagsList();

            for (ModelVersionTag tag : tags) {
                if (tag.getKey().equals("digest"))
                    digest = tag.getValue();
            }

            if (digest == null) {
                logger.warn(
                        "digest tag not found, insert a SHA-1 digest of the model as a tag of the model version with the key 'digest' to automatically compute the ContentIdentifier");
                return null;
            }

            if (digest.length() > 40) {
                logger.warn(
                        "digest tag longer than 40 characters, it is not a valid GITOID, ContentIdentifier cannot be retrieved");
                return null;
            }

            // string to hex
            byte[] contentBytes = digest.getBytes("UTF-8");
            String header = "blob" + " " + contentBytes.length + "\0";
            byte[] store = new byte[header.length() + contentBytes.length];

            System.arraycopy(header.getBytes("UTF-8"), 0, store, 0, header.length());
            System.arraycopy(contentBytes, 0, store, header.length(), contentBytes.length);

            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] newDigest = sha1.digest(store);

            StringBuilder sb = new StringBuilder();
            for (byte b : newDigest)
                sb.append(String.format("%02x", b));

            ContentIdentifierBuilder contentIdentifierBuilder = info
                    .createContentIdentifier("SPDXRef:contentIdentifier");
            contentIdentifierBuilder.setContentIdentifierType(ContentIdentifierType.GITOID);
            contentIdentifierBuilder.setContentIdentifierValue(sb.toString());
            return contentIdentifierBuilder.build();

        } catch (IOException e) {
            logger.error("Unable to compute Model hash, content identifier cannot be retrieved", e);

        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();

        } catch (InvalidSPDXAnalysisException e) {
            e.printStackTrace();

        } catch (MlflowClientException e) {
            logger.error(
                    "To compute the ContentIdentifier field, this tool must be executed in a Python environment where the mlflow.store.artifact.cli package is available",
                    e);

        }

        return null;
    }

    /**
     * Retrieves the name of the creator for a model version.
     * First attempts to get the user ID from the model itself.
     * If the user ID is empty, falls back to the user ID from the latest run
     * information.
     *
     * @param model     The model version containing creator information
     * @param latestRun The latest run associated with the model, used as fallback
     *                  for creator information
     * @return The name/ID of the creator who produced the model
     */
    private String GetCreatorName(ModelVersion model, Run latestRun) {
        String userName = model.getUserId();

        if (userName == "") {
            userName = latestRun.getInfo().getUserId();
        }

        return userName;
    }

    // if the license is registered as artifact it will try to extract the license
    // type, it will try to read from the description otherwise
    private String checkLicense(ModelVersion model, Run latestRun) {
        try {
            List<FileInfo> art = client.listArtifacts(latestRun.getInfo().getRunId());

            for (FileInfo info : art) {
                if (info.getPath().toLowerCase().contains("license")) {
                    File licensefile = client.downloadArtifacts(latestRun.getInfo().getRunId(), info.getPath());
                    try (BufferedReader buffered = new BufferedReader(new FileReader(licensefile))) {
                        String line;
                        try {

                            while ((line = buffered.readLine()) != null) {
                                if (line.toLowerCase().contains("license")) {
                                    String[] chunks = line.split(":", 2);
                                    if (chunks.length == 2) {
                                        return chunks[1].trim();
                                    }
                                    return line.trim();
                                }

                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                }
            }
        } catch (MlflowClientException e) {
            logger.error(
                    "MLflow artifact license check is only possible if the tool is executed in a Python environment with mlflow installed.",
                    e);
        }

        return "NOASSERTION";
    }

    private String getModelTagValue(ModelVersion model, String... tagNames) {
        try {
            List<ModelVersionTag> tags = model.getTagsList();
            for (ModelVersionTag tag : tags) {
                for (String tagName : tagNames) {
                    if (tagName.equals(tag.getKey()) && tag.getValue() != null && !tag.getValue().isBlank()) {
                        return tag.getValue();
                    }
                }
            }
        } catch (MlflowClientException e) {
            logger.error("Unable to retrieve MLflow model version tags", e);
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Retrieves the release time for a model in SPDX date format.
     * 
     * The method determines the release time using the following priority order:
     * 1. If explicitly defined in the config file via releaseTime property
     * 2. From modelData if available
     * 3. From the model's creation timestamp
     * 
     * @param model The ModelVersion object to extract the release time from
     * @return A string representing the release time in SPDX date format,
     *         or {@code MANDATORY_DATA_UNAVAILABLE} if the data cannot be
     *         determined
     */
    private String GetReleaseTime(ModelVersion model) {
        // check is defined in the config file, max priority
        if (config != null && config.releaseTime != null && !config.releaseTime.isBlank()) {
            return config.releaseTime;
        }

        if (modelData != null && modelData.get("releaseTime") != null && !modelData.get("releaseTime").isEmpty()) {
            return modelData.get("releaseTime").getFirst();
        }

        return ConvertToSpdxDate(model.getCreationTimestamp());
    }

    // utilities
    // -----------------------------------------------------------------------------------------
    /**
     * Converts a timestamp to an SPDX-compliant date string.
     * 
     * This method takes a timestamp in milliseconds since the epoch and formats it
     * according to the SPDX date format specification. The date is formatted in UTC
     * timezone
     * as required by SPDX standards.
     *
     * @param creationTimestamp The timestamp in milliseconds since epoch to be
     *                          converted
     * @return A string representation of the timestamp formatted according to SPDX
     *         date format
     */
    private String ConvertToSpdxDate(long creationTimestamp) {
        SimpleDateFormat format = new SimpleDateFormat(SpdxConstantsV3.SPDX_DATE_FORMAT);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String date = format.format(creationTimestamp);
        return date;
    }

    /**
     * Initializes the MLFlowAIBuilder with configuration from the specified file
     * path.
     * If the configuration file cannot be read or is not found, an error message is
     * logged.
     *
     * @param path The path to the configuration file for MLFlowAIBuilder
     */
    void MlFlowAiBuilderInit(String path) {
        try {
            config = MlFlowAiBuilderConfig.readConfig(path);
        } catch (IOException e) {
            logger.error("Configuration file not found for MLFlowAIBuilder", e);
        }
    }

    /**
     * Retrieve the run information of the source model.
     * If a version is specified in the config file, retrieve that version.
     * 
     * @param model
     * @return
     */
    private Run RetrieveSourceRunInfo(ModelVersion model) {
        Run run = client.getRun(model.getRunId());

        if (run == null) {
            logger.error("Source run not found for model: " + model.getName() + " version: " + model.getVersion());
        }

        return run;
    }

    /**
     * Retrieves model data from the MLflow tracking server.
     * 
     * This method connects to the MLflow server, searches for the model specified
     * in the
     * configuration, and retrieves either a specific version or the latest version
     * of the model.
     * 
     * @return ModelVersion The retrieved model version object
     * @throws RuntimeException If the specified model is not found on the server
     * @throws RuntimeException If there is an error while connecting to or
     *                          retrieving data from the MLflow server
     */
    public ModelVersion RetrieveModelData() {
        try {

            logger.info("Connection established");
            if (config == null || config.modelName == null || config.modelName.isBlank()) {
                logger.error("MLflow modelName is missing in the agent configuration");
                return null;
            }

            ModelVersionsPage model = client.searchModelVersions("name LIKE '" + config.modelName + "'");

            if (model.getItems().isEmpty()) {
                logger.error("Model not found: " + config.modelName + " execution aborted");
                return null;
            }

            ModelVersion toreturn = null;

            if (config.modelVersion != null && !config.modelVersion.isBlank()) {
                toreturn = model.getItems().stream()
                        .filter(item -> config.modelVersion.equals(item.getVersion()))
                        .findFirst()
                        .orElse(null);

                if (toreturn == null) {
                    logger.error("Model found, but version {} is not available for model {}",
                            config.modelVersion, config.modelName);
                    return null;
                }
            } else {
                toreturn = model.getItems().getFirst();
            }

            logger.info("Model retrieved: " + toreturn.getName() + " version: " + toreturn.getVersion());
            return toreturn;

        } catch (MlflowClientException e) {
            logger.error(
                    "error while trying to retrieve model from tracking server, pipe will try to continue without this agent",
                    e);
        }

        return null;

    }

    private File[] retrieveTrainingScripts() {
        return listFilesFromConfiguredDirectory(config.trainingScriptsPath, "training scripts");
    }

    private File[] retrieveApplicationDocumentation() {
        return listFilesFromConfiguredDirectory(config.applicationDocumentationPath, "application documentation");
    }

    private File[] listFilesFromConfiguredDirectory(String directoryPath, String label) {
        if (directoryPath == null || directoryPath.isBlank()) {
            logger.debug("No {} directory configured for MLflow builder", label);
            return new File[0];
        }

        File directory = new File(directoryPath);
        if (!directory.isDirectory()) {
            logger.warn("Configured {} path is not a directory: {}", label, directoryPath);
            return new File[0];
        }

        File[] files = directory.listFiles();
        if (files == null) {
            logger.warn("Unable to list configured {} directory: {}", label, directoryPath);
            return new File[0];
        }
        return files;
    }

    public static class MlFlowAiBuilderConfig {
        public String modelName;
        public String mlFlowServerIP;
        public String mlFlowServerPort;
        public String releaseTime;
        public String declaredLicense;
        public String concludedLicense;
        public String modelVersion;
        public String trainingScriptsPath;
        public String applicationDocumentationPath;

        static MlFlowAiBuilderConfig readConfig(String path)
                throws StreamReadException, DatabindException, IOException {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

            MlFlowAiBuilderConfig config = mapper.readValue(new File(path), MlFlowAiBuilderConfig.class);
            config.resolveConfigValues();
            return config;

        }

        public MlFlowAiBuilderConfig() {
        }

        private void resolveConfigValues() {
            modelName = PipeManager.resolveConfigValue(modelName);
            mlFlowServerIP = PipeManager.resolveConfigValue(mlFlowServerIP);
            mlFlowServerPort = PipeManager.resolveConfigValue(mlFlowServerPort);
            releaseTime = PipeManager.resolveConfigValue(releaseTime);
            declaredLicense = PipeManager.resolveConfigValue(declaredLicense);
            concludedLicense = PipeManager.resolveConfigValue(concludedLicense);
            modelVersion = PipeManager.resolveConfigValue(modelVersion);
            trainingScriptsPath = PipeManager.resolveConfigValue(trainingScriptsPath);
            applicationDocumentationPath = PipeManager.resolveConfigValue(applicationDocumentationPath);
        }
    }

}
