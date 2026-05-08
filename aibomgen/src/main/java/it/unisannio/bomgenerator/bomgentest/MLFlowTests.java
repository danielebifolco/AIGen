// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.bomgentest;

import java.io.*;

import it.unisannio.bomgenerator.support.OllamaClient;

public class MLFlowTests {
    public static void main(String[] args) throws IOException, InterruptedException {
        OllamaClient clinet = new OllamaClient();
        String domain = clinet
                .inferFormatted(
                        "can you tell me which is the domain of application of a classfier of dos attack? just answer with the domain (2-3 words) no other text or brackets",
                        "domain");

        System.err.println("domain:" + domain);
    }

}
