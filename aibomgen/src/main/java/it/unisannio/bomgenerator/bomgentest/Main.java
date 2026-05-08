// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.bomgentest;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;

import org.spdx.core.InvalidSPDXAnalysisException;

import it.unisannio.bomgenerator.PipeManager;

public class Main {
        public static void main(String[] args) throws FileNotFoundException, IOException, InvalidSPDXAnalysisException {
                CliOptions options = parseArgs(args);
                if (options.showHelp) {
                        printUsage();
                        return;
                }

                new PipeManager(options.configPath, options.outputPath);
        }

        private static CliOptions parseArgs(String[] args) {
                CliOptions options = new CliOptions();

                for (int i = 0; i < args.length; i++) {
                        String arg = args[i];
                        switch (arg) {
                                case "--config":
                                case "-c":
                                        options.configPath = readPathArg(args, ++i, arg);
                                        break;
                                case "--output":
                                case "-o":
                                        options.outputPath = readPathArg(args, ++i, arg);
                                        break;
                                case "--help":
                                case "-h":
                                        options.showHelp = true;
                                        break;
                                default:
                                        throw new IllegalArgumentException("Unknown argument: " + arg);
                        }
                }

                return options;
        }

        private static Path readPathArg(String[] args, int index, String option) {
                if (index >= args.length || args[index].isBlank()) {
                        throw new IllegalArgumentException("Missing value for " + option);
                }
                return Path.of(args[index]);
        }

        private static void printUsage() {
                System.out.println("""
                                Usage: mvn exec:java -Dexec.args="[options]"

                                Options:
                                  -c, --config <path>   Pipeline YAML configuration path.
                                  -o, --output <path>   Output SPDX JSON-LD file path.
                                  -h, --help            Show this help message.
                                """);
        }

        private static class CliOptions {
                private Path configPath;
                private Path outputPath;
                private boolean showHelp;
        }
}
