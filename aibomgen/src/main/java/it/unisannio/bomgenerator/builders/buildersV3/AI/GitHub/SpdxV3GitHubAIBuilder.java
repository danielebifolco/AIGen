// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.builders.buildersV3.AI.GitHub;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import it.unisannio.bomgenerator.PipeManager;
import it.unisannio.bomgenerator.builders.buildersV3.AI.AIPackageBuilderV3;
import it.unisannio.bomgenerator.support.LLMClient;

public class SpdxV3GitHubAIBuilder extends AIPackageBuilderV3 {
    GitHubBuilderConfig config;
    List<GHContent> trainingFiles = new ArrayList<>();
    List<GHContent> preprocessingFiles = new ArrayList<>();
    List<GHContent> applicationDocumentation = new ArrayList<>();
    List<String> trainingFileContent = new ArrayList<>();
    List<String> applicationFileContent = new ArrayList<>();

    private static Logger logger = LoggerFactory.getLogger(SpdxV3GitHubAIBuilder.class);

    public SpdxV3GitHubAIBuilder(String stepname, String codeName) {
        super(stepname, codeName);
        Path path = PipeManager.getAgentConfigPath(codeName + ".yaml");
        config = GitHubBuilderConfig.readConfig(path.toString());
    }

    @Override
    public boolean initBuilder() {
        logger.info("Initializing GitHubAIBuilder");
        GitHub github;

        try {

            if (config.accessToken.equals("")) {
                github = new GitHubBuilder().build();

            } else {
                github = new GitHubBuilder().withOAuthToken(config.accessToken).build();

            }

            GHRepository repo = github.getRepository(config.userRepoString);

            if (config.trainingFiles != null) {
                for (String filePath : config.trainingFiles) {
                    GHContent content = repo.getFileContent(filePath, config.branch);
                    trainingFiles.add(content);
                }
            } else {
                logger.warn(
                        "No training files specified in the configuration file, cannot proceed with training data retrieval");
            }

            if (config.preprocessingFiles != null) {
                for (String filePath : config.preprocessingFiles) {
                    GHContent content = repo.getFileContent(filePath, config.branch);
                    preprocessingFiles.add(content);
                }
            } else {
                logger.warn(
                        "No preprocessing files specified in the configuration file, cannot proceed with preprocessing data retrieval");
            }

            if (config.applicationFiles != null) {
                for (String filePath : config.applicationFiles) {
                    GHContent content = repo.getFileContent(filePath, config.branch);
                    applicationDocumentation.add(content);
                }
            } else {
                logger.warn(
                        "No application documentation files specified in the configuration file, cannot proceed with application documentation retrieval");
            }

        } catch (IOException e) {
            e.printStackTrace();
            logger.error("Error while connecting to GitHub", e);
        }

        return true;
    }

    @Override
    public String[] addDomain() {
        logger.info("Domain produced by GitHubAIBuilder");
        LLMClient client = PipeManager.getActiveClient();
        String toSend = "Infer the working domain of the model based on the following training scripts: just answer with the domain name, no other text";

        String toadd = trainingFileString();

        if (toadd == null) {
            logger.error("No training files found in the repository, cannot add domain");
            return null;
        }

        toSend += toadd;

        try {

            toSend = toSend.replace("\"", ""); // escape quotes
            toSend = toSend.replace("\'", ""); // escape single quotes
            toSend = toSend.replace("\n", " "); // remove new lines
            toSend = toSend.replace("\\", " "); // remove carriage returns

            String domain = client.inferFormatted(toSend, "domain", true);
            return domain.split(",");

        } catch (ConnectException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return null;

    }

    @Override
    public String addInformationAboutTrainingData() {
        logger.info("Information about training data produced by GitHubAIBuilder");
        LLMClient client = PipeManager.getActiveClient();
        String toSend = "Briefly describe the training steps for the model based " +
                "on the provided scripts. Provide concise information in list format.";

        String toadd = trainingFileString();

        if (toadd == null) {
            logger.error("No training files found in the repository, cannot add information about training data");
            return null;
        }

        toSend += toadd;

        try {

            toSend = toSend.replace("\"", ""); // escape quotes
            toSend = toSend.replace("\'", ""); // escape single quotes
            toSend = toSend.replace("\n", " "); // remove new lines
            toSend = toSend.replace("\\", " "); // remove carriage returns

            String infoTrainingData = client.chat(toSend, false);
            return infoTrainingData;

        } catch (ConnectException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return null;

    }

    @Override
    public String addTypeOfModel() {
        logger.info("Type of model produced by GitHubAIBuilder");
        LLMClient client = PipeManager.getActiveClient();
        String toSend = "briefly describe the model "
                + " , use 20 words max, produce only the text to fill \"type of model\" AIBOM field and nothing else";

        String toadd = trainingFileString();

        if (toadd == null) {
            logger.error("No training files found in the repository, cannot add type of model");
            return null;
        }

        toSend += toadd;

        try {

            toSend = toSend.replace("\"", ""); // escape quotes
            toSend = toSend.replace("\'", ""); // escape single quotes
            toSend = toSend.replace("\n", " "); // remove new lines
            toSend = toSend.replace("\\", " "); // remove carriage returns

            String typeOfModel = client.chat(toSend, false);
            return typeOfModel;

        } catch (ConnectException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return null;
    }

    @Override
    public String addLimitations() {
        logger.info("Limitations produced by GitHubAIBuilder");
        LLMClient client = PipeManager.getActiveClient();
        String toSend = "briefly describe the limitations of the model "
                + " , use 20 words max, produce only the text to fill \"limitations\" AIBOM field and nothing else";

        String toadd = trainingFileString();
        if (toadd == null) {
            logger.error("No training files found in the repository, cannot add limitations");
            return null;
        }

        toSend += toadd;

        try {

            toSend = toSend.replace("\"", ""); // escape quotes
            toSend = toSend.replace("\'", ""); // escape single quotes
            toSend = toSend.replace("\n", " "); // remove new lines
            toSend = toSend.replace("\\", " "); // remove carriage returns

            String limitations = client.chat(toSend, false);
            return limitations;

        } catch (ConnectException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return null;
    }

    @Override
    public String[] addModelExplainability() {
        logger.info("Model explainability produced by GitHubAIBuilder");
        LLMClient client = PipeManager.getActiveClient();
        String toSend = "Infer a list of possible explainability methods that can be used to explain the model based on the following training scripts: "
                + "just answer with a list of methods, no other text \n";

        String toadd = trainingFileString();

        if (toadd == null) {
            logger.error("No training files found in the repository, cannot add model explainability");
            return null;
        }

        toSend += toadd;

        try {

            toSend = toSend.replace("\"", ""); // escape quotes
            toSend = toSend.replace("\'", ""); // escape single quotes
            toSend = toSend.replace("\n", " "); // remove new lines
            toSend = toSend.replace("\\", " "); // remove carriage returns

            String modelExplainability = client.chat(toSend, false);
            return modelExplainability.split("-[^A-Za-z0-9]");

        } catch (ConnectException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return null;
    }

    @Override
    public String addInformationAboutApplication() {
        logger.info("Information about application produced by GitHubAIBuilder");
        LLMClient client = PipeManager.getActiveClient();
        String toSend = "using given documentation files please describe the usages of the "
                + "Describe how the application uses the model to reach its goals. This is required to complete the information about application field in the AIBOM (max 20 words)";

        String toadd = documentationFileString();
        if (toadd == null) {
            if (!this.applicationDocumentation.isEmpty())
                logger.error(
                        "No documentation files found in the repository, cannot add information about application");
            return null;
        }

        toSend += toadd;

        try {

            toSend = toSend.replace("\"", ""); // escape quotes
            toSend = toSend.replace("\'", ""); // escape single quotes
            toSend = toSend.replace("\n", " "); // remove new lines
            toSend = toSend.replace("\\", " "); // remove carriage returns

            String applicationName = client.chat(toSend, false);
            return applicationName;

        } catch (ConnectException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return null;
    }

    private String documentationFileString() {
        String toSend = "";

        if (applicationFileContent.isEmpty()) {
            for (GHContent content : applicationDocumentation) {
                try {
                    String text = readRepositoryFile(content);
                    this.applicationFileContent.add(text);
                    toSend = toSend + "\n" + text;

                } catch (IOException e) {
                    logger.error("Error reading content from GitHub: " + content.getPath(), e);
                }
            }

            if (applicationDocumentation.isEmpty()) {
                return null;
            }
            return toSend;
        }
        return applicationFileContent.stream()
                .reduce("", (partialString, element) -> partialString + "\n" + element);
    }

    private String trainingFileString() {
        String toSend = "";

        if (trainingFileContent.isEmpty()) {
            for (GHContent content : trainingFiles) {
                try {
                    String text = readRepositoryFile(content);
                    this.trainingFileContent.add(text);
                    toSend = toSend + "\n" + text;

                } catch (IOException e) {
                    logger.error("Error reading content from GitHub: " + content.getPath(), e);
                }
            }

            if (toSend.equals("")) {
                return null; // no training files found
            }

            return toSend;
        }
        return trainingFileContent.stream()
                .reduce("", (partialString, element) -> partialString + "\n" + element);
    }

    private String readRepositoryFile(GHContent content) throws IOException {
        try (InputStream in = content.read()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (content.getPath() != null && content.getPath().endsWith(".ipynb")) {
                return extractNotebookText(text, content.getPath());
            }
            return text;
        }
    }

    private String extractNotebookText(String notebookJson, String path) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode root = mapper.readTree(notebookJson);
            JsonNode cells = root.path("cells");
            if (!cells.isArray()) {
                return notebookJson;
            }

            StringBuilder extracted = new StringBuilder();
            for (JsonNode cell : cells) {
                String cellType = cell.path("cell_type").asText("");
                if (!cellType.equals("code") && !cellType.equals("markdown")) {
                    continue;
                }

                extracted.append("\n# ").append(cellType).append(" cell from ").append(path).append("\n");
                JsonNode source = cell.path("source");
                if (source.isArray()) {
                    for (JsonNode line : source) {
                        extracted.append(line.asText());
                    }
                } else if (source.isTextual()) {
                    extracted.append(source.asText());
                }
                extracted.append("\n");
            }

            return extracted.toString();
        } catch (JsonProcessingException e) {
            logger.warn("Unable to parse notebook {}, using raw content", path);
            return notebookJson;
        }
    }

    private static class GitHubBuilderConfig {
        public String userRepoString;
        public String branch = "main";
        public String accessToken = "";
        public List<String> trainingFiles;
        public List<String> preprocessingFiles;
        public List<String> applicationFiles;

        static GitHubBuilderConfig readConfig(String path) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            try {

                GitHubBuilderConfig config = mapper.readValue(new File(path), GitHubBuilderConfig.class);
                config.resolveConfigValues();
                return config;

            } catch (JsonProcessingException e) {
                logger.error("Error while loading GitHub builder configuration: YAML file is misconfigured", e);
            } catch (IOException e) {
                logger.error("Error while loading GitHub builder configuration: YAML file was not found", e);
            }

            return null;
        }

        private void resolveConfigValues() {
            userRepoString = PipeManager.resolveConfigValue(userRepoString);
            branch = PipeManager.resolveConfigValue(branch);
            accessToken = PipeManager.resolveConfigValue(accessToken);
            trainingFiles = PipeManager.resolveConfigValues(trainingFiles);
            preprocessingFiles = PipeManager.resolveConfigValues(preprocessingFiles);
            applicationFiles = PipeManager.resolveConfigValues(applicationFiles);
        }

    }
}
