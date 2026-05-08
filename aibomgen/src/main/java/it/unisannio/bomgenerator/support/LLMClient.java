// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.support;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;

public interface LLMClient {

        /**
         * Sends a message to the LLM server and returns the response. Note: if the
         * model is a thinking model the answer will still contain the reasoning part of
         * the answer.
         * 
         * 
         * @throws InterruptedException
         * @throws IOException
         */
        public String chat(String message, boolean reasoning)
                        throws IOException, InterruptedException, ConnectException;

        public String chat(String[] conversation, String message)
                        throws IOException, InterruptedException, ConnectException;

        public String inferFormatted(String prompt, String fieldName)
                        throws IOException, InterruptedException, ConnectException;

        public String inferFormatted(String message, String fieldName, boolean isList)
                        throws IOException, InterruptedException, ConnectException;

        public String inferFormatted(String message, String system, String fieldName, boolean isList)
                        throws IOException, InterruptedException, ConnectException;

        public void setupURI(String uri);

        public void setupModel(String model);

        public String packageFilesContentInString(String message, File[] files);

        public String packageFilesContentInString(String message, File file);

}
