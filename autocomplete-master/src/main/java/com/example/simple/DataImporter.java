package com.example.simple;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch import coordinator used by the UI import tab.
 */
public final class DataImporter {
    /**
     * Progress callback for updating JavaFX task status.
     */
    public interface ProgressCallback {
        void onProgress(double progress, String message);
    }

    public record BatchImportResult(int files, int lines, int words) {}

    private DataImporter() {
    }

    public static BatchImportResult importFiles(List<Path> files, ProgressCallback callback) throws Exception {
        if (files == null || files.isEmpty()) {
            return new BatchImportResult(0, 0, 0);
        }

        // Pre-filter invalid paths so progress and totals stay accurate.
        List<Path> existing = new ArrayList<>();
        for (Path file : files) {
            if (file != null && java.nio.file.Files.exists(file) && java.nio.file.Files.isRegularFile(file)) {
                existing.add(file);
            }
        }

        int totalFiles = existing.size();
        int totalLines = 0;
        int totalWords = 0;

        for (int i = 0; i < existing.size(); i++) {
            Path file = existing.get(i);
            DBHelper.ImportResult result = DBHelper.importFile(file.toAbsolutePath().toString());
            totalLines += result.lines();
            totalWords += result.words();

            double progress = (i + 1) / (double) totalFiles;
            if (callback != null) {
                callback.onProgress(progress, "Imported " + file.getFileName() + " (" + result.words() + " words)");
            }
        }

        return new BatchImportResult(totalFiles, totalLines, totalWords);
    }
}
