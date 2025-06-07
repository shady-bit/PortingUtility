package com.prporter.model;

import java.util.List;

public class ChangedFile {
    private String path;
    private FileStatus status;
    private String reason;
    private List<DiffHunk> diffHunks;

    public ChangedFile(String path) {
        this.path = path;
        this.status = FileStatus.PENDING;
    }

    public String getPath() {
        return path;
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

    public List<DiffHunk> getDiffHunks() {
        return diffHunks;
    }

    public void setDiffHunks(List<DiffHunk> diffHunks) {
        this.diffHunks = diffHunks;
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
} 