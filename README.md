# Sentence Builder

Clean project layout for submission and Git upload.

## Main App
- Project directory: `autocomplete-master`
- Stack: Java 17, JavaFX 21, MySQL, Maven Wrapper

## What Is Included
- JavaFX UI + import / generation / autocomplete / reporting code
- Database integration (`word`, `word_follows`, `imported_file`, `generated_sentence`)
- Text datasets in `data/` for import testing (the Import tab can use `../data` when you run from `autocomplete-master`)

## Prerequisites
- **JDK 17** (or newer LTS that still runs Java 17 bytecode)
- **MySQL** reachable locally (or update `SB_DB_URL`)
- Create an empty database named **`sentence_builder`** (or match your URL). The app creates tables on startup via `DBHelper.initializeSchema()`.

## Database Connection Setup
- Connection code: `autocomplete-master/src/main/java/com/example/simple/DBConnection.java`
- Settings come from environment variables (do **not** commit real passwords in code or in this file):
  - `SB_DB_URL` — default `jdbc:mysql://127.0.0.1:3306/sentence_builder`
  - `SB_DB_USER` — default `root`
  - `SB_DB_PASSWORD` — default empty; set this for a passworded MySQL user

Optional connectivity check: in IntelliJ (or your IDE), run `com.example.simple.ConnectionCheck` with the same environment variables as the app.

## Run (JavaFX UI)

From `autocomplete-master`, shortest form when defaults match your machine (only set password):

```bash
cd autocomplete-master
SB_DB_PASSWORD='YOUR_PASSWORD' ./mvnw javafx:run
```

Full explicit example (equivalent to defaults above):

```bash
cd autocomplete-master
SB_DB_URL='jdbc:mysql://127.0.0.1:3306/sentence_builder' \
SB_DB_USER='root' \
SB_DB_PASSWORD='YOUR_PASSWORD' \
./mvnw javafx:run
```

Same goal using the fully qualified plugin id (either works):

```bash
./mvnw org.openjfx:javafx-maven-plugin:0.0.8:run
```

**IntelliJ:** Run Configuration → Environment variables → add `SB_DB_PASSWORD` (and others if needed), then run `Main` or a Maven `javafx:run` goal.

**Windows:** use `mvnw.cmd` instead of `./mvnw`.
