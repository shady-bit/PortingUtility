package com.prporter.cli;

import com.prporter.patcher.FilePatcher;
import com.prporter.report.ReportGenerator;
import com.prporter.analyzer.PRAnalyzer;
import com.prporter.model.ChangedFile;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FS;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Component
public class PortingUtilityRunner implements CommandLineRunner {
    private final FilePatcher filePatcher;
    private final ReportGenerator reportGenerator;
    private final PRAnalyzer prAnalyzer;

    @Autowired
    public PortingUtilityRunner(FilePatcher filePatcher, ReportGenerator reportGenerator, PRAnalyzer prAnalyzer) {
        this.filePatcher = filePatcher;
        this.reportGenerator = reportGenerator;
        this.prAnalyzer = prAnalyzer;
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length < 4) {
            System.out.println("Usage: <repoUrl> <sourceBranch> <targetBranch> <prNumber>");
            System.out.println("Example: java -jar app.jar https://github.com/username/repo.git main develop 123");
            return;
        }
        String repoUrl = args[0];
        String sourceBranch = args[1];
        String targetBranch = args[2];
        String prNumber = args[3];
        System.out.println("[CLI] Starting patching for repo: " + repoUrl + ", source: " + sourceBranch + ", target: " + targetBranch + ", PR: " + prNumber);
        try {
            // Clone or open repo
            String repoDir = "pr-porter-repo";
            Git git;
            File repoFolder = new File(repoDir);
            if (repoFolder.exists()) {
                git = Git.open(repoFolder);
            } else {
                git = Git.cloneRepository().setURI(repoUrl).setDirectory(repoFolder).call();
            }
            CredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider("", ""); // TODO: Use real credentials if needed
            // Analyze PR
            List<ChangedFile> changedFiles = prAnalyzer.analyzePR(sourceBranch, targetBranch, prNumber);
            // Patch files
            for (ChangedFile file : changedFiles) {
                filePatcher.applyChanges(file, targetBranch, prNumber, sourceBranch);
            }
            // Generate report
            String reportPath = reportGenerator.generateReport(changedFiles, prNumber);
            System.out.println("[CLI] Report generated at: " + reportPath);
        } catch (GitAPIException | IOException e) {
            System.err.println("[CLI] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
} 