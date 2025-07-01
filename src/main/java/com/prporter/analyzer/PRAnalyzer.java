package com.prporter.analyzer;

import com.prporter.model.ChangedFile;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.transport.CredentialsProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RepositoryState;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

@Service
public class PRAnalyzer {
    private final Git git;
    private final Repository repository;
    private final CredentialsProvider credentialsProvider;
    private static final String SAFETY_MESSAGE = "\n⚠️  SAFETY NOTICE: This utility is READ-ONLY until you explicitly push changes.\n" +
                                               "   No remote branches will be modified or deleted.\n" +
                                               "   All changes are local until you choose to push them.\n";

    public PRAnalyzer(Git git, CredentialsProvider credentialsProvider) {
        this.git = git;
        this.repository = git.getRepository();
        this.credentialsProvider = credentialsProvider;
        System.out.println(SAFETY_MESSAGE);
    }

    public List<ChangedFile> analyzePR(String sourceBranch, String targetBranch, String prNumber) throws GitAPIException, IOException {
        List<ChangedFile> changedFiles = new ArrayList<>();

        // Verify we're in a clean state
        System.out.println("\n🔍 Verifying repository state...");
        if (!repository.getRepositoryState().equals(RepositoryState.SAFE)) {
            System.out.println("⚠️  Warning: Repository is not in a clean state");
            System.out.println("   Current state: " + repository.getRepositoryState());
            System.out.println("   This is safe - we're only reading information");
        }

        // Fetch all remote branches (read-only operation)
        System.out.println("\n📥 Fetching remote branches (read-only operation)...");
        try {
            git.fetch()
               .setCredentialsProvider(credentialsProvider)
               .setForceUpdate(true)
               .setRefSpecs("+refs/heads/*:refs/remotes/origin/*")
               .call();
            System.out.println("✅ Remote branches fetched successfully");
            System.out.println("   Note: This only updates local references to remote branches");
        } catch (GitAPIException e) {
            System.err.println("❌ Error fetching remote branches: " + e.getMessage());
            throw new JGitInternalException("Failed to fetch remote branches. Please check your credentials and network connection.", e);
        }

        // Get the commit IDs for both branches
        System.out.println("\n🔍 Resolving branch references (read-only operation)...");
        System.out.println("   Source branch: " + sourceBranch);
        System.out.println("   Target branch: " + targetBranch);
        
        // List all available references for debugging
        System.out.println("\n📋 Available references (read-only operation):");
        try {
            for (Ref ref : git.getRepository().getRefDatabase().getRefs()) {
                System.out.println("   " + ref.getName());
            }
        } catch (IOException e) {
            System.err.println("❌ Error listing references: " + e.getMessage());
        }
        System.out.println();
        
        // Try different reference formats
        ObjectId sourceId = null;
        ObjectId targetId = null;
        
        System.out.println("🔄 Attempting to resolve branches (read-only operation)...");
        
        // Try with refs/remotes/origin/ prefix
        System.out.println("Trying refs/remotes/origin/ prefix...");
        sourceId = repository.resolve("refs/remotes/origin/" + sourceBranch);
        targetId = repository.resolve("refs/remotes/origin/" + targetBranch);
        
        // If not found, try without prefix
        if (sourceId == null) {
            System.out.println("Trying direct branch name for source...");
            sourceId = repository.resolve(sourceBranch);
        }
        if (targetId == null) {
            System.out.println("Trying direct branch name for target...");
            targetId = repository.resolve(targetBranch);
        }
        
        // If still not found, try with origin/ prefix
        if (sourceId == null) {
            System.out.println("Trying origin/ prefix for source...");
            sourceId = repository.resolve("origin/" + sourceBranch);
        }
        if (targetId == null) {
            System.out.println("Trying origin/ prefix for target...");
            targetId = repository.resolve("origin/" + targetBranch);
        }
        
        // If still not found, try with refs/heads/ prefix
        if (sourceId == null) {
            System.out.println("Trying refs/heads/ prefix for source...");
            sourceId = repository.resolve("refs/heads/" + sourceBranch);
        }
        if (targetId == null) {
            System.out.println("Trying refs/heads/ prefix for target...");
            targetId = repository.resolve("refs/heads/" + targetBranch);
        }

        if (sourceId == null) {
            throw new JGitInternalException("Could not resolve source branch: " + sourceBranch + 
                "\nTried: refs/remotes/origin/" + sourceBranch + 
                ", " + sourceBranch + 
                ", origin/" + sourceBranch +
                ", refs/heads/" + sourceBranch +
                "\nPlease verify the branch exists and has been fetched.");
        }
        if (targetId == null) {
            throw new JGitInternalException("Could not resolve target branch: " + targetBranch + 
                "\nTried: refs/remotes/origin/" + targetBranch + 
                ", " + targetBranch + 
                ", origin/" + targetBranch +
                ", refs/heads/" + targetBranch +
                "\nPlease verify the branch exists and has been fetched.");
        }

        System.out.println("Source commit: " + sourceId.getName());
        System.out.println("Target commit: " + targetId.getName());

        try (ObjectReader reader = repository.newObjectReader()) {
            // Get the merge commit for PR #123
            System.out.println("Finding merge commit for PR #" + prNumber + "...");
            Iterable<RevCommit> mergeCommits = git.log()
                .add(sourceId)
                .call();

            RevCommit prMergeCommit = null;
            for (RevCommit commit : mergeCommits) {
                String message = commit.getFullMessage();
                if (message.contains("Merge pull request #" + prNumber) || 
                    message.contains("Merged PR #" + prNumber)) {
                    prMergeCommit = commit;
                    break;
                }
            }

            if (prMergeCommit == null) {
                throw new JGitInternalException("Could not find merge commit for PR #" + prNumber);
            }

            System.out.println("Found merge commit: " + prMergeCommit.getName());
            System.out.println("Merge commit message: " + prMergeCommit.getFullMessage());

            // Get the changes in the merge commit itself
            System.out.println("Getting changes from merge commit...");
            
            // Get the tree iterators for the merge commit and its first parent (base branch)
            CanonicalTreeParser baseTree = new CanonicalTreeParser();
            baseTree.reset(reader, prMergeCommit.getParent(0).getTree().getId());
            CanonicalTreeParser mergeTree = new CanonicalTreeParser();
            mergeTree.reset(reader, prMergeCommit.getTree().getId());

            // Get the list of changes in the merge commit
            List<DiffEntry> diffs = git.diff()
                    .setOldTree(baseTree)
                    .setNewTree(mergeTree)
                    .call();

            System.out.println("Found " + diffs.size() + " files changed in merge commit");

            // Process each changed file
            for (DiffEntry diff : diffs) {
                String filePath = diff.getChangeType() == DiffEntry.ChangeType.DELETE ? 
                    diff.getOldPath() : diff.getNewPath();
                
                System.out.println("\nProcessing file: " + filePath);
                System.out.println("Change type: " + diff.getChangeType());
                
                ChangedFile changedFile = new ChangedFile(filePath);
                if (diff.getChangeType() == DiffEntry.ChangeType.MODIFY || 
                    diff.getChangeType() == DiffEntry.ChangeType.ADD) {
                    List<ChangedFile.DiffHunk> diffHunks = extractDiffHunks(diff, prMergeCommit.getParent(0), prMergeCommit);
                    if (!diffHunks.isEmpty()) {
                        changedFile.setDiffHunks(diffHunks);
                        System.out.println("Found " + diffHunks.size() + " diff hunks");
                    }
                }
                changedFiles.add(changedFile);
            }

            System.out.println("\nTotal files changed in merge commit: " + changedFiles.size());
            
            if (changedFiles.isEmpty()) {
                System.out.println("WARNING: No files were detected in the merge commit");
                System.out.println("Please verify:");
                System.out.println("1. PR #" + prNumber + " exists and has been merged");
                System.out.println("2. The merge commit message contains 'Merge pull request #" + prNumber + "'");
                System.out.println("3. The source branch is correct");
            }
        }

        return changedFiles;
    }

    private List<ChangedFile.DiffHunk> extractDiffHunks(DiffEntry diff, RevCommit oldCommit, RevCommit newCommit) throws IOException {
        List<ChangedFile.DiffHunk> diffHunks = new ArrayList<>();
        
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter diffFormatter = new DiffFormatter(out)) {
            diffFormatter.setRepository(repository);
            diffFormatter.setContext(0); // Get hunks without any context lines initially, we add them back.
            
            // Get the raw diff content
            diffFormatter.format(diff);
            String diffContent = out.toString();
            String[] lines = diffContent.split("\n");
            
            int currentStartLine = 0;
            int currentEndLine = 0;
            StringBuilder currentContent = new StringBuilder();
            boolean inHunk = false;
            
            for (String line : lines) {
                if (line.startsWith("@@")) {
                    // Save previous hunk if exists
                    if (inHunk && currentContent.length() > 0) {
                        diffHunks.add(new ChangedFile.DiffHunk(currentStartLine, currentEndLine, currentContent.toString()));
                    }
                    
                    currentContent = new StringBuilder();
                    inHunk = true;
                    
                    // Parse the hunk header for the original file's line numbers
                    String[] parts = line.split(" ");
                    if (parts.length > 1) {
                        String oldRange = parts[1]; // e.g., -58,7
                        String[] lineNumbers = oldRange.substring(1).split(",");
                        if (lineNumbers.length > 0) {
                            currentStartLine = Integer.parseInt(lineNumbers[0]);
                            if (lineNumbers.length > 1) {
                                currentEndLine = currentStartLine + Integer.parseInt(lineNumbers[1]) - 1;
                            } else {
                                currentEndLine = currentStartLine;
                            }
                        }
                    }
                    currentContent.append(line).append("\n");
                } else if (inHunk) {
                    // Store all lines within a hunk (context, added, removed)
                    currentContent.append(line).append("\n");
                }
            }
            
            // Add the last hunk
            if (inHunk && currentContent.length() > 0) {
                diffHunks.add(new ChangedFile.DiffHunk(currentStartLine, currentEndLine, currentContent.toString()));
            }
        }
        
        return diffHunks;
    }

    // Extract the full method code from a file in a specific branch by method name
    public String extractMethodFromBranch(String filePath, String methodName, String branch) throws IOException, GitAPIException {
        // Checkout the branch
        String currentBranch = repository.getBranch();
        if (!currentBranch.equals(branch)) {
            git.checkout().setName(branch).call();
        }
        // Read the file
        Path absPath = repository.getWorkTree().toPath().resolve(filePath);
        List<String> lines = Files.readAllLines(absPath);
        // Find method start
        int start = -1, end = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(methodName + "(") && lines.get(i).matches(".*\\b(public|private|protected|static|final)\\s+\\w+\\s+" + methodName + "\\s*\\(.*\\)\\s*\\{")) {
                start = i;
                break;
            }
        }
        if (start == -1) return null;
        // Find method end (matching braces)
        int braceCount = 0;
        for (int i = start; i < lines.size(); i++) {
            braceCount += countChar(lines.get(i), '{');
            braceCount -= countChar(lines.get(i), '}');
            if (braceCount == 0) {
                end = i;
                break;
            }
        }
        if (end == -1) return null;
        StringBuilder method = new StringBuilder();
        for (int i = start; i <= end; i++) {
            method.append(lines.get(i)).append("\n");
        }
        // Restore original branch if needed
        if (!currentBranch.equals(branch)) {
            git.checkout().setName(currentBranch).call();
        }
        return method.toString();
    }

    private int countChar(String str, char c) {
        return (int) str.chars().filter(ch -> ch == c).count();
    }
} 