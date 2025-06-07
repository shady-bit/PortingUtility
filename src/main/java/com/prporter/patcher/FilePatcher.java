package com.prporter.patcher;

import com.prporter.model.ChangedFile;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class FilePatcher {
    private final Git git;
    private final Repository repository;

    public FilePatcher(Git git) {
        this.git = git;
        this.repository = git.getRepository();
    }

    public void applyChanges(ChangedFile file, String targetBranch) throws GitAPIException, IOException {
        // Checkout the target branch
        git.checkout()
                .setName(targetBranch)
                .call();

        // Get the file path
        Path filePath = Paths.get(repository.getWorkTree().getAbsolutePath(), file.getPath());
        
        // Read the current content of the file
        List<String> lines = Files.readAllLines(filePath);
        
        // Apply the changes from each diff hunk
        for (ChangedFile.DiffHunk hunk : file.getDiffHunks()) {
            applyDiffHunk(lines, hunk);
        }
        
        // Write the modified content back to the file
        Files.write(filePath, lines);
        
        // Stage and commit the changes
        git.add()
                .addFilepattern(file.getPath())
                .call();
        
        git.commit()
                .setMessage("Port changes from PR: " + file.getPath())
                .call();
    }

    private void applyDiffHunk(List<String> lines, ChangedFile.DiffHunk hunk) {
        String[] diffLines = hunk.getContent().split("\n");
        int currentLine = hunk.getStartLine() - 1; // Convert to 0-based index
        
        for (String diffLine : diffLines) {
            if (diffLine.startsWith("+")) {
                // Add new line
                if (currentLine < lines.size()) {
                    lines.add(currentLine, diffLine.substring(1));
                } else {
                    lines.add(diffLine.substring(1));
                }
                currentLine++;
            } else if (diffLine.startsWith("-")) {
                // Remove line
                if (currentLine < lines.size()) {
                    lines.remove(currentLine);
                }
            } else {
                // Context line, just move to next line
                currentLine++;
            }
        }
    }
} 