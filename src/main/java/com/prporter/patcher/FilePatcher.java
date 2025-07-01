package com.prporter.patcher;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import com.prporter.analyzer.PRAnalyzer;
import okhttp3.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class FilePatcher {
    private final Git git;
    private final Repository repository;
    private final PRAnalyzer prAnalyzer;

    @Value("${openai.connect-timeout-seconds:30}")
    private int connectTimeoutSeconds;
    @Value("${openai.write-timeout-seconds:30}")
    private int writeTimeoutSeconds;
    @Value("${openai.read-timeout-seconds:120}")
    private int readTimeoutSeconds;

    @Autowired
    public FilePatcher(Git git, PRAnalyzer prAnalyzer) {
        this.git = git;
        this.repository = git.getRepository();
        this.prAnalyzer = prAnalyzer;
    }

    public void applyChanges(com.prporter.model.ChangedFile file, String targetBranch, String prNumber, String sourceBranch) throws IOException, GitAPIException {
        Path filePath = git.getRepository().getWorkTree().toPath().resolve(file.getPath());
        List<String> currentLines = Files.readAllLines(filePath);

        List<String> portedHunks = new ArrayList<>();
        List<String> failedHunks = new ArrayList<>();

        for (com.prporter.model.ChangedFile.DiffHunk hunk : file.getDiffHunks()) {
            try {
                System.out.println("Applying diff hunk: lines " + hunk.getStartLine() + "-" + hunk.getEndLine());
                boolean success = applyDiffHunkIntelligently(currentLines, hunk, file.getPath(), sourceBranch, file);
                if (success) {
                    portedHunks.add("lines " + hunk.getStartLine() + "-" + hunk.getEndLine());
                } else {
                    failedHunks.add("lines " + hunk.getStartLine() + "-" + hunk.getEndLine());
                    file.setStatus(com.prporter.model.FileStatus.PARTIALLY_PORTED);
                    file.setReason("AI could not help for hunk(s): " + String.join(", ", failedHunks) + ". Manual review required.");
                    file.setAiSuggestion(String.join("\n", currentLines));
                    return;
                }
            } catch (Exception e) {
                System.out.println("Failed to apply diff hunk: " + e.getMessage());
                failedHunks.add("lines " + hunk.getStartLine() + "-" + hunk.getEndLine());
                file.setStatus(com.prporter.model.FileStatus.PARTIALLY_PORTED);
                file.setReason("Exception during patching: " + e.getMessage() + ". Manual review required.");
                file.setAiSuggestion(String.join("\n", currentLines));
                return;
            }
        }
        Files.write(filePath, currentLines);

        // Syntax check using javac
        try {
            System.out.println("[PATCHER] Compiling file: " + filePath.toString());
            System.out.println("[PATCHER] File exists: " + Files.exists(filePath));
            System.out.println("[PATCHER] Working directory: " + System.getProperty("user.dir"));
            ProcessBuilder pb = new ProcessBuilder(
                "javac",
                "-cp", "target/classes",
                "-d", "target/classes",
                "-sourcepath", "src/main/java",
                filePath.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder errorOutput = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    errorOutput.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                file.setStatus(com.prporter.model.FileStatus.PARTIALLY_PORTED);
                file.setReason("File does not compile after patch. Manual review mandatory.\nCompiler output:\n" + errorOutput.toString());
                file.setAiSuggestion(String.join("\n", currentLines));
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            file.setStatus(com.prporter.model.FileStatus.PARTIALLY_PORTED);
            file.setReason("Syntax check interrupted. Manual review mandatory.");
            file.setAiSuggestion(String.join("\n", currentLines));
            return;
        }

        git.add().addFilepattern(file.getPath()).call();
        StringBuilder commitMessage = new StringBuilder();
        commitMessage.append("Port changes from PR #").append(prNumber).append("\n\n");
        if (!portedHunks.isEmpty()) {
            commitMessage.append("Successfully ported hunks:\n");
            for (String h : portedHunks) commitMessage.append("- ").append(h).append("\n");
        }
        if (!failedHunks.isEmpty()) {
            commitMessage.append("\nFailed to port hunks (manual review needed):\n");
            for (String h : failedHunks) commitMessage.append("- ").append(h).append("\n");
        }
        git.commit().setMessage(commitMessage.toString()).call();
        if (failedHunks.isEmpty()) {
            file.setStatus(com.prporter.model.FileStatus.PORTED);
        } else if (!portedHunks.isEmpty()) {
            file.setStatus(com.prporter.model.FileStatus.PARTIALLY_PORTED);
            file.setReason("Partially ported: " + String.join(", ", failedHunks) + " need manual review");
        } else {
            file.setStatus(com.prporter.model.FileStatus.SKIPPED);
            file.setReason("Failed to port any hunks: " + String.join(", ", failedHunks));
        }
    }

    // Apply a diff hunk at the file level using context lines. If context does not match, call AI. If AI cannot help, flag for manual review.
    private boolean applyDiffHunkIntelligently(List<String> currentLines, com.prporter.model.ChangedFile.DiffHunk hunk, String filePath, String sourceBranch, com.prporter.model.ChangedFile file) {
        // Always use AI for patching
        System.out.println("[AI PATCH] Using AI to apply hunk at lines " + hunk.getStartLine() + "-" + hunk.getEndLine() + ".");
        String disableAiPatching = System.getenv("DISABLE_AI_PATCHING");
        if ("true".equalsIgnoreCase(disableAiPatching) || "1".equals(disableAiPatching)) {
            System.out.println("[AI PATCH] AI patching is disabled via DISABLE_AI_PATCHING environment variable. Flagging for manual review.");
            return false;
        }
        String aiPrompt = "You are a code migration assistant.\n"
                + "Understand the intent of the change in the diff hunk and try to do the same in the target file.\n"
                + "Apply the following diff hunk to the target file, adapting the change to fit the file's current structure.\n"
                + "Return ONLY the complete, modified file content. Do not include explanations, comments, or markdown code fences.\n"
                + "If you cannot safely apply the change, reply with: MANUAL REVIEW NEEDED\n\n"
                + "---\n" + "Diff Hunk:\n" + hunk.getContent() + "\n\n"
                + "Current Target File Content:\n" + String.join("\n", currentLines) + "\n";
        String aiResult = callOpenAIApi(aiPrompt);
        if (aiResult != null && !aiResult.trim().equalsIgnoreCase("MANUAL REVIEW NEEDED")) {
            currentLines.clear();
            for (String l : aiResult.split("\n")) currentLines.add(l);
            System.out.println("[AI PATCH] AI successfully patched the hunk at lines " + hunk.getStartLine() + "-" + hunk.getEndLine() + ".");
            return true;
        } else {
            System.out.println("[AI PATCH] AI could not help. Flagging for manual review.");
            file.setAiSuggestion(aiResult); // Save the AI's suggestion for the report
            return false;
        }
    }

    // Call OpenAI API with the prompt and return the response
    private String callOpenAIApi(String prompt) {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.out.println("[AI PATCH] No OpenAI API key found in environment variable OPENAI_API_KEY.");
            return null;
        }
        String endpoint = "https://api.openai.com/v1/chat/completions";
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        MediaType mediaType = MediaType.parse("application/json");
        Gson gson = new Gson();
        JsonObject body = new JsonObject();
        body.addProperty("model", "gpt-3.5-turbo");
        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", prompt);
        messages.add(userMsg);
        body.add("messages", messages);
        body.addProperty("max_tokens", 2048);
        String requestBody = gson.toJson(body);
        Request request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(mediaType, requestBody))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();
        
        // Improved rate limiting with exponential backoff
        int maxRetries = 5;
        int baseDelayMs = 1000; // Start with 1 second
        
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try (Response response = client.newCall(request).execute()) {
                if (response.code() == 429) {
                    // Calculate exponential backoff delay
                    int delayMs = baseDelayMs * (int) Math.pow(2, attempt);
                    System.out.println("[AI PATCH] OpenAI API rate limit exceeded (429). Waiting " + (delayMs/1000) + " seconds before retry " + (attempt + 1) + "/" + maxRetries + "...");
                    
                    // Check for Retry-After header
                    String retryAfter = response.header("Retry-After");
                    if (retryAfter != null) {
                        try {
                            int retryAfterSeconds = Integer.parseInt(retryAfter);
                            delayMs = retryAfterSeconds * 1000;
                            System.out.println("[AI PATCH] Using server-suggested retry delay: " + retryAfterSeconds + " seconds");
                        } catch (NumberFormatException e) {
                            // Use exponential backoff if Retry-After is invalid
                        }
                    }
                    
                    Thread.sleep(delayMs);
                    continue;
                }
                
                if (!response.isSuccessful()) {
                    System.out.println("[AI PATCH] OpenAI API call failed with status " + response.code() + ": " + response.message());
                    if (response.code() >= 500) {
                        // Server error, retry with exponential backoff
                        int delayMs = baseDelayMs * (int) Math.pow(2, attempt);
                        System.out.println("[AI PATCH] Server error detected. Waiting " + (delayMs/1000) + " seconds before retry " + (attempt + 1) + "/" + maxRetries + "...");
                        Thread.sleep(delayMs);
                        continue;
                    }
                    return null;
                }
                
                String responseBody = response.body().string();
                JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
                JsonArray choices = jsonResponse.getAsJsonArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    JsonObject message = choice.getAsJsonObject("message");
                    if (message != null && message.has("content")) {
                        String content = message.get("content").getAsString();
                        // The new prompt asks for no markdown, but let's be safe.
                        content = content.replaceAll("^```[a-zA-Z]*\\n|```$", "").trim();
                        return content;
                    }
                }
                System.out.println("[AI PATCH] Could not parse content from OpenAI response: " + responseBody);
                return null;
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("[AI PATCH] API call interrupted");
                return null;
            } catch (Exception e) {
                System.out.println("[AI PATCH] Exception calling OpenAI API: " + e);
                e.printStackTrace();
                if (attempt < maxRetries - 1) {
                    // Retry on network errors with exponential backoff
                    int delayMs = baseDelayMs * (int) Math.pow(2, attempt);
                    try {
                        System.out.println("[AI PATCH] Network error detected. Waiting " + (delayMs/1000) + " seconds before retry " + (attempt + 1) + "/" + maxRetries + "...");
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }
                return null;
            }
        }
        
        System.out.println("[AI PATCH] OpenAI API call failed after " + maxRetries + " retries due to rate limiting or persistent errors.");
        return null;
    }
} 