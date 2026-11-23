#!/usr/bin/env bash
# Measures the LLM classification path against the same 200-email set the
# rule-based baseline was measured on, and prints the comparison.
#
#   ./scripts/run-llm-eval.sh
#
# Reads OPENAI_API_KEY, OPENAI_BASE_URL and LLM_MODEL from .env, so the key
# never has to be typed on a command line or pasted into a chat. With no key
# set it stops and says so rather than quietly measuring the fallback.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ ! -f .env ]]; then
  echo "No .env found. Copy .env.example to .env and add your key." >&2
  exit 1
fi

# Only the three variables this needs, so nothing else in .env leaks in.
#
# Values are trimmed of surrounding whitespace and quotes before export. A key
# pasted into a file picks those up easily, and the failure is nasty: shell
# treats `KEY= value` as an empty assignment, while docker compose keeps the
# space and sends it as part of the Bearer token. Both look like a rejected
# key rather than a formatting slip.
read_env() {
  local name="$1" line value
  line=$(grep -E "^${name}=" .env | tail -1) || true
  [[ -z "$line" ]] && return 0
  value="${line#*=}"
  value="${value#"${value%%[![:space:]]*}"}"   # leading whitespace
  value="${value%"${value##*[![:space:]]}"}"   # trailing whitespace
  if [[ ${#value} -ge 2 && "${value:0:1}" == "${value: -1}" && "${value:0:1}" =~ ^[\"\']$ ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf '%s' "$value"
}

OPENAI_API_KEY="$(read_env OPENAI_API_KEY)"
OPENAI_BASE_URL="$(read_env OPENAI_BASE_URL)"
LLM_MODEL="$(read_env LLM_MODEL)"
export OPENAI_API_KEY OPENAI_BASE_URL LLM_MODEL

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  cat >&2 <<'MSG'
OPENAI_API_KEY is empty in .env, so there is nothing to measure.

  1. Create a free key at https://console.groq.com  (API Keys -> Create)
  2. Put it in .env:   OPENAI_API_KEY=gsk_...
  3. Run this again.

See docs/llm-setup.md. Ollama works too - point OPENAI_BASE_URL at it.
MSG
  exit 1
fi

BASE_URL="${OPENAI_BASE_URL:-https://api.groq.com/openai/v1}"
MODEL="${LLM_MODEL:-llama-3.3-70b-versatile}"

echo "Checking the key reaches ${BASE_URL} ..."
probe=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 \
  -X POST "${BASE_URL}/chat/completions" \
  -H "Authorization: Bearer ${OPENAI_API_KEY}" \
  -H "Content-Type: application/json" \
  --data "{\"model\":\"${MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"reply with the single word ok\"}],\"max_tokens\":5}")

case "$probe" in
  200) echo "  key works, model ${MODEL} responded." ;;
  401|403) echo "  HTTP ${probe}: the key was rejected. Check it was copied whole." >&2; exit 1 ;;
  404) echo "  HTTP 404: ${MODEL} is not available on this account. Try another LLM_MODEL." >&2; exit 1 ;;
  429) echo "  HTTP 429: already rate limited. Wait a minute and retry." >&2; exit 1 ;;
  *) echo "  HTTP ${probe} from ${BASE_URL}. Check OPENAI_BASE_URL." >&2; exit 1 ;;
esac

# Free tiers limit requests per minute. Spacing the calls out keeps the run
# measuring the model rather than its own 429s.
export EVAL_THROTTLE_MS="${EVAL_THROTTLE_MS:-1200}"
echo "Throttling to one call every ${EVAL_THROTTLE_MS} ms (set EVAL_THROTTLE_MS to change)."
echo "200 emails, roughly $(( 200 * ${EVAL_THROTTLE_MS} / 1000 / 60 + 2 )) minutes."
echo

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21)}" \
  mvn -B -f backend/pom.xml -Peval test -Dtest=ClassifierEvaluationTest

echo
echo "=============================================================="
echo " LLM path      -> eval/results/classifier-llm.md"
echo " Rule baseline -> eval/results/classifier-rules.md"
echo "=============================================================="
grep -E '^\- Overall accuracy|^\- Macro F1|^\- Accuracy on|^\- Fell back' \
  eval/results/classifier-llm.md 2>/dev/null || true
