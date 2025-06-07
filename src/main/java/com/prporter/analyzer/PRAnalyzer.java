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

            // Get all commits between merge base and source commit
            System.out.println("Getting PR commits...");
            revWalk.reset();
            revWalk.markStart(sourceCommit);
            revWalk.markUninteresting(mergeBaseCommit);
            
            // Configure RevWalk to show all commits
            revWalk.setRevFilter(RevFilter.ALL);
            
            Set<String> prChangedFiles = new HashSet<>();
            Map<String, ChangedFile> changedFilesMap = new HashMap<>();
            
            int commitCount = 0;
            // Process each commit in the PR
            for (RevCommit commit : revWalk) {
                if (commit.equals(mergeBaseCommit)) {
                    break;
                }
                
                commitCount++;
                System.out.println("\nProcessing commit " + commitCount + ": " + commit.getName());
                System.out.println("Commit message: " + commit.getFullMessage());
                
                // Get the parent commit
                RevCommit parent = commit.getParent(0);
                
                // Get the tree iterators for this commit and its parent
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, parent.getTree().getId());
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, commit.getTree().getId());
                
                // Get the list of changes in this commit
                List<DiffEntry> diffs = git.diff()
                        .setOldTree(oldTree)
                        .setNewTree(newTree)
                        .call();
                
                System.out.println("Found " + diffs.size() + " changes in this commit");
                
                // Process each changed file
                for (DiffEntry diff : diffs) {
                    String filePath = diff.getChangeType() == DiffEntry.ChangeType.DELETE ? 
                        diff.getOldPath() : diff.getNewPath();
                    
                    System.out.println("  File: " + filePath + " (Change type: " + diff.getChangeType() + ")");
                    
                    if (!prChangedFiles.contains(filePath)) {
                        prChangedFiles.add(filePath);
                        ChangedFile changedFile = new ChangedFile(filePath);
                        if (diff.getChangeType() == DiffEntry.ChangeType.MODIFY || 
                            diff.getChangeType() == DiffEntry.ChangeType.ADD) {
                            List<ChangedFile.DiffHunk> diffHunks = extractDiffHunks(diff, parent, commit);
                            if (!diffHunks.isEmpty()) {
                                changedFile.setDiffHunks(diffHunks);
                                System.out.println("    Found " + diffHunks.size() + " diff hunks");
                            }
                        }
                        changedFilesMap.put(filePath, changedFile);
                    }
                }
            }
            
            System.out.println("\nTotal commits processed: " + commitCount);
            
            // Convert map to list
            changedFiles.addAll(changedFilesMap.values());
            System.out.println("Found " + changedFiles.size() + " unique files changed in PR");
            
            if (changedFiles.isEmpty()) {
                System.out.println("WARNING: No files were detected in the PR. This might indicate an issue with:");
                System.out.println("1. Branch resolution");
                System.out.println("2. Commit history");
                System.out.println("3. Merge base detection");
                System.out.println("Please verify the source and target branches are correct.");
                System.out.println("Source branch: " + sourceBranch);
                System.out.println("Target branch: " + targetBranch);
                System.out.println("Source commit: " + sourceId.getName());
                System.out.println("Target commit: " + targetId.getName());
                System.out.println("Merge base: " + mergeBaseCommit.getName());
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