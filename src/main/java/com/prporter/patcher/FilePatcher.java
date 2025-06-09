package com.prporter.patcher;

import com.prporter.model.ChangedFile;
import com.prporter.model.ChangedFile.MethodChange;
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
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

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

    public void applyChanges(ChangedFile file, String targetBranch, String prNumber) throws IOException, GitAPIException {
        // Create new branch for the port
        String portBranchName = targetBranch + "-port-" + prNumber;
        System.out.println("Creating port branch: " + portBranchName);
        
        // Checkout target branch first
        git.checkout()
           .setName(targetBranch)
           .call();
        
        // Create and checkout new branch
        git.checkout()
           .setCreateBranch(true)
           .setName(portBranchName)
           .call();

        // Read current content of the file
        Path filePath = git.getRepository().getWorkTree().toPath().resolve(file.getPath());
        List<String> currentLines = Files.readAllLines(filePath);
        
        // Track which methods were successfully ported
        List<String> portedMethods = new ArrayList<>();
        List<String> failedMethods = new ArrayList<>();
        
        // Process each method change
        for (MethodChange methodChange : file.getMethodChanges()) {
            try {
                System.out.println("Processing method: " + methodChange.getName());
                boolean success = applyMethodChange(currentLines, methodChange);
                if (success) {
                    methodChange.setPorted(true);
                    portedMethods.add(methodChange.getName());
                } else {
                    failedMethods.add(methodChange.getName());
                }
            } catch (Exception e) {
                System.out.println("Failed to port method " + methodChange.getName() + ": " + e.getMessage());
                failedMethods.add(methodChange.getName());
            }
        }

        // Write the modified content back to the file
        Files.write(filePath, currentLines);

        // Stage and commit the changes
        git.add()
           .addFilepattern(file.getPath())
           .call();

        // Create commit message with porting details
        StringBuilder commitMessage = new StringBuilder();
        commitMessage.append("Port changes from PR #").append(prNumber).append("\n\n");
        
        if (!portedMethods.isEmpty()) {
            commitMessage.append("Successfully ported methods:\n");
            for (String method : portedMethods) {
                commitMessage.append("- ").append(method).append("\n");
            }
        }
        
        if (!failedMethods.isEmpty()) {
            commitMessage.append("\nFailed to port methods (manual review needed):\n");
            for (String method : failedMethods) {
                commitMessage.append("- ").append(method).append("\n");
            }
        }

        git.commit()
           .setMessage(commitMessage.toString())
           .call();

        // Update file status based on porting results
        if (failedMethods.isEmpty()) {
            file.setStatus(com.prporter.model.FileStatus.PORTED);
        } else if (!portedMethods.isEmpty()) {
            file.setStatus(com.prporter.model.FileStatus.PARTIALLY_PORTED);
            file.setReason("Partially ported: " + String.join(", ", failedMethods) + " need manual review");
        } else {
            file.setStatus(com.prporter.model.FileStatus.SKIPPED);
            file.setReason("Failed to port any methods: " + String.join(", ", failedMethods));
        }
    }

    private boolean applyMethodChange(List<String> currentLines, MethodChange methodChange) {
        String[] methodLines = methodChange.getContent().split("\n");
        int bestMatchLine = findBestMatchLocation(currentLines, methodLines);
        
        if (bestMatchLine == -1) {
            System.out.println("Could not find suitable location for method: " + methodChange.getName());
            return false;
        }

        // Apply the method changes
        try {
            // Remove old method if it exists
            int methodEnd = findMethodEnd(currentLines, bestMatchLine);
            if (methodEnd > bestMatchLine) {
                for (int i = methodEnd; i >= bestMatchLine; i--) {
                    currentLines.remove(i);
                }
            }

            // Insert new method
            for (int i = 0; i < methodLines.length; i++) {
                currentLines.add(bestMatchLine + i, methodLines[i]);
            }
            return true;
        } catch (Exception e) {
            System.out.println("Error applying method changes: " + e.getMessage());
            return false;
        }
    }

    private int findMethodEnd(List<String> lines, int startLine) {
        int braceCount = 0;
        for (int i = startLine; i < lines.size(); i++) {
            String line = lines.get(i);
            braceCount += countChar(line, '{');
            braceCount -= countChar(line, '}');
            if (braceCount == 0) {
                return i;
            }
        }
        return startLine;
    }

    private int countChar(String str, char c) {
        return (int) str.chars().filter(ch -> ch == c).count();
    }

    private int findBestMatchLocation(List<String> currentLines, String[] methodLines) {
        // First try to find the method by name
        String methodName = extractMethodName(methodLines[0]);
        for (int i = 0; i < currentLines.size(); i++) {
            if (currentLines.get(i).contains(methodName + "(")) {
                return i;
            }
        }

        // If not found, try to find a good location based on context
        int bestMatchLine = -1;
        int bestMatchScore = 0;

        for (int i = 0; i < currentLines.size(); i++) {
            int score = calculateMatchScore(currentLines, i, methodLines);
            if (score > bestMatchScore) {
                bestMatchScore = score;
                bestMatchLine = i;
            }
        }

        return bestMatchLine;
    }

    private String extractMethodName(String line) {
        String[] parts = line.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].contains("(")) {
                return parts[i].substring(0, parts[i].indexOf("("));
            }
        }
        return "";
    }

    private int calculateMatchScore(List<String> currentLines, int startLine, String[] methodLines) {
        int score = 0;
        int maxLines = Math.min(5, methodLines.length);
        
        for (int i = 0; i < maxLines; i++) {
            if (startLine + i >= currentLines.size()) {
                break;
            }
            if (currentLines.get(startLine + i).trim().equals(methodLines[i].trim())) {
                score++;
            }
        }
        
        return score;
    }
} 