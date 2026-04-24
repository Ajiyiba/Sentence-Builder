# Sentence Builder

Clean project layout for submission and Git upload.

## Main App
- Project directory: `autocomplete-master`
- Stack: Java 17, JavaFX, MySQL, Maven Wrapper

## What Is Included
- JavaFX UI + import/generation/autocomplete/reporting code
- Database integration (`word`, `word_follows`, `imported_file`, `generated_sentence`)
- Text datasets in `data/` for import testing

## Database Connection Setup
- Connection code location: `autocomplete-master/src/main/java/com/example/simple/DBConnection.java`
- The app reads DB settings from environment variables:
  - `SB_DB_URL`
  - `SB_DB_USER`
  - `SB_DB_PASSWORD`
- Password input: set `SB_DB_PASSWORD` when you run the app (terminal command below), or add the same env vars in IntelliJ Run Configuration.
- If env vars are not set, defaults are:
  - URL: `jdbc:mysql://127.0.0.1:3306/sentence_builder`
  - User: `root`
  - Password: empty string (`""`)

## Run
```bash
cd autocomplete-master
SB_DB_URL='jdbc:mysql://127.0.0.1:3306/sentence_builder' \
SB_DB_USER='root' \
SB_DB_PASSWORD='YOUR_PASSWORD' \
./mvnw org.openjfx:javafx-maven-plugin:0.0.8:run
```
