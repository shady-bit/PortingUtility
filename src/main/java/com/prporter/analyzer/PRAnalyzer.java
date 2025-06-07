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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PRAnalyzer {
    private final Git git;
    private final Repository repository;

    public PRAnalyzer(Git git) {
        this.git = git;
        this.repository = git.getRepository();
    }

    public List<ChangedFile> analyzePR(String sourceBranch, String targetBranch, String prNumber) throws GitAPIException, IOException {
        List<ChangedFile> changedFiles = new ArrayList<>();

        // Get the commit IDs for both branches
        ObjectId sourceId = repository.resolve(sourceBranch);
        ObjectId targetId = repository.resolve(targetBranch);

        if (sourceId == null || targetId == null) {
            throw new JGitInternalException("Could not resolve branch references");
        }

        try (RevWalk revWalk = new RevWalk(repository);
             ObjectReader reader = repository.newObjectReader()) {

            RevCommit sourceCommit = revWalk.parseCommit(sourceId);
            RevCommit targetCommit = revWalk.parseCommit(targetId);

            // Get the tree iterators for both commits
            CanonicalTreeParser sourceTree = new CanonicalTreeParser();
            sourceTree.reset(reader, sourceCommit.getTree().getId());
            CanonicalTreeParser targetTree = new CanonicalTreeParser();
            targetTree.reset(reader, targetCommit.getTree().getId());

            // Get the list of changes
            List<DiffEntry> diffs = git.diff()
                    .setOldTree(sourceTree)
                    .setNewTree(targetTree)
                    .call();

            // Process each changed file
            for (DiffEntry diff : diffs) {
                if (diff.getChangeType() == DiffEntry.ChangeType.MODIFY) {
                    ChangedFile changedFile = new ChangedFile(diff.getNewPath());
                    List<ChangedFile.DiffHunk> diffHunks = extractDiffHunks(diff, sourceCommit, targetCommit);
                    changedFile.setDiffHunks(diffHunks);
                    changedFiles.add(changedFile);
                }
            }
        }

        return changedFiles;
    }

    private List<ChangedFile.DiffHunk> extractDiffHunks(DiffEntry diff, RevCommit sourceCommit, RevCommit targetCommit) throws IOException {
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