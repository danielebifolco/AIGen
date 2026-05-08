// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.builders.buildersV3.Dataset;

import it.unisannio.bomgenerator.builders.buildersV3.SpdxV3PackageBuilder;

public abstract class DatasetPackageBuilderV3 extends SpdxV3PackageBuilder {

    public DatasetPackageBuilderV3(String stepname, String codeName) {
        super(stepname, codeName);
    }

    @Override
    public V3Type getType() {
        return V3Type.DatasetType;
    }

    public String addBuildTime() {
        return null;
    }

    public String[] addDatasetType() {
        return null;
    }

    public String addDownloadLocation() {
        return null;
    }

    public String addOriginatedBy() {
        return null;
    }

    public String addPackageVersion() {
        return null;
    }

    public String addPrimaryPurpose() {
        return null;
    }

    public String addPackageName() {
        return null;
    }

    public String addReleaseTime() {
        return null;
    }

    public String addSpdxId() {
        return null;
    }

    public String addRelationshipTypeHasConcludedLicense() {
        return null;
    }

    public String addDeclaredLicense() {
        return null;
    }

    // DatasetPackage (optional)
    public String addAnonimizationMethodUsed() {
        return null;
    }

    public String addConfidentialityLevel() {
        return null;
    }

    public String addDataCollectionProcess() {
        return null;
    }

    public String addDataPreprocessing() {
        return null;
    }

    public String addDatasetAvailability() {
        return null;
    }

    public String addDatasetNoise() {
        return null;
    }

    public Integer addDatasetSize() {
        return null;
    }

    public String addDatasetUpdateMechanism() {
        return null;
    }

    public String addHasSensitivePersonalInformation() {
        return null;
    }

    public String addIntendedUse() {
        return null;
    }

    public String addKnownBias() {
        return null;
    }

    public String addSensor() {
        return null;
    }

}
