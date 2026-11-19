# Giving FastTrack a language model (free)

FastTrack works without one: email classification, resume parsing and job
posting parsing all fall back to deterministic rule-based paths, and the UI
says plainly that AI classification is not configured. A model improves the
hard cases - the measured difference is in [RESULTS.md](../RESULTS.md).

Any OpenAI-compatible endpoint works. Two free options:

## Option A - Groq (free tier, hosted, fastest to set up)

1. Sign up at <https://console.groq.com/>.
2. Go to **API Keys → Create API Key**, copy it.
3. In `.env`:

   ```bash
   OPENAI_BASE_URL=https://api.groq.com/openai/v1
   OPENAI_API_KEY=<your key>
   LLM_MODEL=llama-3.3-70b-versatile
   ```

Groq's free tier is rate limited per minute and per day. FastTrack is built
for that: it caches every classification by content hash, rate limits per
user, and falls back to rules on a 429 rather than failing.

## Option B - Ollama (local, nothing leaves your machine)

```bash
brew install ollama        # or https://ollama.com/download
ollama serve
ollama pull llama3.1:8b
```

```bash
OPENAI_BASE_URL=http://localhost:11434/v1
OPENAI_API_KEY=ollama
LLM_MODEL=llama3.1:8b
```

Slower on a laptop, and a small model is less accurate on the tricky cases,
but no email content ever leaves the machine.

## Checking it worked

Restart the backend, then:

```bash
curl -s http://localhost:8080/api/config
```

`"llmConfigured": true` means the key is being read. The Settings page shows
the same thing in words.

To measure what the model actually buys you on the 200-email evaluation set:

```bash
cd backend && mvn -B -Peval test     # writes eval/results/classifier-llm.md
```
