// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.builders.buildersV3.Dataset.Kaggle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spdx.library.model.v3_0_1.dataset.DatasetType;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import it.unisannio.bomgenerator.PipeManager;
import it.unisannio.bomgenerator.builders.buildersV3.Dataset.DatasetPackageBuilderV3;
import it.unisannio.bomgenerator.support.LLMClient;

/**
 * A builder class for Kaggle datasets that extends the
 * DatasetPackageBuilderV3.
 * This class is responsible for building dataset packages by retrieving dataset
 * files
 * from the Kaggle platform.
 * 
 * <p>
 * The builder connects to the active LLM client to analyze dataset contents
 * and determine appropriate dataset types. It currently retrieves datasets from
 * a hardcoded file location, but is designed to be extended to support other
 * retrieval methods.
 * </p>
 * 
 * @see DatasetPackageBuilderV3
 * @see LLMClient
 * @see DatasetType
 */
public class KaggleDatasetBuilder extends DatasetPackageBuilderV3 {

    static final Logger logger = LoggerFactory.getLogger(KaggleDatasetBuilder.class);

    File f;
    KaggleDatasetConfig config;
    LLMClient llmClient;
    Dictionary<String, String> datasetFields;
    JsonNode jsonMetadata;
    File datasetDir;
    Path outputDir;
    String datasetHead;

    public KaggleDatasetBuilder(String stepname, String codeName) {
        super(stepname, codeName);

        // check if an LLM was configured
        logger.info("KaggleDatasetBuilder initializing...");
        this.llmClient = PipeManager.getActiveClient();

        if (llmClient == null) {
            logger.warn("No LLM client configured, KaggleDatasetBuilder will not be able to infer dataset fields");
        }

    }

    @Override
    public String addOriginatedBy() {
        logger.info("Retrieving suppliedBy name from metadata");
        String creatorName = jsonMetadata.get("creatorName").asText();
        if (creatorName == null)
            creatorName = jsonMetadata.get("ownerName").asText();

        return creatorName;
    }

    @Override
    public String[] addDatasetType() {
        logger.info("Inferring dataset type using LLM");
        return inferDatasetTypeWithLLM(datasetDir);
    }

    @Override
    public String addBuildTime() {
        logger.info("Retrieving build time from JSON metadata");

        if (jsonMetadata == null) {
            try {
                retrieveMetadata();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                return null;
            }
            if (jsonMetadata == null) {
                return null;
            }
        }

        String rawTimestamp = jsonMetadata.get("lastUpdated").asText();
        try {
            // Parse the timestamp (accepts millisecond precision too)
            OffsetDateTime parsed = OffsetDateTime.parse(rawTimestamp);

            // Format to 'YYYY-MM-DDTHH:MM:SSZ'
            return parsed.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
        } catch (DateTimeParseException e) {
            logger.error("Invalid timestamp format: " + rawTimestamp, e);
            return null;
        }
    }

    @Override
    public String addReleaseTime() {
        if (jsonMetadata == null) {
            try {
                retrieveMetadata();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                return null;
            }
            if (jsonMetadata == null) {
                return null;
            }
        }

        JsonNode versions = jsonMetadata.get("versions");
        if (versions == null || !versions.isArray() || versions.size() == 0) {
            logger.warn("No versions found in JSON metadata");
            return null;
        }

        JsonNode firstVersion = versions.get(0);
        JsonNode releaseDateNode = firstVersion.get("creationDate");
        if (releaseDateNode == null || releaseDateNode.isNull()) {
            logger.warn("Missing releaseDate in first version");
            return null;
        }

        String rawDate = releaseDateNode.asText();
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(rawDate);
            return parsed.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
        } catch (DateTimeParseException e) {
            logger.error("Invalid releaseDate format: " + rawDate, e);
            return null;
        }
    }

    @Override
    public String addDeclaredLicense() {
        if (this.jsonMetadata == null) {
            try {
                retrieveMetadata();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                return null;
            }
            if (this.jsonMetadata == null) {
                return null;
            }
        }

        JsonNode licenseNode = this.jsonMetadata.get("licenseName");
        return licenseNode.asText();
    }

    @Override
    public String addPrimaryPurpose() {
        return "data";
    }

    @Override
    public String addPackageName() {
        return config.datasetName;
    }

    @Override
    public String addAnonimizationMethodUsed() {
        logger.info("Retrieving anonymization method from dataset file heads using the LLM");
        if (llmClient == null) {
            logger.warn("No LLM client configured, cannot infer anonymization method");
            return null;
        }

        String prompt = "Using the following dataset head, infer the anonymization method used, if any. " +
                " If no anonymization method is used, or it is not specified, just write 'no assertion' and no other text.\n\n"
                +
                datasetHead;

        prompt = prompt.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t");

        try {
            String result = llmClient.chat(prompt, false);
            return result.trim();
        } catch (ConnectException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return "no assertion";
    }

    @Override
    public String addDownloadLocation() {
        return "https://www.kaggle.com/datasets/" + config.userName + "/" + config.datasetName;
    }

    private String[] inferDatasetTypeWithLLM(File dir) {
        {
            LLMClient client = PipeManager.getActiveClient();
            BufferedReader reader;
            String sys;
            String toSend = "";
            String head = datasetHead;
            try {

                sys = "You are working in an AIBOM generator. Infer the dataset type using the dataset heads provided by the user. "
                        + "Return a list with one or more of the following values. Never use values outside this list: ";

                for (DatasetType type : DatasetType.values()) {
                    sys += type.toString() + ", ";
                }

                toSend += "list of datasets heads: \n";
                toSend = head;
                toSend = toSend.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\t", "\\t");

                // check if context is greater than max
                String[] contextCheck = toSend.split(" ");
                if (contextCheck.length > 5000) {
                    logger.warn("Context for dataset type inference is too long, truncating to 4000 words");
                    toSend = String.join(" ", Arrays.copyOfRange(contextCheck, 0, 4000));
                }

                // from tests lists is returned as a string in format: elem1, elem2
                String string;
                string = client.inferFormatted(toSend, sys, "datasetType", true);
                string = string.replace("\"", ""); // escape quotes
                String[] s = string.split(",");
                return s;

            } catch (ConnectException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        return null;

    }

    @Override
    public Integer addDatasetSize() {
        for (File f : datasetDir.listFiles()) {
            if (f.isFile()) {
                return (int) f.length();
            }
        }
        return null;
    }

    @Override
    public String addPackageVersion() {
        logger.info("Retrieving package version from metadata");
        return this.jsonMetadata.get("currentVersionNumber").asText(null);
    }

    @Override
    public String addIntendedUse() {
        logger.info("Inferring intended use from dataset metadata using LLM");
        List<String> descriptions = new ArrayList<>();

        JsonNode rootDesc = jsonMetadata.get("description");
        if (rootDesc != null) {
            descriptions.add(rootDesc.asText());
        }

        JsonNode versions = jsonMetadata.get("versions");
        if (versions != null && versions.isArray()) {
            versions.forEach(version -> {
                String description = version.path("description").asText(null);
                String descriptionNullable = version.path("descriptionNullable").asText(null);
                if (description != null)
                    descriptions.add(description);
                if (descriptionNullable != null)
                    descriptions.add(descriptionNullable);
            });
        }

        String total = descriptions.stream()
                .collect(Collectors.joining("\n"));

        try {

            String prompt = "Using the following descriptions of the dataset versions, infer the intended use declared for the dataset. "
                    + "Report only the intended uses. "
                    + "If no information is available, return 'no assertion':"
                    + total;

            prompt = prompt.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\t", "\\t");

            return llmClient.chat(prompt, false);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return "no assertion";
        }
    }

    public File downloadDataset() throws IOException, InterruptedException {
        logger.info("Downloading dataset from Kaggle");

        String downloadUrl = String.format(
                "https://www.kaggle.com/api/v1/datasets/download/%s/%s",
                config.userName, config.datasetName);

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .header("Authorization", "Basic " + config.token)
                .header("Accept", "application/zip")
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        // Follow redirects manually when Kaggle returns HTTP 302.
        if (response.statusCode() == 302) {
            String redirectUrl = response.headers().firstValue("Location").orElse(null);
            if (redirectUrl == null) {
                throw new IOException("HTTP 302 redirect received without a Location header.");
            }

            logger.info("Download redirected to URL: " + redirectUrl);

            HttpRequest redirectedRequest = HttpRequest.newBuilder()
                    .uri(URI.create(redirectUrl))
                    .header("Authorization", "Basic " + config.token)
                    .header("Accept", "application/zip")
                    .GET()
                    .build();

            response = client.send(redirectedRequest, HttpResponse.BodyHandlers.ofInputStream());
        }

        if (response.statusCode() == 200) {
            File outputFile = File.createTempFile(config.userName.replaceAll("-", "_"), ".zip");
            try (InputStream inputStream = response.body();
                    FileOutputStream outputStream = new FileOutputStream(outputFile)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }

                logger.info("Dataset download success: " + outputFile.getAbsolutePath());
                return outputFile;
            }
        } else {
            throw new IOException("HTTP error " + response.statusCode() + ": unable to download the dataset");
        }
    }

    public File unzip(File zipFile, Path outputDir) throws IOException {
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }

        boolean atLeastOneFileExtracted = false;

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                Path newPath = outputDir.resolve(entry.getName()).normalize();

                // Protect against zip slip.
                if (!newPath.startsWith(outputDir)) {
                    throw new IOException(
                            "Attempted write outside the destination directory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Files.createDirectories(newPath.getParent());
                    try (OutputStream os = Files.newOutputStream(newPath)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }

                    atLeastOneFileExtracted = true;
                }
                zis.closeEntry();
            }
        }

        if (!atLeastOneFileExtracted) {
            throw new IOException("No files extracted from the zip archive.");
        }

        logger.info("Dataset unzipped: " + outputDir.toAbsolutePath());
        return outputDir.toFile();
    }

    public void retrieveMetadata() throws IOException, InterruptedException {
        ObjectMapper objectMapper = new ObjectMapper();

        String url = "https://www.kaggle.com/api/v1/datasets/view/"
                + config.userName + "/" + config.datasetName;

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Basic " + config.token)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonNode json = objectMapper.readTree(response.body());
            this.jsonMetadata = json;
        } else {
            logger.error("Error in http request: " + response.statusCode());
        }
    }
    // LLM inference methods ----------------------------------------------------

    public static class KaggleDatasetConfig {
        public String datasetName;
        public String userName;
        public String token;

        static KaggleDatasetConfig readConfig(String path)
                throws StreamReadException, DatabindException, IOException {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

            KaggleDatasetConfig config = mapper.readValue(new File(path), KaggleDatasetConfig.class);
            config.resolveConfigValues();
            return config;

        }

        public KaggleDatasetConfig() {
        }

        private void resolveConfigValues() {
            datasetName = PipeManager.resolveConfigValue(datasetName);
            userName = PipeManager.resolveConfigValue(userName);
            token = PipeManager.resolveConfigValue(token);
        }

    }

    public String produceDatasetHead(File dir) {
        String head = "";
        BufferedReader reader;

        try {
            for (File f : dir.listFiles()) {
                reader = Files.newBufferedReader(Path.of(f.getPath()));
                if (!f.isFile())
                    continue;

                if (!f.getName().endsWith(".csv"))
                    continue;

                for (int x = 0; x < 5; x++) {
                    head += reader.readLine() + "\n";
                }

                reader.close();
                // Use the first CSV file as a representative sample.
                break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return head;
    }

    @Override
    public String addDataCollectionProcess() {
        List<String> descriptions = new ArrayList<>();

        JsonNode rootDesc = jsonMetadata.get("description");
        if (rootDesc != null) {
            descriptions.add(rootDesc.asText());
        }

        JsonNode versions = jsonMetadata.get("versions");
        if (versions != null && versions.isArray()) {
            versions.forEach(version -> {
                String description = version.path("description").asText(null);
                String descriptionNullable = version.path("descriptionNullable").asText(null);
                if (description != null)
                    descriptions.add(description);
                if (descriptionNullable != null)
                    descriptions.add(descriptionNullable);
            });
        }

        String total = descriptions.stream()
                .collect(Collectors.joining("\n"));

        try {
            total = total.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\t", "\\t");// remove tabs

            String prompt = "Using the following descriptions of the dataset versions, infer the data collection process, "
                    + "if possible. If no information is available, return 'no assertion':"
                    + total;

            return llmClient.chat(prompt, false);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return "no assertion";
        }
    }

    @Override
    public boolean initBuilder() {
        logger.info("Initializing KaggleDatasetBuilder with codeName: {}", codeName);
        try {
            Path path = PipeManager.getAgentConfigPath(codeName + ".yaml");
            this.config = KaggleDatasetConfig.readConfig(path.toString());

            this.retrieveMetadata();
            this.datasetDir = downloadDataset();
            this.datasetDir = unzip(datasetDir, Path.of("tmp/" + config.userName + "/" + config.datasetName));
            this.datasetHead = produceDatasetHead(this.datasetDir);

        } catch (StreamReadException e) {
            e.printStackTrace();
            return false;
        } catch (DatabindException e) {
            e.printStackTrace();
            return false;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } catch (InterruptedException e) {
            e.printStackTrace();
            return false;
        }

        return true;
    }

}
