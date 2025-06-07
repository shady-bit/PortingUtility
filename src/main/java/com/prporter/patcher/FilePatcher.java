package com.prporter.patcher;

import com.prporter.model.ChangedFile;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class FilePatcher {
    private final Git git;
    private final Repository repository;

    public FilePatcher(Git git) {
        this.git = git;
        this.repository = git.getRepository();
    }

    public void applyChanges(ChangedFile file, String targetBranch, String prNumber) throws GitAPIException, IOException {
        // Create a new branch name based on the target branch and PR number
        String portBranchName = targetBranch + "-port-" + prNumber;
        
        // Checkout the target branch first
        git.checkout()
                .setName(targetBranch)
                .call();
        
        // Create and checkout the new port branch
        git.checkout()
                .setCreateBranch(true)
                .setName(portBranchName)
                .setStartPoint(targetBranch)
                .call();

        // Get the file path
        Path filePath = Paths.get(repository.getWorkTree().getAbsolutePath(), file.getPath());
        
        // Read the current content of the file
        List<String> lines = Files.readAllLines(filePath);
        
        // Get the current state of the file in the target branch
        ObjectId targetTreeId = repository.resolve(targetBranch + "^{tree}");
        CanonicalTreeParser targetTree = new CanonicalTreeParser();
        try (ObjectReader reader = repository.newObjectReader()) {
            targetTree.reset(reader, targetTreeId);
        }

        // Apply the changes from each diff hunk with context
        for (ChangedFile.DiffHunk hunk : file.getDiffHunks()) {
            if (!applyDiffHunkIntelligently(lines, hunk, filePath)) {
                System.out.println("Warning: Could not apply hunk at line " + hunk.getStartLine() + 
                    " in file " + file.getPath() + ". Manual review may be needed.");
            }
        }
        
        // Write the modified content back to the file
        Files.write(filePath, lines);
        
        // Stage and commit the changes
        git.add()
                .addFilepattern(file.getPath())
                .call();
        
        git.commit()
                .setMessage("Port changes from PR #" + prNumber + ": " + file.getPath())
                .call();
    }

    private boolean applyDiffHunkIntelligently(List<String> lines, ChangedFile.DiffHunk hunk, Path filePath) {
        String[] diffLines = hunk.getContent().split("\n");
        int currentLine = hunk.getStartLine() - 1; // Convert to 0-based index
        
        // Extract context lines
        List<String> contextBefore = new ArrayList<>();
        List<String> contextAfter = new ArrayList<>();
        List<String> changes = new ArrayList<>();
        
        boolean foundChanges = false;
        for (String diffLine : diffLines) {
            if (diffLine.startsWith(" ")) {
                if (foundChanges) {
                    contextAfter.add(diffLine.substring(1));
                } else {
                    contextBefore.add(diffLine.substring(1));
                }
            } else if (diffLine.startsWith("+") || diffLine.startsWith("-")) {
                foundChanges = true;
                changes.add(diffLine);
            }
        }
        
        // Find the best matching location for the hunk
        int bestMatchLine = findBestMatchLocation(lines, contextBefore, contextAfter, currentLine);
        if (bestMatchLine == -1) {
            return false;
        }
        
        // Apply the changes at the best matching location
        int lineIndex = bestMatchLine;
        for (String change : changes) {
            if (change.startsWith("+")) {
                if (lineIndex < lines.size()) {
                    lines.add(lineIndex, change.substring(1));
                } else {
                    lines.add(change.substring(1));
                }
                lineIndex++;
            } else if (change.startsWith("-")) {
                if (lineIndex < lines.size()) {
                    lines.remove(lineIndex);
                }
            }
        }
        
        return true;
    }

    private int findBestMatchLocation(List<String> lines, List<String> contextBefore, List<String> contextAfter, int expectedLine) {
        // First try the expected line
        if (matchesContext(lines, contextBefore, contextAfter, expectedLine)) {
            return expectedLine;
        }
        
        // If that fails, look for the best match within a reasonable range
        int searchRange = 10; // Look 10 lines up and down
        int bestMatch = -1;
        int bestScore = 0;
        
        for (int i = Math.max(0, expectedLine - searchRange); 
             i < Math.min(lines.size(), expectedLine + searchRange); i++) {
            int score = calculateContextMatchScore(lines, contextBefore, contextAfter, i);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = i;
            }
        }
        
        return bestMatch;
    }

    private boolean matchesContext(List<String> lines, List<String> contextBefore, List<String> contextAfter, int line) {
        // Check if we have enough lines before and after
        if (line < contextBefore.size() || line + contextAfter.size() > lines.size()) {
            return false;
        }
        
        // Check context before
        for (int i = 0; i < contextBefore.size(); i++) {
            if (!lines.get(line - contextBefore.size() + i).equals(contextBefore.get(i))) {
                return false;
            }
        }
        
        // Check context after
        for (int i = 0; i < contextAfter.size(); i++) {
            if (!lines.get(line + i).equals(contextAfter.get(i))) {
                return false;
            }
        }
        
        return true;
    }

    private int calculateContextMatchScore(List<String> lines, List<String> contextBefore, List<String> contextAfter, int line) {
        int score = 0;
        
        // Check context before
        for (int i = 0; i < contextBefore.size(); i++) {
            if (line - contextBefore.size() + i >= 0 && 
                line - contextBefore.size() + i < lines.size() &&
                lines.get(line - contextBefore.size() + i).equals(contextBefore.get(i))) {
                score++;
            }
        }
        
        // Check context after
        for (int i = 0; i < contextAfter.size(); i++) {
            if (line + i < lines.size() && 
                lines.get(line + i).equals(contextAfter.get(i))) {
                score++;
            }
        }
        
        return score;
    }
} 