package com.prporter.model;

import java.util.ArrayList;
import java.util.List;

public class ChangedFile {
    private String path;
    private List<DiffHunk> diffHunks;
    private FileStatus status;
    private String reason;
    private List<MethodChange> methodChanges;
    private String aiSuggestion;

    public ChangedFile(String path) {
        this.path = path;
        this.diffHunks = new ArrayList<>();
        this.methodChanges = new ArrayList<>();
        this.status = FileStatus.PENDING;
    }

    public String getPath() {
        return path;
    }

    public List<DiffHunk> getDiffHunks() {
        return diffHunks;
    }

    public void setDiffHunks(List<DiffHunk> diffHunks) {
        this.diffHunks = diffHunks;
        // Analyze diff hunks to identify method changes
        analyzeMethodChanges();
    }

    public FileStatus getStatus() {
        return status;
    }

    public void setStatus(FileStatus status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<MethodChange> getMethodChanges() {
        return methodChanges;
    }

    public String getAiSuggestion() {
        return aiSuggestion;
    }

    public void setAiSuggestion(String aiSuggestion) {
        this.aiSuggestion = aiSuggestion;
    }

    private void analyzeMethodChanges() {
        methodChanges.clear();
        MethodChange currentMethod = null;
        StringBuilder methodContent = new StringBuilder();

        for (DiffHunk hunk : diffHunks) {
            String[] lines = hunk.getContent().split("\n");
            for (String line : lines) {
                // Look for method declarations
                if (line.matches(".*\\b(public|private|protected|static|final)\\s+\\w+\\s+\\w+\\s*\\(.*\\)\\s*\\{")) {
                    // Save previous method if exists
                    if (currentMethod != null) {
                        currentMethod.setContent(methodContent.toString());
                        methodChanges.add(currentMethod);
                    }
                    // Start new method
                    currentMethod = new MethodChange();
                    currentMethod.setName(extractMethodName(line));
                    currentMethod.setStartLine(hunk.getStartLine());
                    methodContent = new StringBuilder();
                }
                if (currentMethod != null) {
                    methodContent.append(line).append("\n");
                }
            }
        }
        // Add the last method
        if (currentMethod != null) {
            currentMethod.setContent(methodContent.toString());
            methodChanges.add(currentMethod);
        }
    }

    private String extractMethodName(String line) {
        // Extract method name from declaration
        String[] parts = line.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].contains("(")) {
                return parts[i].substring(0, parts[i].indexOf("("));
            }
        }
        return "unknown";
    }

    public static class DiffHunk {
        private int startLine;
        private int endLine;
        private String content;

        public DiffHunk(int startLine, int endLine, String content) {
            this.startLine = startLine;
            this.endLine = endLine;
            this.content = content;
        }

        public int getStartLine() {
            return startLine;
        }

        public int getEndLine() {
            return endLine;
        }

        public String getContent() {
            return content;
        }
    }

    public static class MethodChange {
        private String name;
        private int startLine;
        private String content;
        private boolean ported;

        public MethodChange() {
            this.ported = false;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getStartLine() {
            return startLine;
        }

        public void setStartLine(int startLine) {
            this.startLine = startLine;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public boolean isPorted() {
            return ported;
        }

        public void setPorted(boolean ported) {
            this.ported = ported;
        }
    }
} 