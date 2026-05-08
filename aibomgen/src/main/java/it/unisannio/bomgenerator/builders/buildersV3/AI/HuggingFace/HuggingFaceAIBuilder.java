// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.builders.buildersV3.AI.HuggingFace;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spdx.library.model.v3_0_1.SpdxConstantsV3;
import org.spdx.library.model.v3_0_1.software.SoftwarePurpose;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import it.unisannio.bomgenerator.PipeManager;
import it.unisannio.bomgenerator.builders.buildersV3.AI.AIPackageBuilderV3;

public class HuggingFaceAIBuilder extends AIPackageBuilderV3 {

    private static final Logger logger = LoggerFactory.getLogger(HuggingFaceAIBuilder.class);
    private static final String DEFAULT_HUB_BASE_URL = "https://huggingface.co";
    private static final Set<String> FRAMEWORK_TAGS = Set.of(
            "pytorch",
            "tensorflow",
            "jax",
            "keras",
            "safetensors",
            "transformers",
            "diffusers",
            "sentence-transformers",
            "onnx",
            "timm");

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private HuggingFaceBuilderConfig config;
    private HttpClient httpClient;
    private JsonNode modelInfo;
    private JsonNode modelConfig;
    private String modelCard = "";

    public HuggingFaceAIBuilder(String stepname, String codeName) {
        super(stepname, codeName);
        Path path = PipeManager.getAgentConfigPath(codeName + ".yaml");
        config = HuggingFaceBuilderConfig.readConfig(path.toString());
    }

    @Override
    public boolean initBuilder() {
        if (config == null) {
            logger.error("Hugging Face builder configuration could not be loaded");
            return false;
        }

        if (config.modelId == null || config.modelId.isBlank()) {
            logger.error("Hugging Face modelId is missing in the agent configuration");
            return false;
        }

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        modelInfo = fetchJson(modelInfoUri());
        if (modelInfo == null) {
            logger.error("Unable to retrieve Hugging Face model metadata for {}", config.modelId);
            return false;
        }

        modelCard = fetchText(rawFileUri("README.md"), false);
        if (modelCard == null) {
            modelCard = "";
        }

        JsonNode embeddedConfig = modelInfo.path("config");
        if (embeddedConfig.isObject()) {
            modelConfig = embeddedConfig;
        } else if (config.configFile != null && !config.configFile.isBlank()) {
            modelConfig = fetchJson(rawFileUri(config.configFile), false);
        }

        logger.info("Hugging Face model metadata retrieved: {}", config.modelId);
        return true;
    }

    @Override
    public String addPackageName() {
        return firstNonBlank(textField(modelInfo, "id"), config.modelId);
    }

    @Override
    public String addPackageVersion() {
        return firstNonBlank(textField(modelInfo, "sha"), config.revision);
    }

    @Override
    public String addBuildTime() {
        return firstNonBlank(config.buildTime, spdxDate(textField(modelInfo, "createdAt")),
                spdxDate(textField(modelInfo, "lastModified")));
    }

    @Override
    public String addReleaseTime() {
        return firstNonBlank(config.releaseTime, spdxDate(textField(modelInfo, "lastModified")),
                spdxDate(textField(modelInfo, "createdAt")));
    }

    @Override
    public String addDownloadLocation() {
        return normalizedHubBaseUrl() + "/" + config.modelId + "/tree/" + config.revision;
    }

    @Override
    public String addDeclaredLicense() {
        return firstNonBlank(config.declaredLicense, textFromCardData("license"), tagValue("license:"));
    }

    @Override
    public String addPrimaryPurpose() {
        return SoftwarePurpose.MODEL.toString();
    }

    @Override
    public String addSuppliedBy() {
        return firstNonBlank(config.suppliedBy, textField(modelInfo, "author"), ownerFromModelId());
    }

    @Override
    public String[] addDomain() {
        Set<String> domains = new LinkedHashSet<>();
        if (config.domains != null) {
            domains.addAll(config.domains);
        }

        putIfPresent(domains, textField(modelInfo, "pipeline_tag"));
        putIfPresent(domains, textFromModelIndexTask("type"));
        putIfPresent(domains, textFromModelIndexTask("name"));

        return toArrayOrNull(domains);
    }

    @Override
    public String addTypeOfModel() {
        Set<String> modelTypes = new LinkedHashSet<>();
        if (config.modelTypes != null) {
            modelTypes.addAll(config.modelTypes);
        }

        putIfPresent(modelTypes, textField(modelConfig, "model_type"));
        putAllPresent(modelTypes, textArrayField(modelConfig, "architectures"));
        putIfPresent(modelTypes, textField(modelInfo, "pipeline_tag"));
        putAllPresent(modelTypes, frameworkTags());

        if (modelTypes.isEmpty()) {
            return null;
        }

        return String.join(", ", modelTypes);
    }

    @Override
    public String addInformationAboutTrainingData() {
        String datasetTags = tagsWithPrefix("dataset:");
        return firstNonBlank(config.informationAboutTrainingData,
                markdownSection("training data", "training dataset", "datasets", "data"),
                datasetTags == null ? null : "Datasets: " + datasetTags);
    }

    @Override
    public String addLimitations() {
        return firstNonBlank(config.limitations,
                markdownSection("bias, risks, and limitations", "risks and limitations", "limitations", "bias"));
    }

    @Override
    public String addInformationAboutApplication() {
        return firstNonBlank(config.informationAboutApplication,
                markdownSection("intended use", "uses", "use cases", "direct use", "out-of-scope use"));
    }

    @Override
    public Dictionary<String, String> addMetrics() {
        Dictionary<String, String> metrics = new Hashtable<>();
        JsonNode cardData = modelInfo.path("cardData");
        collectMetrics(cardData, metrics, 0);

        if (metrics.isEmpty()) {
            return null;
        }

        return metrics;
    }

    private JsonNode fetchJson(URI uri) {
        return fetchJson(uri, true);
    }

    private JsonNode fetchJson(URI uri, boolean required) {
        String responseBody = fetchText(uri, required);
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }

        try {
            return jsonMapper.readTree(responseBody);
        } catch (JsonProcessingException e) {
            logger.warn("Unable to parse Hugging Face JSON response from {}", uri, e);
            return null;
        }
    }

    private String fetchText(URI uri, boolean required) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(config.timeoutSeconds))
                    .GET()
                    .header("Accept", "application/json, text/plain;q=0.9, */*;q=0.8");

            if (config.token != null && !config.token.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + config.token);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }

            if (required) {
                logger.error("Hugging Face request failed with status {} for {}", response.statusCode(), uri);
            } else {
                logger.debug("Optional Hugging Face request failed with status {} for {}", response.statusCode(), uri);
            }
        } catch (IOException e) {
            logger.warn("I/O error during Hugging Face request to {}", uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Hugging Face request interrupted for {}", uri, e);
        }
        return null;
    }

    private URI modelInfoUri() {
        StringBuilder path = new StringBuilder();
        path.append(normalizedHubBaseUrl())
                .append("/api/models/")
                .append(encodePath(config.modelId));

        if (config.revision != null && !config.revision.isBlank()) {
            path.append("/revision/").append(encodePathSegment(config.revision));
        }

        path.append("?full=true&config=true");
        return URI.create(path.toString());
    }

    private URI rawFileUri(String filePath) {
        return URI.create(normalizedHubBaseUrl()
                + "/"
                + encodePath(config.modelId)
                + "/raw/"
                + encodePathSegment(config.revision)
                + "/"
                + encodePath(filePath));
    }

    private String normalizedHubBaseUrl() {
        String baseUrl = firstNonBlank(config.hubBaseUrl, DEFAULT_HUB_BASE_URL);
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private static String encodePath(String path) {
        String[] segments = path.split("/");
        List<String> encodedSegments = new ArrayList<>();
        for (String segment : segments) {
            encodedSegments.add(encodePathSegment(segment));
        }
        return String.join("/", encodedSegments);
    }

    private static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String spdxDate(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) {
            return null;
        }

        try {
            return DateTimeFormatter.ofPattern(SpdxConstantsV3.SPDX_DATE_FORMAT)
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.parse(isoDate));
        } catch (DateTimeParseException e) {
            logger.warn("Unable to parse Hugging Face date as ISO-8601: {}", isoDate);
            return isoDate;
        }
    }

    private String textField(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }

        JsonNode value = node.path(fieldName);
        if (value.isTextual()) {
            return emptyToNull(value.asText());
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return null;
    }

    private List<String> textArrayField(JsonNode node, String fieldName) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return values;
        }

        JsonNode value = node.path(fieldName);
        if (value.isArray()) {
            for (JsonNode item : value) {
                putIfPresent(values, item.asText(null));
            }
        } else if (value.isTextual()) {
            putIfPresent(values, value.asText());
        }
        return values;
    }

    private String textFromCardData(String fieldName) {
        JsonNode value = modelInfo.path("cardData").path(fieldName);
        if (value.isTextual()) {
            return emptyToNull(value.asText());
        }
        if (value.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : value) {
                putIfPresent(values, item.asText(null));
            }
            if (!values.isEmpty()) {
                return String.join(", ", values);
            }
        }
        return null;
    }

    private String textFromModelIndexTask(String fieldName) {
        JsonNode modelIndex = modelInfo.path("cardData").path("model-index");
        if (!modelIndex.isArray()) {
            return null;
        }

        for (JsonNode modelIndexEntry : modelIndex) {
            JsonNode results = modelIndexEntry.path("results");
            if (!results.isArray()) {
                continue;
            }

            for (JsonNode result : results) {
                String value = textField(result.path("task"), fieldName);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private String tagValue(String prefix) {
        JsonNode tags = modelInfo.path("tags");
        if (!tags.isArray()) {
            return null;
        }

        for (JsonNode tag : tags) {
            String value = tag.asText("");
            if (value.startsWith(prefix)) {
                return emptyToNull(value.substring(prefix.length()));
            }
        }
        return null;
    }

    private String tagsWithPrefix(String prefix) {
        JsonNode tags = modelInfo.path("tags");
        if (!tags.isArray()) {
            return null;
        }

        List<String> values = new ArrayList<>();
        for (JsonNode tag : tags) {
            String value = tag.asText("");
            if (value.startsWith(prefix)) {
                putIfPresent(values, value.substring(prefix.length()));
            }
        }

        if (values.isEmpty()) {
            return null;
        }
        return String.join(", ", values);
    }

    private List<String> frameworkTags() {
        List<String> frameworks = new ArrayList<>();
        JsonNode tags = modelInfo.path("tags");
        if (!tags.isArray()) {
            return frameworks;
        }

        for (JsonNode tag : tags) {
            String value = tag.asText("").toLowerCase();
            if (FRAMEWORK_TAGS.contains(value)) {
                frameworks.add(value);
            }
        }
        return frameworks;
    }

    private String markdownSection(String... titleCandidates) {
        if (modelCard == null || modelCard.isBlank()) {
            return null;
        }

        String[] lines = modelCard.split("\\R");
        boolean collecting = false;
        int sectionLevel = Integer.MAX_VALUE;
        StringBuilder section = new StringBuilder();

        for (String line : lines) {
            Heading heading = parseHeading(line);
            if (heading != null) {
                if (collecting && heading.level <= sectionLevel) {
                    break;
                }

                if (!collecting && headingMatches(heading.title, titleCandidates)) {
                    collecting = true;
                    sectionLevel = heading.level;
                    continue;
                }
            }

            if (collecting) {
                section.append(line).append('\n');
            }
        }

        return emptyToNull(cleanMarkdown(section.toString()));
    }

    private Heading parseHeading(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("#")) {
            return null;
        }

        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#') {
            level++;
        }

        if (level == 0 || level > 6 || level >= trimmed.length() || trimmed.charAt(level) != ' ') {
            return null;
        }

        return new Heading(level, trimmed.substring(level + 1).trim());
    }

    private boolean headingMatches(String heading, String[] titleCandidates) {
        String normalizedHeading = normalizeHeading(heading);
        for (String title : titleCandidates) {
            if (normalizedHeading.contains(normalizeHeading(title))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeHeading(String value) {
        return value.toLowerCase()
                .replace("&", "and")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String cleanMarkdown(String text) {
        return text.replaceAll("(?s)```.*?```", " ")
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")
                .replaceAll("\\[[^\\]]+\\]\\(([^)]*)\\)", "$1")
                .replaceAll("[*_`]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void collectMetrics(JsonNode node, Dictionary<String, String> metrics, int depth) {
        if (node == null || node.isMissingNode() || node.isNull() || depth > 8) {
            return;
        }

        if (node.isObject()) {
            JsonNode metricsNode = node.path("metrics");
            if (metricsNode.isArray()) {
                for (JsonNode metric : metricsNode) {
                    String key = firstNonBlank(textField(metric, "name"), textField(metric, "type"));
                    String value = firstNonBlank(textField(metric, "value"), textField(metric, "score"));
                    if (key != null && value != null) {
                        metrics.put(key, value);
                    }
                }
            }

            node.fields().forEachRemaining(entry -> collectMetrics(entry.getValue(), metrics, depth + 1));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectMetrics(item, metrics, depth + 1);
            }
        }
    }

    private String ownerFromModelId() {
        if (config.modelId == null || !config.modelId.contains("/")) {
            return null;
        }
        return config.modelId.substring(0, config.modelId.indexOf('/'));
    }

    private String[] toArrayOrNull(Set<String> values) {
        values.removeIf(value -> value == null || value.isBlank());
        if (values.isEmpty()) {
            return null;
        }
        return values.toArray(new String[0]);
    }

    private void putAllPresent(Set<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            putIfPresent(target, value);
        }
    }

    private void putIfPresent(Set<String> target, String value) {
        String normalized = emptyToNull(value);
        if (normalized != null) {
            target.add(normalized);
        }
    }

    private void putIfPresent(List<String> target, String value) {
        String normalized = emptyToNull(value);
        if (normalized != null) {
            target.add(normalized);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static class Heading {
        final int level;
        final String title;

        Heading(int level, String title) {
            this.level = level;
            this.title = title;
        }
    }

    public static class HuggingFaceBuilderConfig {
        public String modelId;
        public String revision = "main";
        public String token = "";
        public String hubBaseUrl = DEFAULT_HUB_BASE_URL;
        public String configFile = "config.json";
        public int timeoutSeconds = 30;
        public String buildTime;
        public String releaseTime;
        public String declaredLicense;
        public String suppliedBy;
        public List<String> domains;
        public List<String> modelTypes;
        public String informationAboutTrainingData;
        public String informationAboutApplication;
        public String limitations;

        static HuggingFaceBuilderConfig readConfig(String path) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            try {
                HuggingFaceBuilderConfig config = mapper.readValue(new File(path), HuggingFaceBuilderConfig.class);
                config.resolveConfigValues();
                return config;
            } catch (JsonProcessingException e) {
                logger.error("Error while loading HuggingFaceAIBuilder configuration: YAML file is misconfigured", e);
            } catch (IOException e) {
                logger.error("Error while loading HuggingFaceAIBuilder configuration: YAML file was not found", e);
            }

            return null;
        }

        private void resolveConfigValues() {
            modelId = PipeManager.resolveConfigValue(modelId);
            revision = firstNonBlank(PipeManager.resolveConfigValue(revision), "main");
            token = PipeManager.resolveConfigValue(token);
            hubBaseUrl = firstNonBlank(PipeManager.resolveConfigValue(hubBaseUrl), DEFAULT_HUB_BASE_URL);
            configFile = firstNonBlank(PipeManager.resolveConfigValue(configFile), "config.json");
            buildTime = PipeManager.resolveConfigValue(buildTime);
            releaseTime = PipeManager.resolveConfigValue(releaseTime);
            declaredLicense = PipeManager.resolveConfigValue(declaredLicense);
            suppliedBy = PipeManager.resolveConfigValue(suppliedBy);
            domains = PipeManager.resolveConfigValues(domains);
            modelTypes = PipeManager.resolveConfigValues(modelTypes);
            informationAboutTrainingData = PipeManager.resolveConfigValue(informationAboutTrainingData);
            informationAboutApplication = PipeManager.resolveConfigValue(informationAboutApplication);
            limitations = PipeManager.resolveConfigValue(limitations);

            if (timeoutSeconds <= 0) {
                timeoutSeconds = 30;
            }
        }
    }
}
