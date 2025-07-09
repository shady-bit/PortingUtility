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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.stream.Collectors;

@Service
public class FilePatcher {
    private static final Logger logger = LoggerFactory.getLogger(FilePatcher.class);
    private final Git git;
    private final Repository repository;
    private final PRAnalyzer prAnalyzer;
    private static final String BASE_TMP_DIR = "base-merge-tmp";

    @Value("${openai.connect-timeout-seconds:30}")
    private int connectTimeoutSeconds;
    @Value("${openai.write-timeout-seconds:30}")
    private int writeTimeoutSeconds;
    @Value("${openai.read-timeout-seconds:120}")
    private int readTimeoutSeconds;
    @Value("${patcher.strict-mode:false}")
    private boolean strictMode;
    @Value("${patcher.auto-commit:true}")
    private boolean autoCommit;

    @Autowired
    public FilePatcher(Git git, PRAnalyzer prAnalyzer) {
        this.git = git;
        this.repository = git.getRepository();
        this.prAnalyzer = prAnalyzer;
    }

    public void applyChanges(com.prporter.model.ChangedFile file, String targetBranch, String prNumber, String mergeCommitHash) throws IOException, GitAPIException {
        // Extract only the base file for this PR file from merge commit if not already done
        java.nio.file.Path baseTmpDirPath = java.nio.file.Paths.get(BASE_TMP_DIR);
        java.nio.file.Path baseFilePath = java.nio.file.Paths.get(BASE_TMP_DIR, file.getPath());
        if (!java.nio.file.Files.exists(baseFilePath)) {
            System.out.println("[PATCHER] Extracting base file from merge commit " + mergeCommitHash + " to " + baseFilePath);
            java.util.List<String> filesToExtract = java.util.Collections.singletonList(file.getPath());
            prAnalyzer.extractFilesFromCommit(mergeCommitHash, BASE_TMP_DIR, filesToExtract);
        }
        logger.info("Entering applyChanges(file={}, targetBranch={}, prNumber={}, mergeCommitHash={})", file != null ? file.getPath() : null, targetBranch, prNumber, mergeCommitHash);
        Path filePath = git.getRepository().getWorkTree().toPath().resolve(file.getPath());
        List<String> currentLines = Files.readAllLines(filePath);

        List<String> portedHunks = new ArrayList<>();
        List<String> failedHunks = new ArrayList<>();

        // Send all diff hunks at once using AI (full-file patch)
        boolean success = applyDiffHunksWithAI(currentLines, file, mergeCommitHash);
        if (success) {
        for (com.prporter.model.ChangedFile.DiffHunk hunk : file.getDiffHunks()) {
                    portedHunks.add("lines " + hunk.getStartLine() + "-" + hunk.getEndLine());
            }
                } else {
            for (com.prporter.model.ChangedFile.DiffHunk hunk : file.getDiffHunks()) {
                failedHunks.add("lines " + hunk.getStartLine() + "-" + hunk.getEndLine());
            }
        }
        Files.write(filePath, currentLines);

        if (autoCommit) {
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
        } else {
            System.out.println("[PATCHER] Auto-commit is disabled. Changes are staged but not committed.");
        }

        if (!failedHunks.isEmpty()) {
            file.setStatus(com.prporter.model.FileStatus.PARTIALLY_PORTED);
            file.setReason("Some hunks could not be ported: " + String.join(", ", failedHunks));
        } else {
            file.setStatus(com.prporter.model.FileStatus.PORTED);
            file.setReason(null);
        }
        logger.info("Exiting applyChanges for file={}", file != null ? file.getPath() : null);
    }

    // Apply all diff hunks at once using AI (full-file patch)
    private boolean applyDiffHunksWithAI(List<String> currentLines, com.prporter.model.ChangedFile file, String mergeCommitHash) {
        logger.info("Entering applyDiffHunksWithAI(filePath={}, mergeCommitHash={})", file != null ? file.getPath() : null, mergeCommitHash);
        try {
            String filePath = file.getPath();
            String targetFileContent = String.join("\n", currentLines);
            String baseFileContent = null;
            if (mergeCommitHash != null) {
                java.nio.file.Path baseFilePath = java.nio.file.Paths.get(BASE_TMP_DIR, filePath);
                if (java.nio.file.Files.exists(baseFilePath)) {
                    baseFileContent = String.join("\n", java.nio.file.Files.readAllLines(baseFilePath));
                }
            }
            StringBuilder diffHunksBuilder = new StringBuilder();
            for (com.prporter.model.ChangedFile.DiffHunk h : file.getDiffHunks()) {
                diffHunksBuilder.append(h.getContent()).append("\n");
            }
            String diffHunks = diffHunksBuilder.toString();
            String aiPrompt = "You are an expert Java code migration assistant.\n" +
                    "\nYour task is to apply the following diff hunks (generated between the base file and the PR) to the target file, making only the necessary modifications.\n" +
                    "\nInstructions:\n" +
                    "- Use the base file ONLY for context.\n" +
                    "- Apply the diff hunks to the target file.\n" +
                    "- Do NOT add, remove, or modify anything that is not part of the diff hunks.\n" +
                    "- You MUST preserve the original order of all methods, classes, and code blocks in the target file, unless the diff hunk explicitly moves them.\n" +
                    "- Do NOT move, reorder, or group methods or code blocks unless the diff hunk specifically requires it.\n" +
                    "- You MUST preserve all existing comments (including Javadoc, inline, and block comments) in the target file, unless the diff hunk explicitly modifies or removes them. Do NOT remove, rewrite, or move any comments unless the diff hunk specifically requires it.\n" +
                    "- You MUST preserve all blank lines, whitespace, and formatting outside the diff hunks. Do not remove or add any blank lines or change indentation except as required by the diff hunks.\n" +
                    "- Do NOT include any explanations, summaries, or markdown in your response.\n" +
                    "- Do NOT include code fences (like ```java).\n" +
                    "- Return ONLY the complete, modified Java file content, and nothing else.\n" +
                    "\n---\n" +
                    "Target file content (from target branch):\n" + targetFileContent + "\n\n" +
                    "Base file content (for context only):\n" + (baseFileContent != null ? baseFileContent : "") + "\n\n" +
                    "Diff hunks to apply:\n" + diffHunks + "\n";
            String aiResult = callOpenAIApiGpt35Turbo(aiPrompt);
            if (aiResult != null) {
                List<String> filteredLines = new ArrayList<>();
                for (String l : aiResult.split("\n")) {
                    String trimmed = l.trim();
                    if (trimmed.startsWith("```")) continue;
                    filteredLines.add(l);
                }
                currentLines.clear();
                currentLines.addAll(filteredLines);
                System.out.println("[AI PATCH] Full-file AI patching succeeded (gpt-3.5-turbo).");
                return true;
            } else {
                System.out.println("[AI PATCH] Full-file AI patching failed. No output from AI.");
                return false;
            }
        } catch (Exception e) {
            logger.error("Exception during full-file AI patching: {}", file != null ? file.getPath() : null, e);
            return false;
        } finally {
            logger.info("Exiting applyDiffHunksWithAI(filePath={}, mergeCommitHash={})", file != null ? file.getPath() : null, mergeCommitHash);
        }
    }

    // Call OpenAI API with GPT-3.5-turbo model
    private String callOpenAIApiGpt35Turbo(String prompt) {
        logger.info("Entering callOpenAIApiGpt35Turbo(promptLength={})", prompt != null ? prompt.length() : 0);
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            logger.info("Exiting callOpenAIApiGpt35Turbo with resultLength=0");
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
        body.addProperty("max_tokens", 4096);
        String requestBody = gson.toJson(body);
        Request request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(mediaType, requestBody))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();
        int maxRetries = 5;
        int baseDelayMs = 1000;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try (Response response = client.newCall(request).execute()) {
                if (response.code() == 429) {
                    int delayMs = baseDelayMs * (int) Math.pow(2, attempt);
                    System.out.println("[AI PATCH] OpenAI API rate limit exceeded (429). Waiting " + (delayMs/1000) + " seconds before retry " + (attempt + 1) + "/" + maxRetries + "...");
                    String retryAfter = response.header("Retry-After");
                    if (retryAfter != null) {
                        try {
                            int retryAfterSeconds = Integer.parseInt(retryAfter);
                            delayMs = retryAfterSeconds * 1000;
                            System.out.println("[AI PATCH] Using server-suggested retry delay: " + retryAfterSeconds + " seconds");
                        } catch (NumberFormatException e) {}
                    }
                    Thread.sleep(delayMs);
                    continue;
                }
                if (!response.isSuccessful()) {
                    logger.info("Exiting callOpenAIApiGpt35Turbo with resultLength=0");
                    System.out.println("[AI PATCH] OpenAI API call failed with status " + response.code() + ": " + response.message());
                    if (response.code() >= 500) {
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
                        content = content.replaceAll("^```[a-zA-Z]*\\n|```$", "").trim();
                        logger.info("Exiting callOpenAIApiGpt35Turbo with resultLength={}", content.length());
                        return content;
                    }
                }
                logger.info("Exiting callOpenAIApiGpt35Turbo with resultLength=0");
                System.out.println("[AI PATCH] Could not parse content from OpenAI response: " + responseBody);
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.info("Exiting callOpenAIApiGpt35Turbo with resultLength=0");
                System.out.println("[AI PATCH] API call interrupted");
                return null;
            } catch (Exception e) {
                System.out.println("[AI PATCH] Exception calling OpenAI API: " + e);
                e.printStackTrace();
                if (attempt < maxRetries - 1) {
                    int delayMs = baseDelayMs * (int) Math.pow(2, attempt);
                    try {
                        System.out.println("[AI PATCH] Network error detected. Waiting " + (delayMs/1000) + " seconds before retry " + (attempt + 1) + "/" + maxRetries + "...");
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.info("Exiting callOpenAIApiGpt35Turbo with resultLength=0");
                        return null;
                    }
                    continue;
                }
                logger.info("Exiting callOpenAIApiGpt35Turbo with resultLength=0");
                return null;
            }
        }
        logger.info("Exiting callOpenAIApiGpt35Turbo with resultLength=0");
        System.out.println("[AI PATCH] OpenAI API call failed after " + maxRetries + " retries due to rate limiting or persistent errors.");
        return null;
    }
} 