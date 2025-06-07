package com.prporter.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class BrowserAuthenticator {
    private static final String GITHUB_AUTH_URL = "https://github.com/login/device/code";
    private static final String GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String CLIENT_ID = "YOUR_GITHUB_CLIENT_ID"; // Replace with your GitHub OAuth App client ID

    private final OkHttpClient client;

    public BrowserAuthenticator() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public CredentialsProvider authenticate() throws IOException {
        // Step 1: Get device code
        JsonObject deviceCodeResponse = getDeviceCode();
        String deviceCode = deviceCodeResponse.get("device_code").getAsString();
        String userCode = deviceCodeResponse.get("user_code").getAsString();
        String verificationUri = deviceCodeResponse.get("verification_uri").getAsString();
        int interval = deviceCodeResponse.get("interval").getAsInt();

        // Step 2: Show instructions to user
        System.out.println("\nPlease authenticate using your browser:");
        System.out.println("1. Open this URL: " + verificationUri);
        System.out.println("2. Enter this code: " + userCode);
        System.out.println("3. Authorize the application\n");

        // Step 3: Poll for token
        while (true) {
            try {
                Thread.sleep(interval * 1000L);
                JsonObject tokenResponse = getAccessToken(deviceCode);
                if (tokenResponse.has("access_token")) {
                    String accessToken = tokenResponse.get("access_token").getAsString();
                    return new UsernamePasswordCredentialsProvider(accessToken, "");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Authentication interrupted", e);
            }
        }
    }

    private JsonObject getDeviceCode() throws IOException {
        RequestBody formBody = new FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("scope", "repo")
                .build();

        Request request = new Request.Builder()
                .url(GITHUB_AUTH_URL)
                .post(formBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get device code: " + response);
            }
            return JsonParser.parseString(response.body().string()).getAsJsonObject();
        }
    }

    private JsonObject getAccessToken(String deviceCode) throws IOException {
        RequestBody formBody = new FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build();

        Request request = new Request.Builder()
                .url(GITHUB_TOKEN_URL)
                .post(formBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get access token: " + response);
            }
            return JsonParser.parseString(response.body().string()).getAsJsonObject();
        }
    }
} 