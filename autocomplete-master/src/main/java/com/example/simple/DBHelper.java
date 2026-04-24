package com.example.simple;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data access + model logic: schema setup, import persistence, generation, autocomplete, reports.
 */
public final class DBHelper {
    private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z']+");
    private static final Random RANDOM = new Random();

    public enum SortMode {
        ALPHABETICAL,
        FREQUENCY
    }

    public enum GenerationAlgorithm {
        TOP_FREQUENCY,
        WEIGHTED_RANDOM
    }

    public record ImportResult(int lines, int words) {}

    public record WordStats(String word, int totalCount, int startCount, int endCount) {}

    public record Suggestion(String word, int followCount) {}

    public record GeneratedRecord(long id, String algorithm, String startWord, String sentence, LocalDateTime generatedAt) {}

    public record DuplicateRecord(String sentence, int occurrences) {}

    private DBHelper() {
    }

    public static void initializeSchema() {
        try (Connection conn = DBConnection.getConnection(); Statement stmt = conn.createStatement()) {
            // Core tables required by the project spec.
            stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS word (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        word_text VARCHAR(255) NOT NULL UNIQUE,
                        total_count INT NOT NULL DEFAULT 0,
                        start_count INT NOT NULL DEFAULT 0,
                        end_count INT NOT NULL DEFAULT 0
                    )
                    """);

            stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS word_follows (
                        word_id INT NOT NULL,
                        next_word_id INT NOT NULL,
                        follow_count INT NOT NULL DEFAULT 0,
                        PRIMARY KEY (word_id, next_word_id),
                        CONSTRAINT fk_word_follows_word FOREIGN KEY (word_id) REFERENCES word(id),
                        CONSTRAINT fk_word_follows_next FOREIGN KEY (next_word_id) REFERENCES word(id)
                    )
                    """);

            stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS imported_file (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        file_path VARCHAR(1024) NOT NULL,
                        word_count INT NOT NULL,
                        line_count INT NOT NULL,
                        imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS generated_sentence (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        algorithm VARCHAR(64) NOT NULL,
                        start_word VARCHAR(255) NOT NULL,
                        sentence_text TEXT NOT NULL,
                        generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            ensureColumn(conn, "word", "total_count", "INT NOT NULL DEFAULT 0");
            ensureColumn(conn, "word", "start_count", "INT NOT NULL DEFAULT 0");
            ensureColumn(conn, "word", "end_count", "INT NOT NULL DEFAULT 0");
            ensureColumn(conn, "imported_file", "file_path", "VARCHAR(1024) NOT NULL DEFAULT ''");
            ensureColumn(conn, "imported_file", "file_name", "VARCHAR(512) NOT NULL DEFAULT ''");
            ensureColumn(conn, "imported_file", "word_count", "INT NOT NULL DEFAULT 0");
            ensureColumn(conn, "imported_file", "line_count", "INT NOT NULL DEFAULT 0");
            ensureColumn(conn, "imported_file", "imported_at", "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
            ensureColumn(conn, "generated_sentence", "algorithm", "VARCHAR(64) NOT NULL DEFAULT ''");
            ensureColumn(conn, "generated_sentence", "start_word", "VARCHAR(255) NULL");

            // Backfill legacy column names so old databases still work with the new UI/report queries.
            if (hasColumn(conn, "generated_sentence", "algorithm_used")) {
                try (Statement backfill = conn.createStatement()) {
                    backfill.executeUpdate(
                            "UPDATE generated_sentence SET algorithm = algorithm_used " +
                            "WHERE (algorithm IS NULL OR algorithm = '')");
                }
            }

            if (hasColumn(conn, "generated_sentence", "starting_word")) {
                try (Statement backfill = conn.createStatement()) {
                    backfill.executeUpdate(
                            "UPDATE generated_sentence SET start_word = starting_word " +
                            "WHERE start_word IS NULL");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }

    private static void ensureColumn(Connection conn, String table, String column, String ddl) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getColumns(conn.getCatalog(), null, table, column)) {
            if (rs.next()) {
                // Column already exists; keep schema migration idempotent.
                return;
            }
        }

        try (Statement statement = conn.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddl);
        }
    }

    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getColumns(conn.getCatalog(), null, table, column)) {
            return rs.next();
        }
    }

    public static ImportResult importFile(String filePath) throws Exception {
        int lines = 0;
        int words = 0;

        try (Connection conn = DBConnection.getConnection()) {
            // Import one file as a single transaction to keep counts and transitions consistent.
            conn.setAutoCommit(false);
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(filePath))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines++;
                    String[] sentenceParts = line.split("[.!?]+");
                    for (String sentence : sentenceParts) {
                        List<String> tokens = extractWords(sentence);
                        if (tokens.isEmpty()) {
                            continue;
                        }
                        words += tokens.size();
                        ingestTokenSequence(conn, tokens);
                    }
                }
            }

            try (PreparedStatement insertFile = conn.prepareStatement(
                    "INSERT INTO imported_file (file_path, file_name, word_count, line_count) VALUES (?, ?, ?, ?)")) {
                insertFile.setString(1, filePath);
                insertFile.setString(2, java.nio.file.Path.of(filePath).getFileName().toString());
                insertFile.setInt(3, words);
                insertFile.setInt(4, lines);
                insertFile.executeUpdate();
            }

            conn.commit();
        }

        return new ImportResult(lines, words);
    }

    public static void ingestFreeText(String sourceText) throws Exception {
        List<String> tokens = extractWords(sourceText);
        if (tokens.isEmpty()) {
            return;
        }

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            ingestTokenSequence(conn, tokens);
            conn.commit();
        }
    }

    private static void ingestTokenSequence(Connection conn, List<String> tokens) throws SQLException {
        int previousId = -1;
        for (int i = 0; i < tokens.size(); i++) {
            String word = tokens.get(i);
            boolean isStart = i == 0;
            boolean isEnd = i == tokens.size() - 1;
            int currentId = upsertWord(conn, word, isStart, isEnd);
            if (previousId > 0) {
                // Track adjacency for both generation and autocomplete suggestions.
                upsertFollow(conn, previousId, currentId);
            }
            previousId = currentId;
        }
    }

    public static void addWordIfMissing(String word) {
        String normalized = normalizeWord(word);
        if (normalized == null) {
            return;
        }

        try (Connection conn = DBConnection.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO word (word_text, total_count, start_count, end_count) VALUES (?, 0, 0, 0) " +
                            "ON DUPLICATE KEY UPDATE word_text = VALUES(word_text)")) {
                stmt.setString(1, normalized);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add word", e);
        }
    }

    public static List<Suggestion> getSuggestions(String word, int limit) {
        String normalized = normalizeWord(word);
        if (normalized == null) {
            return Collections.emptyList();
        }

        List<Suggestion> suggestions = new ArrayList<>();
        String query = """
                SELECT w2.word_text, wf.follow_count
                FROM word_follows wf
                JOIN word w1 ON wf.word_id = w1.id
                JOIN word w2 ON wf.next_word_id = w2.id
                WHERE w1.word_text = ?
                ORDER BY wf.follow_count DESC, w2.word_text ASC
                LIMIT ?
                """;

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, normalized);
            stmt.setInt(2, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    suggestions.add(new Suggestion(rs.getString("word_text"), rs.getInt("follow_count")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query suggestions", e);
        }

        return suggestions;
    }

    public static String generateSentence(String startWord, int maxWords, GenerationAlgorithm algorithm) {
        String normalizedStart = normalizeWord(startWord);
        if (normalizedStart == null) {
            return "";
        }

        List<String> words = new ArrayList<>();
        // Avoid repeatedly traversing the same directed pair, which causes short loops.
        Set<String> usedEdges = new HashSet<>();
        words.add(normalizedStart);
        String current = normalizedStart;

        for (int i = 1; i < maxWords; i++) {
            List<Suggestion> candidates = getSuggestions(current, 20);
            if (candidates.isEmpty()) {
                break;
            }

            String next = pickNextWord(candidates, algorithm, words, usedEdges, current);
            if (next == null || next.isBlank()) {
                break;
            }

            words.add(next);
            usedEdges.add(current + "->" + next);
            current = next;
        }

        if (words.isEmpty()) {
            return "";
        }

        String sentence = String.join(" ", words);
        saveGeneratedSentence(algorithm, normalizedStart, sentence);
        return sentence;
    }

    private static String pickWeighted(List<Suggestion> candidates) {
        int total = 0;
        for (Suggestion candidate : candidates) {
            total += Math.max(1, candidate.followCount());
        }

        int r = RANDOM.nextInt(total);
        int cumulative = 0;
        for (Suggestion candidate : candidates) {
            cumulative += Math.max(1, candidate.followCount());
            if (r < cumulative) {
                return candidate.word();
            }
        }

        return candidates.get(0).word();
    }

    private static String pickNextWord(List<Suggestion> candidates,
                                       GenerationAlgorithm algorithm,
                                       List<String> words,
                                       Set<String> usedEdges,
                                       String currentWord) {
        List<Suggestion> filtered = new ArrayList<>();
        for (Suggestion candidate : candidates) {
            String edge = currentWord + "->" + candidate.word();
            if (!usedEdges.contains(edge) && !formsImmediateCycle(words, candidate.word())) {
                filtered.add(candidate);
            }
        }

        // Fall back to full candidate list if filtering removes everything.
        List<Suggestion> source = filtered.isEmpty() ? candidates : filtered;
        return switch (algorithm) {
            case TOP_FREQUENCY -> source.get(0).word();
            case WEIGHTED_RANDOM -> pickWeighted(source);
        };
    }

    private static boolean formsImmediateCycle(List<String> words, String nextWord) {
        int size = words.size();
        if (size < 3) {
            return false;
        }

        String prev2 = words.get(size - 2);
        String prev3 = words.get(size - 3);
        String prev1 = words.get(size - 1);

        // Detect ABAB-style pattern at the tail, e.g., "to the to the".
        return nextWord.equals(prev3) && prev1.equals(prev2);
    }

    private static void saveGeneratedSentence(GenerationAlgorithm algorithm, String startWord, String sentence) {
        // Write both old and new column names so mixed schemas keep accepting inserts.
        String sql = "INSERT INTO generated_sentence (algorithm, algorithm_used, start_word, starting_word, sentence_text) " +
                "VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, algorithm.name());
            stmt.setString(2, algorithm.name());
            stmt.setString(3, startWord);
            stmt.setString(4, startWord);
            stmt.setString(5, sentence);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save generated sentence", e);
        }
    }

    public static List<WordStats> getAllWords(SortMode mode) {
        String orderBy = mode == SortMode.FREQUENCY
                ? "total_count DESC, word_text ASC"
                : "word_text ASC";

        String sql = "SELECT word_text, total_count, start_count, end_count FROM word ORDER BY " + orderBy;
        List<WordStats> rows = new ArrayList<>();

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                rows.add(new WordStats(
                        rs.getString("word_text"),
                        rs.getInt("total_count"),
                        rs.getInt("start_count"),
                        rs.getInt("end_count")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load words", e);
        }

        return rows;
    }

    public static void updateWordStats(String word, int total, int start, int end) {
        String normalized = normalizeWord(word);
        if (normalized == null) {
            throw new IllegalArgumentException("Word cannot be empty");
        }

        String sql = "UPDATE word SET total_count = ?, start_count = ?, end_count = ? WHERE word_text = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, total);
            stmt.setInt(2, start);
            stmt.setInt(3, end);
            stmt.setString(4, normalized);
            int updated = stmt.executeUpdate();
            if (updated == 0) {
                throw new IllegalArgumentException("Word not found: " + normalized);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update word", e);
        }
    }

    public static List<GeneratedRecord> getGeneratedHistory(int limit) {
        String sql = """
                SELECT id,
                       COALESCE(NULLIF(algorithm, ''), algorithm_used) AS algo,
                       COALESCE(start_word, starting_word) AS start_w,
                       sentence_text,
                       generated_at
                FROM generated_sentence
                ORDER BY generated_at DESC, id DESC
                LIMIT ?
                """;

        List<GeneratedRecord> rows = new ArrayList<>();

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("generated_at");
                    rows.add(new GeneratedRecord(
                            rs.getLong("id"),
                            rs.getString("algo"),
                            rs.getString("start_w"),
                            rs.getString("sentence_text"),
                            ts == null ? null : ts.toLocalDateTime()
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load generated history", e);
        }

        return rows;
    }

    public static List<DuplicateRecord> getDuplicateSentences() {
        String sql = """
                SELECT sentence_text, COUNT(*) AS occurrences
                FROM generated_sentence
                GROUP BY sentence_text
                HAVING COUNT(*) > 1
                ORDER BY occurrences DESC, sentence_text ASC
                """;

        List<DuplicateRecord> rows = new ArrayList<>();

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                rows.add(new DuplicateRecord(rs.getString("sentence_text"), rs.getInt("occurrences")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load duplicates", e);
        }

        return rows;
    }

    public static List<String> getImportedFilesSummary(int limit) {
        String sql = """
                SELECT imported_at, file_path, word_count, line_count
                FROM imported_file
                ORDER BY imported_at DESC, id DESC
                LIMIT ?
                """;

        List<String> rows = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(String.format(
                            "%s | %s | words=%d | lines=%d",
                            rs.getTimestamp("imported_at"),
                            rs.getString("file_path"),
                            rs.getInt("word_count"),
                            rs.getInt("line_count")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load imported file summary", e);
        }

        return rows;
    }

    public static List<String> extractWords(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        List<String> words = new ArrayList<>();
        Matcher matcher = WORD_PATTERN.matcher(text);
        while (matcher.find()) {
            String normalized = normalizeWord(matcher.group());
            if (normalized != null) {
                words.add(normalized);
            }
        }

        return words;
    }

    private static int upsertWord(Connection conn, String word, boolean isStart, boolean isEnd) throws SQLException {
        String sql = """
                INSERT INTO word (word_text, total_count, start_count, end_count)
                VALUES (?, 1, ?, ?)
                ON DUPLICATE KEY UPDATE
                    total_count = total_count + 1,
                    start_count = start_count + VALUES(start_count),
                    end_count = end_count + VALUES(end_count)
                """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, word);
            stmt.setInt(2, isStart ? 1 : 0);
            stmt.setInt(3, isEnd ? 1 : 0);
            stmt.executeUpdate();
        }

        try (PreparedStatement stmt = conn.prepareStatement("SELECT id FROM word WHERE word_text = ?")) {
            stmt.setString(1, word);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }

        throw new SQLException("Failed to resolve word id for: " + word);
    }

    private static void upsertFollow(Connection conn, int wordId, int nextWordId) throws SQLException {
        String sql = """
                INSERT INTO word_follows (word_id, next_word_id, follow_count)
                VALUES (?, ?, 1)
                ON DUPLICATE KEY UPDATE follow_count = follow_count + 1
                """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, wordId);
            stmt.setInt(2, nextWordId);
            stmt.executeUpdate();
        }
    }

    private static String normalizeWord(String raw) {
        if (raw == null) {
            return null;
        }

        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("^'+|'+$", "")
                .trim();

        if (normalized.isBlank() || !normalized.matches("[a-z']+")) {
            return null;
        }

        return normalized;
    }
}
