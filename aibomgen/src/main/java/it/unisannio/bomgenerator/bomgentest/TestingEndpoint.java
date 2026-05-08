// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.bomgentest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class TestingEndpoint {
    public static void main(String[] args) {
        try {
            // Create the HTTP client.
            HttpClient client = HttpClient.newHttpClient();

            // Create the HTTP POST request.
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/generate"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"model\":\"deepseek-r1\", \"prompt\":\"What is the capital of France?\", \"stream\":false, \"think\":false}"))
                    .build();

            // Send the request and read the response.
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Print the response code and body.
            System.out.println("Response Code: " + response.statusCode());
            System.out.println("Response: " + response.body());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
