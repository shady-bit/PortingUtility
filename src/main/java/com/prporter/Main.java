package com.prporter;

import com.prporter.analyzer.PRAnalyzer;
import com.prporter.checker.ConflictChecker;
import com.prporter.model.ChangedFile;
import com.prporter.model.FileStatus;
import com.prporter.patcher.FilePatcher;
import com.prporter.report.ReportGenerator;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.FS;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        if (args.length != 4) {
            System.out.println("Usage: java -jar pr-porting-utility.jar <repoUrl> <sourceBranch> <targetBranch> <prNumber>");
            System.exit(1);
        }

        String repoUrl = args[0];
        String sourceBranch = args[1];
        String targetBranch = args[2];
        String prNumber = args[3];

        System.out.println("Starting PR Porting Utility...");
        System.out.println("Repository URL: " + repoUrl);
        System.out.println("Source Branch: " + sourceBranch);
        System.out.println("Target Branch: " + targetBranch);
        System.out.println("PR Number: " + prNumber);

        try {
            // Create temporary directory for repository
            System.out.println("Creating temporary directory...");
            Path tempDir = Files.createTempDirectory("pr-porter-");
            File repoDir = tempDir.toFile();
            System.out.println("Temporary directory created at: " + tempDir.toAbsolutePath());
            System.out.println("Repository will be cloned to: " + repoDir.getAbsolutePath());

            // Configure Git credentials
            System.out.println("Configuring Git credentials...");
            CredentialsProvider credentialsProvider = null;
            if (repoUrl.startsWith("https://")) {
                // Use personal access token for HTTPS URLs
                String token = System.getenv("GITHUB_TOKEN");
                if (token == null || token.isEmpty()) {
                    System.err.println("Error: GITHUB_TOKEN environment variable is not set");
                    System.err.println("Please set your GitHub personal access token:");
                    System.err.println("export GITHUB_TOKEN=your_token_here");
                    System.exit(1);
                }
                System.out.println("Using HTTPS authentication with personal access token");
                credentialsProvider = new UsernamePasswordCredentialsProvider(token, "");
            } else if (repoUrl.startsWith("git@")) {
                // For SSH URLs, use SSH key
                System.out.println("Using SSH authentication with default SSH key");
                // SSH authentication will use default SSH configuration
            }

            // Clone repository with timeout
            System.out.println("Starting repository clone...");
            try {
                Git git = Git.cloneRepository()
                        .setURI(repoUrl)
                        .setDirectory(repoDir)
                        .setCredentialsProvider(credentialsProvider)
                        .setTimeout(30) // 30 seconds timeout
                        .setProgressMonitor(new org.eclipse.jgit.lib.ProgressMonitor() {
                            @Override
                            public void start(int totalTasks) {
                                System.out.println("Starting clone operation...");
                            }

                            @Override
                            public void beginTask(String title, int totalWork) {
                                System.out.println("Cloning: " + title);
                            }

                            @Override
                            public void update(int completed) {
                                System.out.print(".");
                            }

                            @Override
                            public void endTask() {
                                System.out.println("\nClone operation completed");
                            }

                            @Override
                            public boolean isCancelled() {
                                return false;
                            }
                        })
                        .call();

                System.out.println("Repository cloned successfully");

                // Initialize components
                System.out.println("Initializing components...");
                PRAnalyzer prAnalyzer = new PRAnalyzer(git);
                ConflictChecker conflictChecker = new ConflictChecker(git);
                FilePatcher filePatcher = new FilePatcher(git);
                ReportGenerator reportGenerator = new ReportGenerator();

                // Analyze PR changes
                System.out.println("Starting PR analysis...");
                List<ChangedFile> changedFiles = prAnalyzer.analyzePR(sourceBranch, targetBranch, prNumber);
                System.out.println("Found " + changedFiles.size() + " changed files");

                // Process each changed file
                for (ChangedFile file : changedFiles) {
                    System.out.println("\nProcessing file: " + file.getPath());
                    
                    if (conflictChecker.hasConflict(file, targetBranch)) {
                        System.out.println("Conflict detected in target branch");
                        file.setStatus(FileStatus.SKIPPED);
                        file.setReason("Conflict detected in target branch");
                    } else {
                        try {
                            System.out.println("Applying changes to target branch...");
                            filePatcher.applyChanges(file, targetBranch, prNumber);
                            file.setStatus(FileStatus.PORTED);
                            System.out.println("Changes applied successfully");
                        } catch (Exception e) {
                            System.out.println("Error applying changes: " + e.getMessage());
                            file.setStatus(FileStatus.SKIPPED);
                            file.setReason("Error applying changes: " + e.getMessage());
                        }
                    }
                }

                // Generate report
                System.out.println("\nGenerating report...");
                String reportPath = reportGenerator.generateReport(changedFiles, prNumber);
                System.out.println("Report generated at: " + reportPath);

                // Cleanup
                System.out.println("\nCleaning up...");
                git.close();
                deleteDirectory(repoDir);
                System.out.println("Cleanup completed");

            } catch (GitAPIException e) {
                System.err.println("Error during Git operations: " + e.getMessage());
                if (e.getCause() != null) {
                    System.err.println("Caused by: " + e.getCause().getMessage());
                }
                e.printStackTrace();
                System.exit(1);
            }

        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void deleteDirectory(File directory) {
        File[] allContents = directory.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        directory.delete();
    }
} 