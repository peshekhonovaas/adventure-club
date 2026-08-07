# AI Adventure Club

A multi-agent AI system that acts as a personalised **Game Master** for children aged 6–12.
It tells an ever-evolving, interest-driven story, keeps everything child-safe, and grows a single
"living picture" of the adventure from the child's own drawings.

Built with **Spring Boot 3.4**, **Spring AI 1.0**, **Java 21**, and **Postgres**. The language model
is **Google Gemini**, reached through its OpenAI-compatible API via Spring AI's OpenAI client — a free
Google AI Studio key today, swappable to a paid Vertex AI account by configuration alone.

> Work in progress — phase 1 (Story Director + input/output safety gates + multi-turn memory +
> illustrator) exists today. Phases 2 (Education Coach) and 3 (Creativity Coach) are on the roadmap.

## What it does

- **Personalised storytelling.** The child picks their interests (e.g. "dragons, space, Pokémon") and
  a Game Master name; the Story Director spins the next beat of an adventure from the whole
  conversation history.
- **Child-safety first.** Two independent safety gates wrap every turn: an **input gate** classifies the
  child's message before the story agent ever sees it, and an **output gate** checks the story response
  before it reaches the child, replacing anything unsafe with a warm fallback. Blocked messages are
  never persisted.
- **A living picture.** When the child uploads a drawing, the **Illustrator** paints that new element
  into one continuously growing picture of the adventure (image-to-image editing), kept server-side so
  it evolves turn after turn. A single-level **undo** lets the child step back to the previous picture.
- **Multi-turn memory.** Every turn loads the full conversation history for the session, so the story
  stays coherent.

## Architecture

The request flow is a deterministic pipeline, not an LLM router:

```
[child message]
     ↓
1. Resolve / create session
2. Input safety gate      ← blocks unsafe child messages
3. Education Coach         ← (roadmap) RAG retrieval from a knowledge base
4. Load conversation history
4b. Illustrator            ← grows the living picture from an uploaded drawing (best-effort)
5. Story Director          ← generates the next story beat (text only)
6. Output safety gate      ← blocks unsafe story responses
7. Persist both messages
     ↓
[child sees response + picture]
```

Key components:

- **`SessionController`** — the HTTP surface: `POST /session/turn` (advance the story) and
  `POST /session/undo` (step back one picture). The React frontend is served at `/` on the same port.
- **`Orchestrator`** — plain-Java "brain" that runs the pipeline above and owns session/message
  persistence. This is where future agents get wired in.
- **`InputSafetyGate` / `OutputSafetyGate`** — separate `ChatClient` classifications kept deliberately
  apart from the story agent so a jailbreak that fools the story cannot also disable safety.
- **`StoryDirectorAgent`** — the Game Master; builds a system prompt from interests + agent name and
  replays the whole history to the LLM. Text only by design.
- **`IllustratorAgent`** — grows one living picture by image-to-image editing; best-effort, so a turn
  never fails for lack of a picture.

Persistence uses `Session` and `Message` JPA entities backed by Postgres. The schema is owned by
**Flyway** (`src/main/resources/db/migration/V*.sql`); Hibernate is `ddl-auto: validate` only — schema
changes go in a new migration file.

## Getting started

### Prerequisites

- Java 21
- A running Postgres instance
- A Gemini API key (free from Google AI Studio)
- A Cloudflare account id + Workers AI API token (from https://developers.cloudflare.com/workers-ai/ — required for the Illustrator's image generation and editing)

### Environment variables

Required: `GEMINI_API_KEY`, `CLOUDFLARE_ACCOUNT_ID`, `CLOUDFLARE_API_TOKEN`, `DB_USERNAME`, `DB_PASSWORD`.
`CLOUDFLARE_ACCOUNT_ID` and `CLOUDFLARE_API_TOKEN` are a Cloudflare account id and Workers AI
API token (from https://developers.cloudflare.com/workers-ai/); the Illustrator's image
generation and image-to-image editing need them, and without them illustration fails.
Optional: `DB_HOST` / `DB_PORT` / `DB_NAME`, `GEMINI_MODEL` (default `gemini-2.5-flash`),
`GEMINI_BASE_URL`, and the Cloudflare knobs `CLOUDFLARE_IMAGE_MODEL` (default
`@cf/black-forest-labs/flux-2-klein-4b`) / `CLOUDFLARE_IMAGE_EDIT_MODEL` (default
`@cf/black-forest-labs/flux-2-klein-4b`) / `CLOUDFLARE_BASE_URL`
(or `IMAGES_ENABLED=false` to disable pictures). For a paid Vertex AI account later, point
`GEMINI_BASE_URL` at the Vertex OpenAI endpoint and use a `gcloud` access token as `GEMINI_API_KEY` — no code change.

### Run

```bash
# Run the backend (needs Postgres + env vars above)
./mvnw spring-boot:run

# Build the React frontend — Vite emits into src/main/resources/static/,
# so the app is then served at http://localhost:8080/
(cd frontend && npm install && npm run build)

# Frontend dev server with hot reload (proxies /session to :8080)
(cd frontend && npm run dev)

# Full stack (frontend build + app + Postgres) via Docker
docker compose up --build
```

### Test

```bash
# Note: some tests make real Gemini API calls and boot the full context,
# so they need Postgres + GEMINI_API_KEY.
./mvnw test

# A single test class / method
./mvnw test -Dtest=StoryDirectorAgentTest
./mvnw test -Dtest=StoryDirectorAgentTest#safetyGate_blocksObviouslyUnsafeContent
```

## Roadmap

- **Phase 2 — Education Coach:** RAG retrieval that enriches the story with age-appropriate learning.
- **Phase 3 — Creativity Coach:** structured feedback on the child's drawings and ideas.
