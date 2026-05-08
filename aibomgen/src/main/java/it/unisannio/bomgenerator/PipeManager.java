// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import it.unisannio.bomgenerator.fileSerializer.FileSerializer;
import it.unisannio.bomgenerator.scheduler.AgentsCoordinator;
import it.unisannio.bomgenerator.scheduler.AgentsCoordinator.TeamInvocations;
import it.unisannio.bomgenerator.support.LLMClient;

public class PipeManager {

    private static final Logger logger = LoggerFactory.getLogger(PipeManager.class.getName());
    private static final Path DEFAULT_OUTPUT_PATH = Paths.get("AIBOM.json");
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static LLMClient llmClient;
    private static Path activeAgentsConfigDirectory = defaultAgentConfigDirectory();
    boolean first = true;

    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_GREEN = "\u001B[32m";

    public PipeManager() {
        this(defaultConfigPath(), DEFAULT_OUTPUT_PATH);
    }

    public PipeManager(String configPath, String outputPath) {
        this(resolvePath(configPath, defaultConfigPath()), resolvePath(outputPath, DEFAULT_OUTPUT_PATH));
    }

    public PipeManager(Path configPath, Path outputPath) {
        logger.info("PipeManager initializing...");
        scheduleDirectoryDeletionOnExit(Paths.get("tmp"));

        Path resolvedConfigPath = configPath == null ? defaultConfigPath() : configPath;
        Path resolvedOutputPath = outputPath == null ? DEFAULT_OUTPUT_PATH : outputPath;
        setupAgentConfigDirectory(resolvedConfigPath);
        logger.info("Using pipeline configuration: {}", resolvedConfigPath);
        logger.info("AIBOM output path: {}", resolvedOutputPath);

        ToolConfig config = ToolConfig.readConfig(resolvedConfigPath.toString());

        if (config == null) {
            throw new IllegalStateException("Unable to load AIBOM pipeline configuration: " + resolvedConfigPath);
        }

        if (config.LLMClient != null)
            llmClient = setupLLMClient(config);

        // runs the pipe

        // Use teams to retrieve information from the configured sources.
        AgentsCoordinator scheduler = new AgentsCoordinator();
        scheduler.validateTeams(config.teams); // validate registered pipe

        logger.info(ANSI_GREEN + "==== starting teams initialization ====" + ANSI_RESET);
        List<TeamInvocations> pipes = scheduler.initInvocationsPipes(config.teams);

        logger.info(ANSI_GREEN + "==== Starting fields data retrieval ====" + ANSI_RESET);
        scheduler.executeInvocationsPipes(pipes);

        // use the produced packs to build the AIBOM file
        FileSerializer serializer = instantiateDocumentSerializer(config);
        if (serializer == null) {
            throw new IllegalStateException("No serializer found for SPDX version: " + config.spdxVersion);
        }

        serializer.setOutputPath(resolvedOutputPath);
        serializer.init(config.teams, config.authors);

        logger.info(ANSI_GREEN + "==== Starting AIBOM file generation ====" + ANSI_RESET);
        serializer.serialize(scheduler.getExecutionResults());

        logger.info("AIBOM generation completed.");

    }

    private static Path resolvePath(String path, Path defaultPath) {
        if (path == null || path.isBlank()) {
            return defaultPath;
        }
        return Paths.get(path);
    }

    private static Path defaultConfigPath() {
        Path repoRootPath = Paths.get("aibomgen", "src", "main", "java", "it", "unisannio", "bomgenerator",
                "AIbomPipe.yaml");
        if (Files.exists(repoRootPath)) {
            return repoRootPath;
        }
        return Paths.get("src", "main", "java", "it", "unisannio", "bomgenerator", "AIbomPipe.yaml");
    }

    private static Path defaultAgentConfigDirectory() {
        Path repoRootPath = Paths.get("aibomgen", "src", "main", "java", "it", "unisannio", "bomgenerator",
                "agentsConfig");
        if (Files.isDirectory(repoRootPath)) {
            return repoRootPath;
        }
        return Paths.get("src", "main", "java", "it", "unisannio", "bomgenerator", "agentsConfig");
    }

    private static void setupAgentConfigDirectory(Path configPath) {
        Path parent = configPath.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            activeAgentsConfigDirectory = defaultAgentConfigDirectory();
            return;
        }

        Path siblingAgentsConfig = parent.resolve("agentsConfig");
        if (Files.isDirectory(siblingAgentsConfig)) {
            activeAgentsConfigDirectory = siblingAgentsConfig;
        } else {
            activeAgentsConfigDirectory = defaultAgentConfigDirectory();
        }
    }

    public static Path getAgentConfigPath(String configFileName) {
        return activeAgentsConfigDirectory.resolve(configFileName);
    }

    public static String resolveConfigValue(String value) {
        if (value == null) {
            return null;
        }

        Matcher matcher = ENV_PLACEHOLDER.matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String variableName = matcher.group(1);
            String envValue = System.getenv(variableName);
            if (envValue == null) {
                logger.warn("Environment variable {} referenced in configuration but not set", variableName);
                envValue = "";
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(envValue));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    public static List<String> resolveConfigValues(List<String> values) {
        if (values == null) {
            return null;
        }

        List<String> resolvedValues = new LinkedList<>();
        for (String value : values) {
            resolvedValues.add(resolveConfigValue(value));
        }
        return resolvedValues;
    }

    private FileSerializer instantiateDocumentSerializer(ToolConfig config) {
        FileSerializer serializer;

        Reflections reflection = new Reflections("it.unisannio.bomgenerator.fileSerializer");
        Set<Class<? extends FileSerializer>> setOfImpl = reflection.getSubTypesOf(FileSerializer.class);
        Optional<Class<? extends FileSerializer>> serializerClass = setOfImpl.stream()
                .filter(c -> c.getSimpleName().equalsIgnoreCase("V" + config.spdxVersion + "FileSerializer"))
                .findFirst();

        if (!serializerClass.isPresent())
            return null;

        Class<? extends FileSerializer> cl = serializerClass.get();
        try {
            FileSerializer instance = cl.getConstructor().newInstance();
            return instance;

        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException
                | NoSuchMethodException | SecurityException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return null;
    }

    public static void scheduleDirectoryDeletionOnExit(Path dir) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (!Files.exists(dir)) {
                    return;
                }
                Files.walk(dir)
                        .sorted(Comparator.reverseOrder()) // Delete files before directories.
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (IOException e) {
                logger.error("Unable to delete tmp directory: " + dir);
                e.printStackTrace();
            }
        }));
    }

    public ToolConfig loadConfig(String path) {
        return ToolConfig.readConfig(path);
    }

    private LLMClient setupLLMClient(ToolConfig conf) {

        if (conf.LLMClient == null || conf.LLMClient.isEmpty()) {
            logger.warn("LLMClient not specified in configuration");
            return null;
        }

        try {

            llmClient = (LLMClient) Class
                    .forName("it.unisannio.bomgenerator.support." + conf.LLMClient)
                    .getConstructor().newInstance();

        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            logger.error("No valid LLMClient found; check the configuration file", e);
        }

        if (conf.LLMModel != null)
            llmClient.setupModel(conf.LLMModel);
        if (conf.LLMServer != null)
            llmClient.setupURI(conf.LLMServer);

        // test client
        try {
            llmClient.chat("this is a test", false);
        } catch (IOException | InterruptedException e) {
            logger.warn(
                    "LLMClient not reachable, check configuration file, pipe will try to continue without the LLM server");
            return null;
        }
        logger.info("LLMClient " + conf.LLMClient + " initialized successfully.");
        return llmClient;

    }

    /***
     * Return the LLMClient configured for this pipeline.
     */
    public static LLMClient getActiveClient() {
        return llmClient;
    }

    public static class ToolConfig {
        public String spdxVersion;
        public List<TeamDescriptor> teams;
        public List<AuthorDescriptor> authors;
        public String LLMServer;
        public String LLMModel;
        public String LLMClient;

        static ToolConfig readConfig(String path) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            try {

                ToolConfig config = mapper.readValue(new File(path), ToolConfig.class);
                config.resolveConfigValues();
                return config;

            } catch (JsonProcessingException e) {
                logger.error("Error while loading PipeManager configuration: AIbomPipe.yaml is misconfigured", e);
            } catch (IOException e) {
                logger.error("Error while loading PipeManager configuration: AIbomPipe.yaml was not found", e);
            }

            return null;
        }

        public ToolConfig() {
        }

        private void resolveConfigValues() {
            spdxVersion = PipeManager.resolveConfigValue(spdxVersion);
            LLMServer = PipeManager.resolveConfigValue(LLMServer);
            LLMModel = PipeManager.resolveConfigValue(LLMModel);
            LLMClient = PipeManager.resolveConfigValue(LLMClient);

            if (authors != null) {
                for (AuthorDescriptor author : authors) {
                    author.name = PipeManager.resolveConfigValue(author.name);
                    author.email = PipeManager.resolveConfigValue(author.email);
                }
            }

            if (teams != null) {
                for (TeamDescriptor team : teams) {
                    team.teamName = PipeManager.resolveConfigValue(team.teamName);
                    team.type = PipeManager.resolveConfigValue(team.type);
                    team.tags = PipeManager.resolveConfigValues(team.tags);

                    if (team.agents != null) {
                        for (AgentDescriptor agent : team.agents) {
                            agent.agentName = PipeManager.resolveConfigValue(agent.agentName);
                            agent.codeName = PipeManager.resolveConfigValue(agent.codeName);

                            if (agent.goals != null) {
                                for (GoalDescriptor goal : agent.goals) {
                                    goal.fieldName = PipeManager.resolveConfigValue(goal.fieldName);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Classes used only to deserialize the configuration file.
        public static class TeamDescriptor {
            public String teamName;
            public String type; // AI or Dataset
            public List<String> tags = new LinkedList<>();
            public List<AgentDescriptor> agents;

            public TeamDescriptor() {
            }
        }

        public static class AgentDescriptor {
            public String agentName;
            public String codeName; // used to identify the agent in the pipe
            public int priority;
            public List<GoalDescriptor> goals = new LinkedList<>();
        }

        public static class GoalDescriptor {
            public String fieldName;
            public int priority;

            public GoalDescriptor() {
            }

            public GoalDescriptor(String name, int priority) {
                this.fieldName = name;
                this.priority = priority;
            }
        }

        public static class AuthorDescriptor {
            public String name;
            public String email;

            public AuthorDescriptor() {
            }

            public AuthorDescriptor(String name, String email) {
                this.name = name;
                this.email = email;
            }
        }
    }

}
