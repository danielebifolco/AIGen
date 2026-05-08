// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.fileSerializer;

import java.nio.file.Path;
import java.util.Hashtable;
import java.util.List;

import it.unisannio.bomgenerator.PipeManager.ToolConfig.AuthorDescriptor;
import it.unisannio.bomgenerator.PipeManager.ToolConfig.TeamDescriptor;

public interface FileSerializer {

    public default void setOutputPath(Path outputPath) {
    }

    public void init(List<TeamDescriptor> buildersList, List<AuthorDescriptor> authors);

    public void serialize(List<Hashtable<String, Object>> data);

}
