// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.builders.buildersV3.AI;

import java.util.Dictionary;

import org.spdx.library.model.v3_0_1.core.PresenceType;

import it.unisannio.bomgenerator.builders.buildersV3.SpdxV3PackageBuilder;
import it.unisannio.bomgenerator.builders.buildersV3.SpdxV3PackageBuilder.V3Type;

public abstract class AIPackageBuilderV3 extends SpdxV3PackageBuilder {

    boolean isInitialized = false;

    @Override
    public V3Type getType() {
        return V3Type.AIType;
    }

    public AIPackageBuilderV3(String stepname, String codeName) {
        super(stepname, codeName);
    }

    public String addBuildTime() {
        return null;
    }

    public String addDownloadLocation() {
        return null;
    }

    public PresenceType addUseSensitivePersonalInformation() {
        return null;
    }

    public String addIdentifier() {
        return null;
    }

    public String addLicenseConcluded() {
        return null;
    }

    public String addDeclaredLicense() {
        return null;
    }

    public String addPackageVersion() {
        return null;
    }

    public String addPackageName() {
        return null;
    }

    public String addPrimaryPurpose() {
        return null;
    }

    public String addSuppliedBy() {
        return null;
    }

    public String addReleaseTime() {
        return null;
    }

    public Dictionary<String, String> addHyperparameters() {
        return null;
    }

    public Dictionary<String, String> addMetrics() {
        return null;
    }

    public String[] addDomain() {
        return null;
    }

    public String addInformationAboutTrainingData() {
        return null;
    }

    public String addTypeOfModel() {
        return null;
    }

    public String addLimitations() {
        return null;
    }

    public String[] addModelExplainability() {
        return null;
    }

    public String addInformationAboutApplication() {
        return null;
    }

    public String addStandardCompliance() {
        return null;
    }

    public String addDataPreprocessing() {
        return null;
    }

    public String addSafetyRiskAssessment() {
        return null;
    }
}
