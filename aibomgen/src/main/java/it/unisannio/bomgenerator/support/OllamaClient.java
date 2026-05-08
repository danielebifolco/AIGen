// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.support;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

public class OllamaClient implements LLMClient {

  private static final long DEFAULT_TIMEOUT_SECONDS = 60;
  private static final String DEFAULT_SYSTEM_PROMPT = "You are working in an AIBOM generator. Respond to each request by filling the requested fields of the AIBOM using the provided data. Never refer to yourself, the user, or what you are doing in the response. Produce only the requested fields.";
  private static final String DEFAULT_FORMATTED_SYSTEM_PROMPT = "You are working in an AIBOM generator. Respond to each request by filling the requested fields of the AIBOM using the provided data. Never refer to yourself or what you are doing in the response. Return only the requested fields.";

  private String serverUrl = "http://localhost:11434";
  private String model = "deepseek-r1";
  private Duration requestTimeout = Duration.ofSeconds(resolveTimeoutSeconds());
  private final Gson gson = new Gson();
  Logger logger = LoggerFactory.getLogger(this.getClass());

  public OllamaClient() {
  }

  @Override
  public String chat(String message, boolean reasoning) throws IOException, InterruptedException, ConnectException {
    String result;
    if (reasoning) {
      result = sendWithReasoningFallback(
          buildChatRequest(message, true),
          buildChatRequest(message, false),
          "Ollama chat request");
    } else {
      result = sendGenerateRequest(buildChatRequest(message, false));
    }

    logger.debug("Received response from Ollama server");
    logger.debug(result);

    return extractResponse(result);
  }

  @Override
  public String inferFormatted(String message, String fieldName, boolean isList)
      throws IOException, InterruptedException {
    String result = sendWithReasoningFallback(
        buildFormattedRequest(message, DEFAULT_FORMATTED_SYSTEM_PROMPT, fieldName, isList, true),
        buildFormattedRequest(message, DEFAULT_FORMATTED_SYSTEM_PROMPT, fieldName, isList, false),
        "Ollama formatted inference request");
    return extractFormattedResponse(extractResponse(result), fieldName);
  }

  public String inferFormatted(String message, String fieldName)
      throws IOException, InterruptedException {
    String result = sendWithReasoningFallback(
        buildFormattedRequest(message, null, fieldName, false, true),
        buildFormattedRequest(message, null, fieldName, false, false),
        "Ollama formatted inference request");
    return extractFormattedResponse(extractResponse(result), fieldName);
  }

  @Override
  public String chat(String[] conversation, String message) throws IOException, InterruptedException {
    StringBuilder prompt = new StringBuilder();
    if (conversation != null) {
      for (String turn : conversation) {
        prompt.append(turn).append("\n");
      }
    }
    prompt.append(message);
    return chat(prompt.toString(), false);
  }

  @Override
  public void setupURI(String uri) {
    this.serverUrl = uri;
  }

  @Override
  public void setupModel(String model) {
    this.model = model;
  }

  /**
   * Packages the content of multiple files into a single string, along with an
   * initial message.
   * This method processes each file in the provided array, reading its content
   * and appending it
   * to the initial message. The method skips directories and handles any
   * IOException that might occur
   * when reading files.
   * 
   * The resulting string is then processed to:
   * - Remove double quotes
   * - Remove single quotes
   * - Replace newlines with spaces
   * - Replace backslashes with spaces
   * 
   * @param message The initial message to prepend to the file contents
   * @param files   An array of File objects whose contents will be read and
   *                packaged
   * @return A single string containing the processed contents of all files
   */
  public String packageFilesContentInString(String message, File[] files) {
    String toSend = message;

    for (File file : files) {
      if (file.isDirectory())
        continue;
      try {
        List<String> lines = Files.readAllLines(file.toPath());
        // Join lines while preserving file boundaries.
        String content = String.join("\n", lines);
        toSend += content + "\n";
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    toSend = toSend.replace("\"", ""); // escape quotes
    toSend = toSend.replace("\'", ""); // escape single quotes
    toSend = toSend.replace("\n", " "); // remove new lines
    toSend = toSend.replace("\\", " "); // remove carriage returns
    return toSend;
  }

  @Override
  public String packageFilesContentInString(String message, File file) {
    String toSend = message;

    if (file.isDirectory())
      return toSend; // if it's a directory, return the message as is
    try {
      List<String> lines = Files.readAllLines(file.toPath());
      // Join lines while preserving file boundaries.
      String content = String.join("\n", lines);
      toSend += content + "\n";
    } catch (IOException e) {
      e.printStackTrace();
    }

    toSend = toSend.replace("\"", ""); // escape quotes
    toSend = toSend.replace("\'", ""); // escape single quotes
    toSend = toSend.replace("\n", " "); // remove new lines
    toSend = toSend.replace("\\", " "); // remove carriage returns
    return toSend;
  }

  @Override
  public String inferFormatted(String message, String system, String fieldName, boolean isList)
      throws IOException, InterruptedException {
    String result = sendGenerateRequest(buildFormattedRequest(message, system, fieldName, isList, false));
    return extractFormattedResponse(extractResponse(result), fieldName);
  }

  private String buildChatRequest(String message, boolean reasoning) {
    return """
        {
          "system": %s,
          "model": %s,
          "prompt": %s,
          "stream": false,
          "think": %s,
          "options": {
            "temperature": 0
          },
          "keep_alive": "0s"
        }"""
        .formatted(jsonString(DEFAULT_SYSTEM_PROMPT), jsonString(model), jsonString(message),
            reasoning ? "true" : "false");
  }

  private String buildFormattedRequest(String message, String system, String fieldName, boolean isList,
      boolean reasoning) {
    String systemField = system == null || system.isBlank() ? "" : "\"system\": " + jsonString(system) + ",";
    String fieldSchema = isList
        ? """
                    "type": "array",
                    "items": {
                      "type": "string"
                    }
            """
        : """
                    "type": "string"
            """;

    return """
        {
          %s
          "model": %s,
          "prompt": %s,
          "stream": false,
          "think": %s,
          "options": {
            "temperature": 0
          },
          "keep_alive": "0s",
          "format": {
            "type": "object",
            "properties": {
              %s: {
        %s
              }
            },
            "required": [%s]
          }
        }"""
        .formatted(systemField, jsonString(model), jsonString(message), reasoning ? "true" : "false",
            jsonString(fieldName), fieldSchema, jsonString(fieldName));
  }

  private String sendWithReasoningFallback(String reasoningRequest, String standardRequest, String operation)
      throws IOException, InterruptedException {
    try {
      return sendGenerateRequest(reasoningRequest);

    } catch (IOException e) {
      logger.warn("{} failed with reasoning enabled; retrying without reasoning. Cause: {}", operation,
          e.getMessage());
      return sendGenerateRequest(standardRequest);
    }
  }

  private String sendGenerateRequest(String json) throws IOException, InterruptedException {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(requestTimeout)
        .build();

    HttpRequest request = HttpRequest.newBuilder()
        .uri(java.net.URI.create(serverUrl + "/api/generate"))
        .timeout(requestTimeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("Ollama request failed with HTTP " + response.statusCode() + ": " + response.body());
    }
    return response.body();
  }

  private String extractResponse(String result) {
    JsonElement element = gson.fromJson(result, JsonElement.class);
    return element.getAsJsonObject().get("response").getAsString();
  }

  private String extractFormattedResponse(String response, String fieldName) {
    try {
      JsonElement parsed = gson.fromJson(response, JsonElement.class);
      if (parsed.isJsonObject() && parsed.getAsJsonObject().has(fieldName)) {
        return stringifyFormattedValue(parsed.getAsJsonObject().get(fieldName));
      }
      return parsed.getAsString();

    } catch (Exception e) {
      String[] chunks = response.split(fieldName, 2);
      if (chunks.length < 2) {
        return response;
      }

      String formatted = chunks[1];
      formatted = formatted.replaceAll("[{}\":\\[\\]]", "").trim();
      return formatted;
    }
  }

  private String stringifyFormattedValue(JsonElement value) {
    if (value == null || value.isJsonNull()) {
      return "";
    }
    if (value.isJsonArray()) {
      List<String> values = new ArrayList<>();
      value.getAsJsonArray().forEach(element -> values.add(element.getAsString()));
      return String.join(",", values);
    }
    if (value.isJsonPrimitive()) {
      return value.getAsString();
    }
    return value.toString();
  }

  private String jsonString(String value) {
    return gson.toJson(value == null ? "" : value);
  }

  private static long resolveTimeoutSeconds() {
    String configuredTimeout = System.getenv("AIGEN_LLM_TIMEOUT_SECONDS");
    if (configuredTimeout == null || configuredTimeout.isBlank()) {
      return DEFAULT_TIMEOUT_SECONDS;
    }
    try {
      long parsedTimeout = Long.parseLong(configuredTimeout);
      return parsedTimeout > 0 ? parsedTimeout : DEFAULT_TIMEOUT_SECONDS;
    } catch (NumberFormatException e) {
      return DEFAULT_TIMEOUT_SECONDS;
    }
  }

}
