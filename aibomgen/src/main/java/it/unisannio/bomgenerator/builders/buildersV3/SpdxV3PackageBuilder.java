// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.builders.buildersV3;

import org.spdx.library.model.v3_0_1.core.SpdxDocument.SpdxDocumentBuilder;
import it.unisannio.bomgenerator.builders.MySpdxPackageBuilder;

/**
 * Specialized version of SpdxPackageBuilder used to support SPDX V3.0.1 format.
 * This class primarily serves organizational purposes and doesn't introduce
 * specific
 * behavior beyond providing the V3.0.1 version identifier.
 * 
 * This class can be further specialized to create individual builders for each
 * package
 * within an AIBOM (AI Bill of Materials).
 */
public abstract class SpdxV3PackageBuilder extends MySpdxPackageBuilder {
    SpdxDocumentBuilder builder;

    public enum V3Type {
        DatasetType,
        AIType;
    }

    public abstract V3Type getType();

    public SpdxV3PackageBuilder(String stepname, String codeName) {
        super(stepname, codeName);
    }

    @Override
    public final String getSpdxVersion() {
        return "3";
    }

}
