package com.prporter.patcher;

import com.prporter.model.ChangedFile.MethodChange;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import com.prporter.analyzer.PRAnalyzer;
import okhttp3.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FilePatcher {
    private final Git git;
    private final Repository repository;
    private final PRAnalyzer prAnalyzer;

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
                boolean success = applyDiffHunkIntelligently(currentLines, hunk, file.getPath(), sourceBranch);
                if (success) {
                    portedHunks.add("lines " + hunk.getStartLine() + "-" + hunk.getEndLine());
                } else {
                    failedHunks.add("lines " + hunk.getStartLine() + "-" + hunk.getEndLine());
                }
            } catch (Exception e) {
                System.out.println("Failed to apply diff hunk: " + e.getMessage());
                failedHunks.add("lines " + hunk.getStartLine() + "-" + hunk.getEndLine());
            }
        }
        Files.write(filePath, currentLines);
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
    private boolean applyDiffHunkIntelligently(List<String> currentLines, com.prporter.model.ChangedFile.DiffHunk hunk, String filePath, String sourceBranch) {
        String[] hunkLines = hunk.getContent().split("\n");
        
        List<String> linesToRemove = new ArrayList<>();
        List<String> linesToAdd = new ArrayList<>();
        
        // First line is hunk header, skip it.
        // We only care about additions and removals for patching. 
        // The original context is implicit in the lines to remove.
        for (int i = 1; i < hunkLines.length; i++) {
            String line = hunkLines[i];
            if (line.startsWith("-")) {
                linesToRemove.add(line.substring(1));
            } else if (line.startsWith("+")) {
                linesToAdd.add(line.substring(1));
            } else { // Context line
                String contextLine = line.substring(1);
                linesToRemove.add(contextLine);
                linesToAdd.add(contextLine);
            }
        }
        
        // Find the starting index of the sublist to be replaced.
        int startIndex = -1;
        if (!linesToRemove.isEmpty()) {
            for (int i = 0; i <= currentLines.size() - linesToRemove.size(); i++) {
                if (currentLines.subList(i, i + linesToRemove.size()).equals(linesToRemove)) {
                    startIndex = i;
                    break;
                }
            }
        } else if (!linesToAdd.isEmpty()) {
            // This case handles pure additions. We need a better way to find the position.
            // For now, we will rely on the hunk's start line. This is not ideal.
             int hunkStart = hunk.getStartLine() -1;
             if(hunkStart >= 0 && hunkStart <= currentLines.size()){
                startIndex = hunkStart;
             }
        }


        if (startIndex != -1) {
            // Found a match, apply the patch
            List<String> tempLines = new ArrayList<>(currentLines);
            
            // Remove the old lines
            if (!linesToRemove.isEmpty()) {
                tempLines.subList(startIndex, startIndex + linesToRemove.size()).clear();
            }
            
            // Add the new lines
            tempLines.addAll(startIndex, linesToAdd);

            currentLines.clear();
            currentLines.addAll(tempLines);
            return true;
        } else {
            // Context does not match, call AI for intent-preserving patching
            System.out.println("[AI PATCH] Context does not match for hunk at lines " + hunk.getStartLine() + "-" + hunk.getEndLine() + ". Calling AI for help.");
            
            // Check if AI patching is disabled
            String disableAiPatching = System.getenv("DISABLE_AI_PATCHING");
            if ("true".equalsIgnoreCase(disableAiPatching) || "1".equals(disableAiPatching)) {
                System.out.println("[AI PATCH] AI patching is disabled via DISABLE_AI_PATCHING environment variable. Flagging for manual review.");
                return false;
            }
            
            String aiPrompt =
                "You are a code migration assistant.\n\n" +
                "Here is a diff hunk from a PR that could not be applied cleanly to the target file.\n" +
                "Please apply the intent of the change to the target file, adapting as needed.\n" +
                "If you cannot do this safely, reply: MANUAL REVIEW NEEDED.\n" +
                "\nDiff hunk:\n```diff\n" + hunk.getContent() + "\n```\n" +
                "\nCurrent target file content:\n```java\n" + String.join("\n", currentLines) + "\n```\n";
            String aiResult = callOpenAIApi(aiPrompt);
            if (aiResult != null && !aiResult.trim().equalsIgnoreCase("MANUAL REVIEW NEEDED")) {
                // Replace the file content with the AI's suggestion
                currentLines.clear();
                for (String l : aiResult.split("\n")) currentLines.add(l);
                return true;
            } else {
                System.out.println("[AI PATCH] AI could not help. Flagging for manual review.");
                return false;
            }
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
        OkHttpClient client = new OkHttpClient();
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
                int contentIndex = responseBody.indexOf("\"content\":");
                if (contentIndex != -1) {
                    int start = responseBody.indexOf('"', contentIndex + 11) + 1;
                    int end = responseBody.indexOf('"', start);
                    if (start != -1 && end != -1 && end > start) {
                        String content = responseBody.substring(start, end);
                        content = content.replaceAll("^```[a-zA-Z]*\\n|```$", "").trim();
                        return content;
                    }
                }
                return null;
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("[AI PATCH] API call interrupted");
                return null;
            } catch (Exception e) {
                System.out.println("[AI PATCH] Exception calling OpenAI API: " + e.getMessage());
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