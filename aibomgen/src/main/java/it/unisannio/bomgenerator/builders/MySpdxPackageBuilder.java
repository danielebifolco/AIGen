// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.builders;

/**
 * A generic package builder that represents a unit of operation within a
 * processing pipeline.
 * This abstract class is agnostic to both the version and type of package
 * produced,
 * allowing specialized implementations to support different versions of the
 * SPDX format
 * while maintaining compatibility with the pipeline infrastructure.
 */
public abstract class MySpdxPackageBuilder {
    public enum RetrievalResult {
        SUCCESS,
        FAILURE,
        NOT_FOUND,
        NOT_IMPLEMENTED
    }

    /**
     * The next builder in the processing pipeline.
     */
    public MySpdxPackageBuilder nextInLine;
    private String stepName;
    public String codeName;

    public abstract boolean initBuilder();

    public MySpdxPackageBuilder(String stepname, String codeName) {
        this.stepName = stepname;
        this.codeName = codeName;
    }

    public String getBuilderName() {
        return stepName;
    }

    /**
     * Returns the SPDX version supported by this builder.
     * 
     * @return The SPDX version string
     */
    public abstract String getSpdxVersion();

}
