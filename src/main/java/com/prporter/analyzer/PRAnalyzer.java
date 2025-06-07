package com.prporter.analyzer;

import com.prporter.model.ChangedFile;
import com.prporter.model.FileStatus;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.transport.CredentialsProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PRAnalyzer {
    private final Git git;
    private final Repository repository;
    private final CredentialsProvider credentialsProvider;

    public PRAnalyzer(Git git, CredentialsProvider credentialsProvider) {
        this.git = git;
        this.repository = git.getRepository();
        this.credentialsProvider = credentialsProvider;
    }

    public List<ChangedFile> analyzePR(String sourceBranch, String targetBranch, String prNumber) throws GitAPIException, IOException {
        List<ChangedFile> changedFiles = new ArrayList<>();

        // Fetch all remote branches
        System.out.println("Fetching remote branches...");
        git.fetch()
           .setCredentialsProvider(credentialsProvider)
           .setForceUpdate(true)
           .call();
        System.out.println("Remote branches fetched successfully");

        // Get the commit IDs for both branches
        System.out.println("Resolving branch references...");
        System.out.println("Source branch: " + sourceBranch);
        System.out.println("Target branch: " + targetBranch);
        
        // Try different reference formats
        ObjectId sourceId = null;
        ObjectId targetId = null;
        
        // Try with refs/remotes/origin/ prefix
        sourceId = repository.resolve("refs/remotes/origin/" + sourceBranch);
        targetId = repository.resolve("refs/remotes/origin/" + targetBranch);
        
        // If not found, try without prefix
        if (sourceId == null) {
            sourceId = repository.resolve(sourceBranch);
        }
        if (targetId == null) {
            targetId = repository.resolve(targetBranch);
        }
        
        // If still not found, try with origin/ prefix
        if (sourceId == null) {
            sourceId = repository.resolve("origin/" + sourceBranch);
        }
        if (targetId == null) {
            targetId = repository.resolve("origin/" + targetBranch);
        }

        if (sourceId == null) {
            throw new JGitInternalException("Could not resolve source branch: " + sourceBranch + 
                "\nTried: refs/remotes/origin/" + sourceBranch + 
                ", " + sourceBranch + 
                ", origin/" + sourceBranch);
        }
        if (targetId == null) {
            throw new JGitInternalException("Could not resolve target branch: " + targetBranch + 
                "\nTried: refs/remotes/origin/" + targetBranch + 
                ", " + targetBranch + 
                ", origin/" + targetBranch);
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

            // Get the parent commits of the merge commit
            RevCommit[] parents = prMergeCommit.getParents();
            if (parents.length != 2) {
                throw new JGitInternalException("Expected merge commit to have 2 parents, found " + parents.length);
            }

            // The first parent is the base branch, the second is the PR branch
            RevCommit baseParent = parents[0];
            RevCommit prParent = parents[1];

            System.out.println("Base parent commit: " + baseParent.getName());
            System.out.println("PR parent commit: " + prParent.getName());

            // Get the tree iterators for the base and PR parents
            CanonicalTreeParser baseTree = new CanonicalTreeParser();
            baseTree.reset(reader, baseParent.getTree().getId());
            CanonicalTreeParser prTree = new CanonicalTreeParser();
            prTree.reset(reader, prParent.getTree().getId());

            // Get the list of changes between base and PR
            System.out.println("Calculating changes from PR #" + prNumber + "...");
            List<DiffEntry> diffs = git.diff()
                    .setOldTree(baseTree)
                    .setNewTree(prTree)
                    .call();

            System.out.println("Found " + diffs.size() + " files changed in PR #" + prNumber);

            // Process each changed file
            for (DiffEntry diff : diffs) {
                String filePath = diff.getChangeType() == DiffEntry.ChangeType.DELETE ? 
                    diff.getOldPath() : diff.getNewPath();
                
                System.out.println("\nProcessing file: " + filePath);
                System.out.println("Change type: " + diff.getChangeType());
                
                ChangedFile changedFile = new ChangedFile(filePath);
                if (diff.getChangeType() == DiffEntry.ChangeType.MODIFY || 
                    diff.getChangeType() == DiffEntry.ChangeType.ADD) {
                    List<ChangedFile.DiffHunk> diffHunks = extractDiffHunks(diff, baseParent, prParent);
                    if (!diffHunks.isEmpty()) {
                        changedFile.setDiffHunks(diffHunks);
                        System.out.println("Found " + diffHunks.size() + " diff hunks");
                    }
                }
                changedFiles.add(changedFile);
            }

            System.out.println("\nTotal files changed in PR #" + prNumber + ": " + changedFiles.size());
            
            if (changedFiles.isEmpty()) {
                System.out.println("WARNING: No files were detected in PR #" + prNumber);
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
            
            // Get the raw diff content
            diffFormatter.format(diff);
            String diffContent = out.toString();
            String[] lines = diffContent.split("\n");
            
            int currentStartLine = 0;
            int currentEndLine = 0;
            StringBuilder currentContent = new StringBuilder();
            
            for (String line : lines) {
                if (line.startsWith("@@")) {
                    // Save previous hunk if exists
                    if (currentContent.length() > 0) {
                        diffHunks.add(new ChangedFile.DiffHunk(currentStartLine, currentEndLine, currentContent.toString()));
                        currentContent = new StringBuilder();
                    }
                    
                    // Parse the hunk header
                    String[] parts = line.split(" ");
                    if (parts.length >= 2) {
                        String[] lineNumbers = parts[1].split(",");
                        if (lineNumbers.length >= 2) {
                            currentStartLine = Integer.parseInt(lineNumbers[0].substring(1));
                            currentEndLine = currentStartLine + Integer.parseInt(lineNumbers[1]) - 1;
                        }
                    }
                } else if (line.startsWith("+") || line.startsWith("-")) {
                    currentContent.append(line).append("\n");
                }
            }
            
            // Add the last hunk
            if (currentContent.length() > 0) {
                diffHunks.add(new ChangedFile.DiffHunk(currentStartLine, currentEndLine, currentContent.toString()));
            }
        }
        
        return diffHunks;
    }
} 