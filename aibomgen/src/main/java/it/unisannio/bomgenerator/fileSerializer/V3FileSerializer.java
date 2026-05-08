// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.fileSerializer;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spdx.core.InvalidSPDXAnalysisException;
import org.spdx.library.ModelCopyManager;
import org.spdx.library.SpdxModelFactory;
import org.spdx.library.model.v3_0_1.SpdxModelClassFactoryV3;
import org.spdx.library.model.v3_0_1.ai.AIPackage.AIPackageBuilder;
import org.spdx.library.model.v3_0_1.core.Agent;
import org.spdx.library.model.v3_0_1.core.CreationInfo;
import org.spdx.library.model.v3_0_1.core.ExternalIdentifierType;
import org.spdx.library.model.v3_0_1.core.DictionaryEntry.DictionaryEntryBuilder;
import org.spdx.library.model.v3_0_1.core.ExternalIdentifier;
import org.spdx.library.model.v3_0_1.core.PresenceType;
import org.spdx.library.model.v3_0_1.core.RelationshipType;
import org.spdx.library.model.v3_0_1.dataset.DatasetType;
import org.spdx.library.model.v3_0_1.dataset.DatasetPackage.DatasetPackageBuilder;
import org.spdx.library.model.v3_0_1.software.SoftwarePurpose;
import org.spdx.library.model.v3_0_1.software.SpdxPackage;
import org.spdx.library.model.v3_0_1.software.SpdxPackage.SpdxPackageBuilder;
import org.spdx.storage.simple.InMemSpdxStore;
import org.spdx.v3jsonldstore.JsonLDStore;

import it.unisannio.bomgenerator.PipeManager.ToolConfig.AuthorDescriptor;
import it.unisannio.bomgenerator.PipeManager.ToolConfig.TeamDescriptor;

public class V3FileSerializer implements FileSerializer {

    private Path outputPath = Path.of("AIBOM.json");
    private Queue<SpdxPackageBuilder> builders = new LinkedList<>();
    private Queue<List<String>> tags = new LinkedList<>();
    private CreationInfo creationInfo;
    private JsonLDStore jsonStore;
    private Logger logger = LoggerFactory.getLogger(V3FileSerializer.class.getName());
    private AIPackageBuilder aiBuilder;
    private List<PendingDeclaredLicense> pendingDeclaredLicenses = new LinkedList<>();

    private Queue<SpdxPackageBuilder> executedBuilders = new LinkedList<>();

    @Override
    public void setOutputPath(Path outputPath) {
        if (outputPath != null) {
            this.outputPath = outputPath;
        }
    }

    @Override
    public void serialize(List<Hashtable<String, Object>> resultsList) {
        List<String> invokedForAI = new LinkedList<>();
        List<String> invokedForDataset = new LinkedList<>();

        for (Hashtable<String, Object> entry : resultsList) {
            SpdxPackageBuilder docBuilder = builders.poll();

            // for each result produced by the agents of this team, invoke the corresponding
            // method to produce the final result in the spdx document
            for (Map.Entry<String, Object> key : entry.entrySet()) {
                String methodName = key.getKey();
                Object argument = key.getValue();

                try {

                    if (argument == null) {
                        logger.warn("The argument for method {} is null, skipping.", methodName);
                        continue;
                    }

                    Method method = this.getClass()
                            .getDeclaredMethod(methodName, SpdxPackageBuilder.class, Object.class);
                    method.invoke(this, docBuilder, argument);

                    if (docBuilder instanceof AIPackageBuilder) {
                        invokedForAI.add(methodName);
                    } else if (docBuilder instanceof DatasetPackageBuilder) {
                        invokedForDataset.add(methodName);
                    }

                } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException
                        | SecurityException e) {
                    logger.error(
                            "A field with name {} was produced by an agent, something went wrong during the serialization process, skipping it.",
                            methodName, e);
                }
            }

            if (aiBuilder == docBuilder)
                continue;
            executedBuilders.add(docBuilder);
        }

        checkMandatoryFileds(invokedForAI, invokedForDataset);

        try {

            var aiPackage = aiBuilder.build();
            createDeclaredLicenseRelationships(aiPackage, aiBuilder);
            tags.poll(); // remove the tags for the AI builder

            for (SpdxPackageBuilder builder : executedBuilders) {
                List<String> tagList = tags.poll();
                if (tagList == null) {
                    tagList = List.of();
                }
                var datasetPackage = builder.build();
                createDeclaredLicenseRelationships(datasetPackage, builder);

                if (tagList.contains("train"))
                    creationInfo.createRelationship("SpdxRef:trainRelationship/" + Math.abs(builder.hashCode()))
                            .setFrom(aiPackage)
                            .setRelationshipType(RelationshipType.TRAINED_ON)
                            .addTo(datasetPackage).build();

                if (tagList.contains("test"))
                    creationInfo.createRelationship("SpdxRef:testRelationship/" + Math.abs(builder.hashCode()))
                            .setFrom(aiPackage)
                            .setRelationshipType(RelationshipType.TESTED_ON)
                            .addTo(datasetPackage).build();
            }

        } catch (InvalidSPDXAnalysisException e) {
            e.printStackTrace();
        }

        try {
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            jsonStore.serialize(new FileOutputStream(outputPath.toFile()));
            logger.info("AIBOM serialized to {}", outputPath);
        } catch (IOException | InvalidSPDXAnalysisException e) {
            e.printStackTrace();
        }
    }

    private void checkMandatoryFileds(List<String> aiMethodsList, List<String> datasetMethodsList) {
        logger.info("====== Validating mandatory fields for packages ======");
        String[] mandatoryFields = {
                "addPackageName", "addPackageVersion", "addSuppliedBy", "addReleaseTime",
                "addBuildTime", "addDownloadLocation", "addDeclaredLicense",

        };

        for (String field : mandatoryFields) {
            if (!aiMethodsList.contains(field)) {
                logger.warn(
                        "The field {} is mandatory for the AI package, but it is missing, the resulting AIBOM may not be valid.",
                        field);
            }

        }

        String[] mandatoryFieldsDataset = {
                "addPackageName", "addPackageVersion", "addOriginatedBy", "addReleaseTime",
                "addBuildTime", "addDownloadLocation", "addPrimaryPurpose", "addDeclaredLicense",
                "addDatasetType",
        };

        for (String field : mandatoryFieldsDataset) {

            // this check against executedBuilders count -1 because there is always an AI
            // builder
            if (datasetMethodsList.stream().filter(f -> f.equals(field)).count() != executedBuilders.size()) {
                logger.warn(
                        "The field {} is not present in all the Dataset packages, the resulting AIBOM may not be valid.",
                        field);
            }
        }
    }

    @Override
    public void init(List<TeamDescriptor> buildersList, List<AuthorDescriptor> authors) {
        try {

            SpdxModelFactory.init();
            InMemSpdxStore inMemSpdxStore = new InMemSpdxStore();
            this.jsonStore = new JsonLDStore(inMemSpdxStore, true);
            this.creationInfo = SpdxModelClassFactoryV3.createCreationInfo(inMemSpdxStore,
                    "SpdxRef:author/" + authors.hashCode(), authors.get(0).name,
                    new ModelCopyManager());

            for (AuthorDescriptor authorName : authors) {

                ExternalIdentifier identifier = creationInfo
                        .createExternalIdentifier("SpdxRef:externalIdentifier/" + Math.abs(authorName.name.hashCode()))
                        .setExternalIdentifierType(ExternalIdentifierType.EMAIL).setIdentifier(authorName.email)
                        .build();
                Agent ag = creationInfo.createAgent("SpdxRef:creatorAgent/" + Math.abs(authorName.name.hashCode()))
                        .setName(authorName.name).addExternalIdentifier(identifier).build();

                this.creationInfo.getCreatedBys()
                        .add(ag);

            }

        } catch (InvalidSPDXAnalysisException e) {
            e.printStackTrace();
        }

        for (TeamDescriptor team : buildersList) {

            AIPackageBuilder aiBuilder;
            DatasetPackageBuilder datasetPackageBuilder;

            try {

                if (team.type.equals("AI")) {

                    aiBuilder = creationInfo
                            .createAIPackage("SpdxRef:model/" + Math.abs(team.teamName.hashCode()));
                    this.builders.add(aiBuilder);
                    this.tags.add(team.tags);
                    this.aiBuilder = aiBuilder;

                } else {

                    datasetPackageBuilder = creationInfo
                            .createDatasetPackage("SpdxRef:dataset/" + Math.abs(team.teamName.hashCode()));
                    this.builders.add((SpdxPackageBuilder) datasetPackageBuilder);
                    this.tags.add(team.tags);
                }

            } catch (InvalidSPDXAnalysisException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

        }
    }

    // #region Methods to add fields to the AIPackageBuilder
    public void addBuildTime(SpdxPackageBuilder docBuilder, Object buildTime) {
        logger.debug("Invoked addBuildTime with argument: {}", buildTime);

        if (docBuilder instanceof AIPackageBuilder) {
            AIPackageBuilder builder = (AIPackageBuilder) docBuilder;
            builder.setBuiltTime((String) buildTime);
            return;
        }

        DatasetPackageBuilder builder = (DatasetPackageBuilder) docBuilder;
        builder.setBuiltTime((String) buildTime);
    }

    public void addDownloadLocation(SpdxPackageBuilder docBuilder, Object downloadLocation) {
        logger.debug("Invoked addDownloadLocation with argument: {}", downloadLocation);
        docBuilder.setDownloadLocation((String) downloadLocation);
    }

    public PresenceType addUseSensitivePersonalInformation(SpdxPackageBuilder docBuilder,
            Object useSensitivePersonalInformation) {
        logger.debug("Invoked addUseSensitivePersonalInformation with argument: {}", useSensitivePersonalInformation);
        AIPackageBuilder builder = (AIPackageBuilder) docBuilder;
        builder.setUseSensitivePersonalInformation((PresenceType) useSensitivePersonalInformation);
        return (PresenceType) useSensitivePersonalInformation;
    }

    public void addPackageVersion(SpdxPackageBuilder docBuilder, Object packageVersion) {
        logger.debug("Invoked addPackageVersion with argument: {}", packageVersion);
        docBuilder.setPackageVersion((String) packageVersion);
    }

    public void addPackageName(SpdxPackageBuilder docBuilder, Object packageName) {
        logger.debug("Invoked addPackageName with argument: {}", packageName);
        docBuilder.setName((String) packageName);
    }

    public void addPrimaryPurpose(SpdxPackageBuilder docBuilder, Object primaryPurpose) {
        logger.debug("Invoked addPrimaryPurpose; argument ignored: {}", primaryPurpose);
        if (docBuilder instanceof AIPackageBuilder) {
            docBuilder.setPrimaryPurpose(SoftwarePurpose.MODEL);
        } else {
            docBuilder.setPrimaryPurpose(SoftwarePurpose.DATA);
        }
    }

    public void addSuppliedBy(SpdxPackageBuilder docBuilder, Object suppliedBy) {
        logger.debug("Invoked addSuppliedBy with argument: {}", suppliedBy);
        try {
            docBuilder.setSuppliedBy(
                    creationInfo.createAgent("SpdxRef:creatorAgent/" + Math.abs(suppliedBy.hashCode()))
                            .setName((String) suppliedBy)
                            .build());
        } catch (InvalidSPDXAnalysisException e) {
            logger.error("Error in addSuppliedBy", e);
        }
    }

    public void addOriginatedBy(SpdxPackageBuilder docBuilder, Object suppliedBy) {
        logger.debug("Invoked addOriginatedBy with argument: {}", suppliedBy);
        try {
            docBuilder.addOriginatedBy(
                    creationInfo.createAgent("SpdxRef:creatorAgent/" + Math.abs(suppliedBy.hashCode()))
                            .setName((String) suppliedBy)
                            .build());
        } catch (InvalidSPDXAnalysisException e) {
            logger.error("Error in addOriginatedBy", e);
        }
    }

    public void addReleaseTime(SpdxPackageBuilder docBuilder, Object releaseTime) {
        logger.debug("Invoked addReleaseTime with argument: {}", releaseTime);
        if (docBuilder instanceof AIPackageBuilder) {
            AIPackageBuilder builder = (AIPackageBuilder) docBuilder;
            builder.setReleaseTime((String) releaseTime);
            return;
        }

        DatasetPackageBuilder builder = (DatasetPackageBuilder) docBuilder;
        builder.setReleaseTime((String) releaseTime);
    }

    public void addDeclaredLicense(SpdxPackageBuilder docBuilder, Object declaredLicense) {
        logger.debug("Invoked addDeclaredLicense with argument: {}", declaredLicense);
        if (declaredLicense == null || ((String) declaredLicense).isBlank()) {
            return;
        }

        pendingDeclaredLicenses.add(new PendingDeclaredLicense(docBuilder, (String) declaredLicense));
    }

    private void createDeclaredLicenseRelationships(SpdxPackage spdxPackage, SpdxPackageBuilder docBuilder) {
        for (PendingDeclaredLicense pendingLicense : pendingDeclaredLicenses) {
            if (pendingLicense.docBuilder != docBuilder) {
                continue;
            }

            String relationshipId = "SpdxRef:declaredLicense/"
                    + Math.abs((docBuilder.hashCode() + pendingLicense.licenseName).hashCode());
            String licenseId = "SpdxRef:licenseInfo/"
                    + Math.abs((docBuilder.hashCode() + pendingLicense.licenseName).hashCode());

            createDeclaredLicenseRelationship(spdxPackage, relationshipId, licenseId, pendingLicense.licenseName);
        }
    }

    private void createDeclaredLicenseRelationship(SpdxPackage spdxPackage, String relationshipId, String licenseId,
            String licenseName) {
        try {
            this.creationInfo.createRelationship(relationshipId)
                    .setFrom(spdxPackage)
                    .addTo(creationInfo
                            .createAnyLicenseInfo(licenseId)
                            .setName(licenseName).build())
                    .setRelationshipType(RelationshipType.HAS_DECLARED_LICENSE)
                    .build();
        } catch (InvalidSPDXAnalysisException e) {
            logger.error("Error in addDeclaredLicense", e);
        }
    }

    private static class PendingDeclaredLicense {
        private final SpdxPackageBuilder docBuilder;
        private final String licenseName;

        PendingDeclaredLicense(SpdxPackageBuilder docBuilder, String licenseName) {
            this.docBuilder = docBuilder;
            this.licenseName = licenseName;
        }
    }

    public void addHyperparameters(SpdxPackageBuilder docBuilder, Object hyperparameters) {
        logger.debug("Invoked addHyperparameters with argument: {}", hyperparameters);

        AIPackageBuilder builder = (AIPackageBuilder) docBuilder;
        Dictionary<String, String> hyperparamDict = (Dictionary<String, String>) hyperparameters;

        Enumeration<String> keys = hyperparamDict.keys();
        while (keys.hasMoreElements()) {
            String key = keys.nextElement();
            String value = hyperparamDict.get(key);

            try {
                DictionaryEntryBuilder entryBuilder = creationInfo.createDictionaryEntry(key);
                entryBuilder.setKey(value.toString()); // numeric value as key
                entryBuilder.setValue((String) key); // parameter name as value
                builder.addHyperparameter(entryBuilder.build());

                logger.debug("Added hyperparameter: key = {}, value = {}", key, value);
            } catch (InvalidSPDXAnalysisException e) {
                logger.error("Error in addHyperparameters with key: {}", key, e);
            }
        }
    }

    public void addMetrics(SpdxPackageBuilder docBuilder, Object metrics) {
        logger.debug("Invoked addMetrics with argument: {}", metrics);

        AIPackageBuilder builder = (AIPackageBuilder) docBuilder;
        Dictionary<String, String> metricDict = (Dictionary<String, String>) metrics;

        Enumeration<String> keys = metricDict.keys();
        while (keys.hasMoreElements()) {
            String key = keys.nextElement();
            String value = metricDict.get((String) key);

            try {
                DictionaryEntryBuilder entryBuilder = creationInfo.createDictionaryEntry(key);
                entryBuilder.setKey(value.toString()); // numeric value as key
                entryBuilder.setValue((String) key); // metric name as value
                builder.addMetric(entryBuilder.build());

                logger.debug("Added metric: key = {}, value = {}", key, value);
            } catch (InvalidSPDXAnalysisException e) {
                logger.error("Error in addMetrics with key: {}", key, e);
            }
        }
    }

    public void addDomain(SpdxPackageBuilder docBuilder, Object domain) {
        logger.debug("Invoked addDomain with argument: {}", domain);
        AIPackageBuilder builder = (AIPackageBuilder) docBuilder;
        String[] domains = (String[]) domain;

        for (String string : domains) {
            builder.addDomain(string);
        }
    }

    public void addInformationAboutTrainingData(SpdxPackageBuilder docBuilder, Object trainingData) {
        logger.debug("Invoked addInformationAboutTrainingData with argument: {}", trainingData);
        AIPackageBuilder builder = (AIPackageBuilder) docBuilder;
        builder.setInformationAboutTraining((String) trainingData);
    }

    public void addTypeOfModel(SpdxPackageBuilder docBuilder, Object typeOfModel) {
        logger.debug("Invoked addTypeOfModel with argument: {}", typeOfModel);
        AIPackageBuilder builder = (AIPackageBuilder) docBuilder;
        builder.addTypeOfModel((String) typeOfModel);
    }

    public void addLimitations(SpdxPackageBuilder docBuilder, Object limitations) {
        logger.debug("Invoked addLimitations with argument: {}", limitations);
        AIPackageBuilder builder = (AIPackageBuilder) docBuilder;
        builder.setLimitation((String) limitations);
    }

    public void addModelExplainability(SpdxPackageBuilder docBuilder, Object modelExplainability) {
        logger.debug("Invoked addModelExplainability with argument: {}", modelExplainability);
        AIPackageBuilder builder = (AIPackageBuilder) docBuilder;
        String[] explainability = (String[]) modelExplainability;
        for (String string : explainability) {
            if (!string.isBlank())
                builder.addModelExplainability(string);
        }
    }

    public void addInformationAboutApplication(SpdxPackageBuilder docBuilder, Object informationAboutApplication) {
        logger.debug("Invoked addInformationAboutApplication with argument: {}", informationAboutApplication);
        AIPackageBuilder builder = (AIPackageBuilder) docBuilder;
        builder.setInformationAboutApplication((String) informationAboutApplication);
    }

    public void addStandardCompliance(SpdxPackageBuilder docBuilder, Object standardCompliance) {
        logger.debug("Invoked addStandardCompliance with argument: {}", standardCompliance);
        AIPackageBuilder builder = (AIPackageBuilder) docBuilder;
        builder.addStandardCompliance((String) standardCompliance);
    }

    public void addDataPreprocessing(SpdxPackageBuilder docBuilder, Object dataPreprocessing) {
        logger.debug("Invoked addDataPreprocessing with argument: {}", dataPreprocessing);
        AIPackageBuilder builder = (AIPackageBuilder) docBuilder;
        builder.addModelDataPreprocessing((String) dataPreprocessing);
    }

    // #endregion

    // #region Dataset specific methods
    public void addDatasetType(SpdxPackageBuilder docBuilder, Object datasetType) {
        logger.debug("Serializing addDatasetType value: {}", datasetType);
        DatasetPackageBuilder builder = (DatasetPackageBuilder) docBuilder;
        String[] types = (String[]) datasetType;

        for (String string : types) {
            // Ignore malformed values returned by inference.
            if (string.length() > 2)
                builder.addDatasetType(DatasetType.valueOf(string.trim()));
        }

    }

    public void addAnonimizationMethodUsed(SpdxPackageBuilder docBuilder, Object datasetType) {
        logger.debug("Anonymization method used: {}", datasetType);
        DatasetPackageBuilder dataBuilder = (DatasetPackageBuilder) docBuilder;
        dataBuilder.addAnonymizationMethodUsed((String) datasetType);
    }

    public void addDataCollectionProcess(SpdxPackageBuilder docBuilder, Object dataCollectionProcess) {
        logger.debug("Data collection process: {}", dataCollectionProcess);
        DatasetPackageBuilder dataBuilder = (DatasetPackageBuilder) docBuilder;
        dataBuilder.setDataCollectionProcess((String) dataCollectionProcess);
    }

    public void addDatasetSize(SpdxPackageBuilder docBuilder, Object datasetSize) {
        logger.debug("Dataset size: {}", datasetSize);
        DatasetPackageBuilder dataBuilder = (DatasetPackageBuilder) docBuilder;
        dataBuilder.setDatasetSize((Integer) datasetSize);
    }

    public void addIntendedUse(SpdxPackageBuilder docBuilder, Object intendedUse) {
        logger.debug("Intended use: {}", intendedUse);
        DatasetPackageBuilder dataBuilder = (DatasetPackageBuilder) docBuilder;
        dataBuilder.setIntendedUse((String) intendedUse);
    }

    // #endregion
}
