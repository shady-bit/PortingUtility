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
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.transport.CredentialsProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

        try (RevWalk revWalk = new RevWalk(repository);
             ObjectReader reader = repository.newObjectReader()) {

            RevCommit sourceCommit = revWalk.parseCommit(sourceId);
            RevCommit targetCommit = revWalk.parseCommit(targetId);

            // Find merge base using RevWalk
            System.out.println("Finding merge base...");
            revWalk.reset();
            revWalk.markStart(sourceCommit);
            revWalk.markStart(targetCommit);
            
            RevCommit mergeBaseCommit = null;
            for (RevCommit commit : revWalk) {
                if (commit.getParentCount() > 0) {
                    mergeBaseCommit = commit;
                    break;
                }
            }
            
            if (mergeBaseCommit == null) {
                throw new JGitInternalException("Could not find common ancestor between branches. " +
                    "Source: " + sourceId.getName() + ", Target: " + targetId.getName());
            }
            
            System.out.println("Merge base commit: " + mergeBaseCommit.getName());

            // Get the tree iterators for the merge base and source commit
            CanonicalTreeParser mergeBaseTree = new CanonicalTreeParser();
            mergeBaseTree.reset(reader, mergeBaseCommit.getTree().getId());
            CanonicalTreeParser sourceTree = new CanonicalTreeParser();
            sourceTree.reset(reader, sourceCommit.getTree().getId());

            // Get the list of changes between merge base and source (PR changes)
            System.out.println("Calculating PR changes...");
            List<DiffEntry> diffs = git.diff()
                    .setOldTree(mergeBaseTree)  // Compare against merge base
                    .setNewTree(sourceTree)     // Using source branch changes
                    .call();

            // Process each changed file
            for (DiffEntry diff : diffs) {
                if (diff.getChangeType() == DiffEntry.ChangeType.MODIFY) {
                    ChangedFile changedFile = new ChangedFile(diff.getNewPath());
                    List<ChangedFile.DiffHunk> diffHunks = extractDiffHunks(diff, mergeBaseCommit, sourceCommit);
                    changedFile.setDiffHunks(diffHunks);
                    changedFiles.add(changedFile);
                }
            }

            System.out.println("Found " + changedFiles.size() + " files changed in PR");
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