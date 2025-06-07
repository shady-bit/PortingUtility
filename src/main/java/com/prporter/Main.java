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

        try {
            // Create temporary directory for repository
            Path tempDir = Files.createTempDirectory("pr-porter-");
            File repoDir = tempDir.toFile();

            // Configure Git credentials
            CredentialsProvider credentialsProvider = null;
            if (repoUrl.startsWith("https://")) {
                // Use Git's credential helper for HTTPS URLs
                System.out.println("Using Git credential helper for authentication...");
                // Create an empty credentials provider to trigger Git's credential helper
                credentialsProvider = new UsernamePasswordCredentialsProvider("", "");
            } else if (repoUrl.startsWith("git@")) {
                // For SSH URLs, use SSH key
                System.out.println("Using SSH authentication...");
                // SSH authentication will use default SSH configuration
            }

            // Clone repository
            System.out.println("Cloning repository...");
            Git git = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(repoDir)
                    .setCredentialsProvider(credentialsProvider)
                    .call();

            // Initialize components
            PRAnalyzer prAnalyzer = new PRAnalyzer(git);
            ConflictChecker conflictChecker = new ConflictChecker(git);
            FilePatcher filePatcher = new FilePatcher(git);
            ReportGenerator reportGenerator = new ReportGenerator();

            // Analyze PR changes
            System.out.println("Analyzing PR changes...");
            List<ChangedFile> changedFiles = prAnalyzer.analyzePR(sourceBranch, targetBranch, prNumber);

            // Process each changed file
            for (ChangedFile file : changedFiles) {
                System.out.println("Processing file: " + file.getPath());
                
                if (conflictChecker.hasConflict(file, targetBranch)) {
                    file.setStatus(FileStatus.SKIPPED);
                    file.setReason("Conflict detected in target branch");
                } else {
                    try {
                        filePatcher.applyChanges(file, targetBranch, prNumber);
                        file.setStatus(FileStatus.PORTED);
                    } catch (Exception e) {
                        file.setStatus(FileStatus.SKIPPED);
                        file.setReason("Error applying changes: " + e.getMessage());
                    }
                }
            }

            // Generate report
            System.out.println("Generating report...");
            String reportPath = reportGenerator.generateReport(changedFiles, prNumber);
            System.out.println("Report generated at: " + reportPath);

            // Cleanup
            git.close();
            deleteDirectory(repoDir);

        } catch (GitAPIException | IOException e) {
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