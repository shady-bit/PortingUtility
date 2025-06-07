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
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.transport.URIish;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Main {
    private static final String REPO_DIR = "pr-porter-repo";
    private static Git git;

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
            File repoDir = new File(REPO_DIR);
            boolean isNewClone = !isValidGitRepository(repoDir);

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

            if (isNewClone) {
                // Clean up existing directory if it exists but is not a valid Git repo
                if (repoDir.exists()) {
                    System.out.println("Cleaning up invalid repository directory...");
                    deleteDirectory(repoDir);
                }
                
                // Clone repository if it doesn't exist
                System.out.println("Cloning repository for the first time...");
                System.out.println("Repository will be cloned to: " + repoDir.getAbsolutePath());
                git = Git.cloneRepository()
                        .setURI(repoUrl)
                        .setDirectory(repoDir)
                        .setCredentialsProvider(credentialsProvider)
                        .setTimeout(30) // 30 seconds timeout
                        .setProgressMonitor(new org.eclipse.jgit.lib.ProgressMonitor() {
                            private int totalWork = 0;
                            private int completed = 0;
                            
                            @Override
                            public void start(int totalTasks) {
                                System.out.print("Cloning: ");
                            }

                            @Override
                            public void beginTask(String title, int totalWork) {
                                this.totalWork = totalWork;
                                this.completed = 0;
                            }

                            @Override
                            public void update(int completed) {
                                this.completed += completed;
                                if (totalWork > 0) {
                                    int percent = (this.completed * 100) / totalWork;
                                    System.out.print("\rCloning: " + percent + "%");
                                }
                            }

                            @Override
                            public void endTask() {
                                System.out.println(" Done");
                            }

                            @Override
                            public boolean isCancelled() {
                                return false;
                            }
                        })
                        .call();
            } else {
                // Open existing repository
                System.out.println("Opening existing repository...");
                System.out.println("Repository location: " + repoDir.getAbsolutePath());
                git = Git.open(repoDir);
                
                // Completely reset the repository state
                System.out.println("Resetting repository to clean state...");
                resetRepository(git, credentialsProvider);
            }

            // Initialize components
            System.out.println("Initializing components...");
            PRAnalyzer prAnalyzer = new PRAnalyzer(git, credentialsProvider);
            ConflictChecker conflictChecker = new ConflictChecker(git, credentialsProvider);
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

            // Don't close git or delete directory - we want to keep it for next run
            System.out.println("\nRepository state preserved for next run");

        } catch (GitAPIException | IOException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void resetRepository(Git git, CredentialsProvider credentialsProvider) throws GitAPIException, IOException {
        // Fetch all remote changes
        System.out.println("Fetching latest changes from remote...");
        git.fetch()
           .setCredentialsProvider(credentialsProvider)
           .setForceUpdate(true)
           .setRemoveDeletedRefs(true)
           .setTagOpt(org.eclipse.jgit.transport.TagOpt.FETCH_TAGS)
           .call();

        // Reset to origin/develop
        System.out.println("Resetting to origin/develop...");
        git.reset()
           .setMode(ResetCommand.ResetType.HARD)
           .setRef("refs/remotes/origin/develop")
           .call();

        // Clean untracked files and directories
        System.out.println("Cleaning untracked files...");
        git.clean()
           .setCleanDirectories(true)
           .setForce(true)
           .setIgnore(false)
           .call();

        // Prune remote-tracking branches
        System.out.println("Pruning remote-tracking branches...");
        String remoteUrl = git.getRepository().getConfig().getString("remote", "origin", "url");
        git.remoteRemove()
           .setRemoteName("origin")
           .call();
        try {
            git.remoteAdd()
               .setName("origin")
               .setUri(new URIish(remoteUrl))
               .call();
        } catch (Exception e) {
            throw new IOException("Invalid remote URL: " + remoteUrl, e);
        }

        // Fetch again with credentials after re-adding remote
        System.out.println("Fetching latest changes after remote reconfiguration...");
        git.fetch()
           .setRemote("origin")
           .setCredentialsProvider(credentialsProvider)
           .setForceUpdate(true)
           .setRemoveDeletedRefs(true)
           .setTagOpt(org.eclipse.jgit.transport.TagOpt.FETCH_TAGS)
           .call();

        // Delete all local branches except develop
        System.out.println("Cleaning up local branches...");
        git.branchList()
           .call()
           .forEach(ref -> {
               try {
                   if (!ref.getName().equals("refs/heads/develop")) {
                       git.branchDelete()
                          .setBranchNames(ref.getName())
                          .setForce(true)
                          .call();
                   }
               } catch (GitAPIException e) {
                   System.out.println("Warning: Could not delete branch " + ref.getName() + ": " + e.getMessage());
               }
           });

        System.out.println("Repository reset complete");
    }

    private static boolean isValidGitRepository(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            return false;
        }
        
        File gitDir = new File(directory, ".git");
        if (!gitDir.exists() || !gitDir.isDirectory()) {
            return false;
        }
        
        try {
            Repository repository = new FileRepositoryBuilder()
                .setGitDir(gitDir)
                .build();
            repository.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // Add a method to clean up the repository when needed
    public static void cleanup() {
        if (git != null) {
            git.close();
        }
        File repoDir = new File(REPO_DIR);
        if (repoDir.exists()) {
            deleteDirectory(repoDir);
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