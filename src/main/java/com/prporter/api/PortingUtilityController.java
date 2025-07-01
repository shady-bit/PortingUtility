package com.prporter.api;

import com.prporter.patcher.FilePatcher;
import com.prporter.report.ReportGenerator;
import com.prporter.analyzer.PRAnalyzer;
import com.prporter.model.ChangedFile;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/porting")
public class PortingUtilityController {
    private final FilePatcher filePatcher;
    private final ReportGenerator reportGenerator;
    private final PRAnalyzer prAnalyzer;

    @Autowired
    public PortingUtilityController(FilePatcher filePatcher, ReportGenerator reportGenerator, PRAnalyzer prAnalyzer) {
        this.filePatcher = filePatcher;
        this.reportGenerator = reportGenerator;
        this.prAnalyzer = prAnalyzer;
    }

    @GetMapping("/health")
    public String health() {
        return "Porting Utility API is running.";
    }

    @PostMapping("/patch")
    public String patch(@RequestBody PatchRequest request) {
        System.out.println("[API] Received patch request: " + request);
        try {
            // Clone or open repo
            String repoDir = "pr-porter-repo";
            Git git;
            File repoFolder = new File(repoDir);
            if (repoFolder.exists()) {
                git = Git.open(repoFolder);
            } else {
                git = Git.cloneRepository().setURI(request.repoUrl).setDirectory(repoFolder).call();
            }
            CredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider("", ""); // TODO: Use real credentials if needed
            // Analyze PR
            List<ChangedFile> changedFiles = prAnalyzer.analyzePR(request.sourceBranch, request.targetBranch, request.prNumber);
            // Patch files
            for (ChangedFile file : changedFiles) {
                filePatcher.applyChanges(file, request.targetBranch, request.prNumber, request.sourceBranch);
            }
            // Generate report
            String reportPath = reportGenerator.generateReport(changedFiles, request.prNumber);
            return "Report generated at: " + reportPath;
        } catch (GitAPIException | IOException e) {
            e.printStackTrace();
            return "Error: " + e.getMessage();
        }
    }

    // DTO for request body
    public static class PatchRequest {
        public String repoUrl;
        public String sourceBranch;
        public String targetBranch;
        public String prNumber;

        @Override
        public String toString() {
            return "PatchRequest{" +
                    "repoUrl='" + repoUrl + '\'' +
                    ", sourceBranch='" + sourceBranch + '\'' +
                    ", targetBranch='" + targetBranch + '\'' +
                    ", prNumber='" + prNumber + '\'' +
                    '}';
        }
    }

    // TODO: Add endpoints for patching and report generation
} 