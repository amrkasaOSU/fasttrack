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
   LLM_MODEL=openai/gpt-oss-120b
   ```

Groq's free tier is rate limited per minute and per day. FastTrack is built
for that: it caches every classification by content hash, rate limits per
user, honours the provider's own `Retry-After` header, and falls back to
rules on a 429 rather than failing.

At the time of writing the free tier allows 8,000 tokens per minute and
200,000 per day. That daily figure matters if you run the evaluation
harness: a full 200-email run costs roughly 280,000 tokens, so use
`EVAL_SAMPLE` to measure a stratified slice that fits (see
[RESULTS.md](../RESULTS.md)).

**If you get a 404, the model was probably retired.** Hosted providers
rotate models out; the name in this file is correct as of the last commit
but will not be forever. List what your key can actually reach:

```bash
curl -s https://api.groq.com/openai/v1/models \
  -H "Authorization: Bearer $OPENAI_API_KEY" | python3 -m json.tool | grep '"id"'
```

Any OpenAI-compatible chat model that can follow a JSON schema will work -
put its name in `LLM_MODEL`.

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
./scripts/run-llm-eval.sh
```

That script reads the key from `.env` (so it never goes on a command line or
into a shell history), checks it reaches the provider before spending ten
minutes finding out it does not, throttles the calls so a free tier's rate
limit does not turn the run into a measurement of its own 429s, and prints
the LLM result next to the rule-based baseline.
