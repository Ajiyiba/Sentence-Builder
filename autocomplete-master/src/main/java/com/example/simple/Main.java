package com.example.simple;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.util.Duration;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JavaFX entry point and UI workflow wiring for import/generate/autocomplete/report features.
 */
public class Main extends Application {
    private final TextArea importLogArea = new TextArea();
    private final ProgressBar importProgress = new ProgressBar(0);
    private Button selectFilesBtn;
    private Button quickDataBtn;
    private volatile boolean importRunning = false;

    private final TextField startWordField = new TextField();
    private final Spinner<Integer> maxWordsSpinner = new Spinner<>(3, 50, 12);
    private final ChoiceBox<DBHelper.GenerationAlgorithm> algorithmChoice =
            new ChoiceBox<>(FXCollections.observableArrayList(DBHelper.GenerationAlgorithm.values()));
    private final TextArea generatedSentenceArea = new TextArea();

    private final TextArea composerArea = new TextArea();
    private final HBox predictionBar = new HBox(8);
    private final List<Button> predictionButtons = new ArrayList<>();
    private final Label autocompleteStatus = new Label("Type words and press space/comma for next-word suggestions.");
    private final PauseTransition autocompleteDebounce = new PauseTransition(Duration.millis(180));

    private final ChoiceBox<DBHelper.SortMode> sortChoice =
            new ChoiceBox<>(FXCollections.observableArrayList(DBHelper.SortMode.values()));
    private final TableView<DBHelper.WordStats> wordTable = new TableView<>();
    private final TextArea importedFilesArea = new TextArea();
    private final TextArea generatedHistoryArea = new TextArea();
    private final TextArea duplicateArea = new TextArea();

    private final TextField editWordField = new TextField();
    private final TextField editTotalField = new TextField();
    private final TextField editStartField = new TextField();
    private final TextField editEndField = new TextField();

    @Override
    public void start(Stage stage) {
        try {
            DBHelper.initializeSchema();
        } catch (Exception ex) {
            Alert alert = new Alert(
                    Alert.AlertType.WARNING,
                    "Database init failed. UI will open, but DB features need valid credentials.\n\n" + ex.getMessage(),
                    ButtonType.OK
            );
            alert.showAndWait();
        }

        TabPane tabPane = new TabPane();
        tabPane.getTabs().add(createImportTab(stage));
        tabPane.getTabs().add(createGenerateTab());
        tabPane.getTabs().add(createAutocompleteTab());
        tabPane.getTabs().add(createReportsTab());

        BorderPane root = new BorderPane(tabPane);
        root.setPadding(new Insets(12));
        root.setStyle("-fx-background-color: linear-gradient(to bottom, #f7f8fc, #eaf1f8);");

        Scene scene = new Scene(root, 980, 700);
        stage.setScene(scene);
        stage.setTitle("Sentence Builder - JavaFX + MySQL");
        stage.show();

        refreshReports();
    }

    private Tab createImportTab(Stage stage) {
        selectFilesBtn = new Button("Select Text Files");
        quickDataBtn = new Button("Import ../data Folder");

        importLogArea.setEditable(false);
        importLogArea.setWrapText(true);
        importProgress.setMaxWidth(Double.MAX_VALUE);

        selectFilesBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose Text Files");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text", "*.txt"));

            File defaultData = Path.of("..", "data").toFile();
            if (defaultData.exists() && defaultData.isDirectory()) {
                chooser.setInitialDirectory(defaultData);
            }

            List<File> selected = chooser.showOpenMultipleDialog(stage);
            if (selected == null || selected.isEmpty()) {
                return;
            }

            List<Path> paths = selected.stream().map(File::toPath).toList();
            runImportTask(paths);
        });

        quickDataBtn.setOnAction(e -> {
            File folder = Path.of("..", "data").toFile();
            if (!folder.exists() || !folder.isDirectory()) {
                importLogArea.appendText("Could not find ../data folder\n");
                return;
            }
            File[] txt = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".txt"));
            if (txt == null || txt.length == 0) {
                importLogArea.appendText("No .txt files found in ../data\n");
                return;
            }
            List<Path> paths = new ArrayList<>();
            for (File file : txt) {
                paths.add(file.toPath());
            }
            runImportTask(paths);
        });

        HBox actions = new HBox(10, selectFilesBtn, quickDataBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox layout = new VBox(12,
                new Label("Import text files into MySQL with progress"),
                actions,
                importProgress,
                importLogArea
        );
        layout.setPadding(new Insets(12));
        VBox.setVgrow(importLogArea, Priority.ALWAYS);

        Tab tab = new Tab("Import", layout);
        tab.setClosable(false);
        return tab;
    }

    private void runImportTask(List<Path> files) {
        if (importRunning) {
            // Guard against double-click imports that would start overlapping DB writes.
            importLogArea.appendText("Import is already running. Please wait.\n");
            return;
        }
        importRunning = true;
        selectFilesBtn.setDisable(true);
        quickDataBtn.setDisable(true);

        Task<DataImporter.BatchImportResult> task = new Task<>() {
            @Override
            protected DataImporter.BatchImportResult call() throws Exception {
                updateMessage("Starting import...");
                return DataImporter.importFiles(files, (progress, message) -> {
                    updateProgress(progress, 1.0);
                    updateMessage(message);
                });
            }
        };

        importProgress.progressProperty().unbind();
        importProgress.progressProperty().bind(task.progressProperty());
        task.messageProperty().addListener((obs, oldV, newV) -> {
            if (newV != null && !newV.isBlank()) {
                importLogArea.appendText(newV + "\n");
            }
        });

        task.setOnSucceeded(e -> {
            DataImporter.BatchImportResult result = task.getValue();
            importLogArea.appendText(String.format("Done. files=%d lines=%d words=%d\n",
                    result.files(), result.lines(), result.words()));
            importRunning = false;
            selectFilesBtn.setDisable(false);
            quickDataBtn.setDisable(false);
            refreshReports();
        });

        task.setOnFailed(e -> {
            importLogArea.appendText("Import failed: " + task.getException().getMessage() + "\n");
            importRunning = false;
            selectFilesBtn.setDisable(false);
            quickDataBtn.setDisable(false);
        });

        Thread t = new Thread(task, "import-task");
        t.setDaemon(true);
        t.start();
    }

    private Tab createGenerateTab() {
        algorithmChoice.setValue(DBHelper.GenerationAlgorithm.TOP_FREQUENCY);
        startWordField.setPromptText("Starting word (e.g., the)");

        Button generateBtn = new Button("Generate Sentence");
        generateBtn.setOnAction(e -> onGenerateSentence());

        generatedSentenceArea.setEditable(false);
        generatedSentenceArea.setWrapText(true);

        HBox row = new HBox(10,
                new Label("Start:"), startWordField,
                new Label("Algorithm:"), algorithmChoice,
                new Label("Max words:"), maxWordsSpinner,
                generateBtn
        );
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(startWordField, Priority.ALWAYS);

        VBox layout = new VBox(12,
                new Label("Sentence generation (2 algorithms required by project)"),
                row,
                generatedSentenceArea
        );
        layout.setPadding(new Insets(12));
        VBox.setVgrow(generatedSentenceArea, Priority.ALWAYS);

        Tab tab = new Tab("Generate", layout);
        tab.setClosable(false);
        return tab;
    }

    private void onGenerateSentence() {
        String start = startWordField.getText();
        if (start == null || start.isBlank()) {
            generatedSentenceArea.setText("Please enter a start word.");
            return;
        }

        try {
            String sentence = DBHelper.generateSentence(start, maxWordsSpinner.getValue(), algorithmChoice.getValue());
            if (sentence.isBlank()) {
                generatedSentenceArea.setText("No sentence generated (word may have no followers).\nTry another start word.");
                return;
            }

            generatedSentenceArea.setText(sentence);
            refreshReports();
        } catch (Exception ex) {
            generatedSentenceArea.setText("Generate failed: " + ex.getMessage());
        }
    }

    private Tab createAutocompleteTab() {
        composerArea.setPromptText("Type a sentence. Suggestions appear only after space/comma.");
        composerArea.setWrapText(true);
        composerArea.textProperty().addListener((obs, oldV, newV) -> scheduleAutocompleteRefresh());
        composerArea.setPrefHeight(220);

        composerArea.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            String ch = e.getCharacter();
            if (" ".equals(ch) || ",".equals(ch)) {
                Platform.runLater(this::onDelimiterTyped);
            }
        });

        Button trainTypedBtn = new Button("Add Typed Sentence To Model");
        trainTypedBtn.setOnAction(e -> trainFromComposerText());
        initializePredictionBar();

        VBox layout = new VBox(10,
                new Label("Composer"),
                composerArea,
                new Label("Next word predictions"),
                predictionBar,
                trainTypedBtn,
                autocompleteStatus
        );
        layout.setPadding(new Insets(12));
        VBox.setVgrow(composerArea, Priority.ALWAYS);

        Tab tab = new Tab("Autocomplete", layout);
        tab.setClosable(false);
        return tab;
    }

    private void onDelimiterTyped() {
        loadSuggestionsForLastWord(true);
    }

    private void scheduleAutocompleteRefresh() {
        // Debounce typing so we do not hit the DB on every keystroke.
        autocompleteDebounce.stop();
        autocompleteDebounce.setOnFinished(e -> loadSuggestionsForLastWord(false));
        autocompleteDebounce.playFromStart();
    }

    private void loadSuggestionsForLastWord(boolean delimiterTriggered) {
        String text = composerArea.getText();
        List<String> words = DBHelper.extractWords(text);
        if (words.isEmpty()) {
            autocompleteStatus.setText("Type a word first.");
            return;
        }

        String contextWord = resolveContextWord(text, words, delimiterTriggered);
        if (contextWord == null || contextWord.isBlank()) {
            autocompleteStatus.setText("Type more words to get suggestions.");
            return;
        }

        if (delimiterTriggered) {
            DBHelper.addWordIfMissing(contextWord);
        }

        List<DBHelper.Suggestion> suggestions = DBHelper.getSuggestions(contextWord, 8);
        updatePredictionButtons(suggestions);
        autocompleteStatus.setText(suggestions.isEmpty()
                ? "No suggestions for '" + contextWord + "'."
                : "Suggestions loaded for '" + contextWord + "'.");
    }

    private String resolveContextWord(String text, List<String> words, boolean delimiterTriggered) {
        String trimmed = text == null ? "" : text.trim();
        boolean endsWithDelimiter = text != null && (text.endsWith(" ") || text.endsWith(","));

        if (delimiterTriggered || endsWithDelimiter) {
            // User finished a token; suggest followers for that completed token.
            return words.get(words.size() - 1);
        }

        if (words.size() >= 2) {
            // User is mid-word; use the previous completed token as context.
            return words.get(words.size() - 2);
        }

        // Single-word input without delimiter: only use it when fully typed.
        return trimmed.equals(words.get(0)) ? words.get(0) : null;
    }

    private void initializePredictionBar() {
        predictionBar.setAlignment(Pos.CENTER_LEFT);
        predictionBar.setPadding(new Insets(4, 0, 4, 0));
        predictionButtons.clear();
        predictionBar.getChildren().clear();

        // Fixed-size prediction strip similar to mobile keyboard next-word suggestions.
        for (int i = 0; i < 5; i++) {
            Button button = new Button();
            button.setVisible(false);
            button.setManaged(false);
            button.setFocusTraversable(false);
            button.setOnAction(e -> insertSuggestionWord(button.getText()));
            predictionButtons.add(button);
            predictionBar.getChildren().add(button);
        }
    }

    private void updatePredictionButtons(List<DBHelper.Suggestion> suggestions) {
        for (int i = 0; i < predictionButtons.size(); i++) {
            Button button = predictionButtons.get(i);
            if (i < suggestions.size()) {
                String word = suggestions.get(i).word();
                button.setText(word);
                button.setVisible(true);
                button.setManaged(true);
            } else {
                button.setVisible(false);
                button.setManaged(false);
            }
        }
    }

    private void insertSuggestionWord(String suggestionWord) {
        if (suggestionWord == null || suggestionWord.isBlank()) {
            autocompleteStatus.setText("No suggestion selected.");
            return;
        }
        String text = composerArea.getText();
        if (!text.endsWith(" ") && !text.endsWith(",") && !text.isEmpty()) {
            text += " ";
        }

        composerArea.setText(text + suggestionWord + " ");
        composerArea.positionCaret(composerArea.getText().length());
        autocompleteStatus.setText("Inserted: " + suggestionWord);
    }

    private void trainFromComposerText() {
        String text = composerArea.getText();
        if (text == null || text.isBlank()) {
            autocompleteStatus.setText("Nothing to train.");
            return;
        }

        try {
            DBHelper.ingestFreeText(text);
            autocompleteStatus.setText("Typed sentence added to model.");
            refreshReports();
        } catch (Exception ex) {
            autocompleteStatus.setText("Train failed: " + ex.getMessage());
        }
    }

    private Tab createReportsTab() {
        sortChoice.setValue(DBHelper.SortMode.ALPHABETICAL);
        Button refreshBtn = new Button("Refresh Reports");
        refreshBtn.setOnAction(e -> refreshReports());

        Button saveWordBtn = new Button("Save Word Stats");
        saveWordBtn.setOnAction(e -> onSaveWordStats());

        TableColumn<DBHelper.WordStats, String> wordCol = new TableColumn<>("Word");
        wordCol.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().word()));

        TableColumn<DBHelper.WordStats, Number> totalCol = new TableColumn<>("Total");
        totalCol.setCellValueFactory(data -> new ReadOnlyIntegerWrapper(data.getValue().totalCount()));

        TableColumn<DBHelper.WordStats, Number> startCol = new TableColumn<>("Start");
        startCol.setCellValueFactory(data -> new ReadOnlyIntegerWrapper(data.getValue().startCount()));

        TableColumn<DBHelper.WordStats, Number> endCol = new TableColumn<>("End");
        endCol.setCellValueFactory(data -> new ReadOnlyIntegerWrapper(data.getValue().endCount()));

        wordTable.getColumns().setAll(wordCol, totalCol, startCol, endCol);
        wordTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        wordTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, selected) -> {
            if (selected == null) {
                return;
            }
            editWordField.setText(selected.word());
            editTotalField.setText(String.valueOf(selected.totalCount()));
            editStartField.setText(String.valueOf(selected.startCount()));
            editEndField.setText(String.valueOf(selected.endCount()));
        });

        importedFilesArea.setEditable(false);
        generatedHistoryArea.setEditable(false);
        duplicateArea.setEditable(false);

        HBox wordActions = new HBox(10,
                new Label("Sort:"), sortChoice,
                refreshBtn,
                new Label("Word:"), editWordField,
                new Label("Total:"), editTotalField,
                new Label("Start:"), editStartField,
                new Label("End:"), editEndField,
                saveWordBtn
        );
        wordActions.setAlignment(Pos.CENTER_LEFT);

        GridPane lower = new GridPane();
        lower.setHgap(10);
        lower.setVgap(8);
        ColumnConstraints col = new ColumnConstraints();
        col.setPercentWidth(33.3);
        lower.getColumnConstraints().addAll(col, col, col);

        VBox importedBox = new VBox(5, new Label("Imported Files"), importedFilesArea);
        VBox historyBox = new VBox(5, new Label("Generated Sentences (latest 50)"), generatedHistoryArea);
        VBox dupBox = new VBox(5, new Label("Duplicate Generated Sentences"), duplicateArea);
        VBox.setVgrow(importedFilesArea, Priority.ALWAYS);
        VBox.setVgrow(generatedHistoryArea, Priority.ALWAYS);
        VBox.setVgrow(duplicateArea, Priority.ALWAYS);

        lower.add(importedBox, 0, 0);
        lower.add(historyBox, 1, 0);
        lower.add(dupBox, 2, 0);
        GridPane.setHgrow(importedBox, Priority.ALWAYS);
        GridPane.setHgrow(historyBox, Priority.ALWAYS);
        GridPane.setHgrow(dupBox, Priority.ALWAYS);
        GridPane.setVgrow(importedBox, Priority.ALWAYS);
        GridPane.setVgrow(historyBox, Priority.ALWAYS);
        GridPane.setVgrow(dupBox, Priority.ALWAYS);

        VBox layout = new VBox(10,
                new Label("Word Report + Generated Sentence Report"),
                wordActions,
                wordTable,
                lower
        );
        layout.setPadding(new Insets(12));
        VBox.setVgrow(wordTable, Priority.ALWAYS);
        VBox.setVgrow(lower, Priority.ALWAYS);

        Tab tab = new Tab("Reports", layout);
        tab.setClosable(false);
        return tab;
    }

    private void onSaveWordStats() {
        try {
            int total = Integer.parseInt(editTotalField.getText().trim());
            int start = Integer.parseInt(editStartField.getText().trim());
            int end = Integer.parseInt(editEndField.getText().trim());

            DBHelper.updateWordStats(editWordField.getText(), total, start, end);
            refreshReports();
        } catch (Exception ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "Save failed: " + ex.getMessage(), ButtonType.OK);
            alert.showAndWait();
        }
    }

    private void refreshReports() {
        try {
            List<DBHelper.WordStats> words = DBHelper.getAllWords(sortChoice.getValue());
            wordTable.setItems(FXCollections.observableArrayList(words));

            List<String> imports = DBHelper.getImportedFilesSummary(50);
            importedFilesArea.setText(String.join("\n", imports));

            List<DBHelper.GeneratedRecord> history = DBHelper.getGeneratedHistory(50);
            List<String> historyRows = history.stream()
                    .map(r -> String.format("%s | %s | %s", r.generatedAt(), r.algorithm(), r.sentence()))
                    .toList();
            generatedHistoryArea.setText(String.join("\n", historyRows));

            List<DBHelper.DuplicateRecord> duplicates = DBHelper.getDuplicateSentences();
            List<String> duplicateRows = duplicates.stream()
                    .map(d -> d.occurrences() + "x | " + d.sentence())
                    .toList();
            duplicateArea.setText(String.join("\n", duplicateRows));
        } catch (Exception ex) {
            importLogArea.appendText("Refresh error: " + ex.getMessage() + "\n");
        }
    }

    public static void main(String[] args) {
        launch();
    }
}
