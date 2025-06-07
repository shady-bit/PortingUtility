package com.prporter.checker;

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
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.eclipse.jgit.transport.CredentialsProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

public class ConflictChecker {
    private final Git git;
    private final Repository repository;
    private final CredentialsProvider credentialsProvider;

    public ConflictChecker(Git git, CredentialsProvider credentialsProvider) {
        this.git = git;
        this.repository = git.getRepository();
        this.credentialsProvider = credentialsProvider;
    }

    public boolean hasConflict(ChangedFile file, String targetBranch) throws GitAPIException, IOException {
        // Fetch latest changes
        System.out.println("Fetching latest changes for conflict check...");
        git.fetch()
           .setCredentialsProvider(credentialsProvider)
           .setForceUpdate(true)
           .call();

        // Get the current HEAD commit and the target branch commit
        ObjectId headId = repository.resolve("HEAD");
        ObjectId targetId = repository.resolve("refs/remotes/origin/" + targetBranch);

        if (headId == null) {
            throw new JGitInternalException("Could not resolve HEAD reference");
        }
        if (targetId == null) {
            throw new JGitInternalException("Could not resolve target branch: " + targetBranch);
        }

        try (RevWalk revWalk = new RevWalk(repository);
             ObjectReader reader = repository.newObjectReader()) {

            RevCommit headCommit = revWalk.parseCommit(headId);
            RevCommit targetCommit = revWalk.parseCommit(targetId);

            // Get the tree iterators for both commits
            CanonicalTreeParser headTree = new CanonicalTreeParser();
            headTree.reset(reader, headCommit.getTree().getId());
            CanonicalTreeParser targetTree = new CanonicalTreeParser();
            targetTree.reset(reader, targetCommit.getTree().getId());

            // Get the list of changes
            List<DiffEntry> diffs = git.diff()
                    .setOldTree(headTree)
                    .setNewTree(targetTree)
                    .call();

            // Check if the file exists in the target branch
            boolean fileExistsInTarget = false;
            for (DiffEntry diff : diffs) {
                if (diff.getNewPath().equals(file.getPath())) {
                    fileExistsInTarget = true;
                    break;
                }
            }

            // If the file doesn't exist in the target branch, there's no conflict
            if (!fileExistsInTarget) {
                return false;
            }

            // Check for overlapping changes in the target branch
            for (DiffEntry diff : diffs) {
                if (diff.getNewPath().equals(file.getPath())) {
                    return checkForOverlappingChanges(diff, file, headCommit, targetCommit);
                }
            }
        }

        return false;
    }

    private boolean checkForOverlappingChanges(DiffEntry diff, ChangedFile file, RevCommit headCommit, RevCommit targetCommit) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter diffFormatter = new DiffFormatter(out)) {
            diffFormatter.setRepository(repository);
            
            // Get the diff content from the target branch
            diffFormatter.format(diff);
            String targetDiffContent = out.toString();
            
            // Check if any of the changed lines in the PR overlap with changes in the target branch
            for (ChangedFile.DiffHunk prHunk : file.getDiffHunks()) {
                if (hasOverlappingLines(targetDiffContent, prHunk.getStartLine(), prHunk.getEndLine())) {
                    return true;
                }
            }
        }
        
        return false;
    }

    private boolean hasOverlappingLines(String diffContent, int startLine, int endLine) {
        String[] lines = diffContent.split("\n");
        int currentLine = 0;
        
        for (String line : lines) {
            if (line.startsWith("@@")) {
                // Parse the hunk header to get the starting line number
                String[] parts = line.split(" ");
                if (parts.length >= 2) {
                    String[] lineNumbers = parts[1].split(",");
                    if (lineNumbers.length >= 2) {
                        currentLine = Integer.parseInt(lineNumbers[0].substring(1));
                    }
                }
            } else if (line.startsWith("+") || line.startsWith("-")) {
                // Check if this line overlaps with the PR changes
                if (currentLine >= startLine && currentLine <= endLine) {
                    return true;
                }
                if (!line.startsWith("-")) {
                    currentLine++;
                }
            } else {
                currentLine++;
            }
        }
        
        return false;
    }
} 