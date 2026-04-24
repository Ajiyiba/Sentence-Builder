# Sentence Builder

Clean project layout for submission and Git upload.

## Main App
- Project directory: `autocomplete-master`
- Stack: Java 17, JavaFX, MySQL, Maven Wrapper

## What Is Included
- JavaFX UI + import/generation/autocomplete/reporting code
- Database integration (`word`, `word_follows`, `imported_file`, `generated_sentence`)
- Text datasets in `data/` for import testing

## Run
```bash
cd autocomplete-master
SB_DB_URL='jdbc:mysql://127.0.0.1:3306/sentence_builder' \
SB_DB_USER='root' \
SB_DB_PASSWORD='YOUR_PASSWORD' \
./mvnw org.openjfx:javafx-maven-plugin:0.0.8:run
```
