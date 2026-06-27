# CodeLens Demo Script
## For interviews: 2-minute live demo walkthrough

### Setup (do before interview)
1. Start docker: `cd infra && docker compose up -d`
2. Wait 3 minutes for ML worker to load
3. Open http://localhost:3000 in browser
4. Have a test GitHub repo ready with bad code

### Demo flow (2 minutes)

**Minute 1: Show the problem**
- Open any Python file with a for loop + db.query inside it
- Say: "ESLint and pylint would not flag this. It's syntactically valid."
- Open terminal, run: curl command against /ml/review with this diff
- Show the JSON response: N+1 query detected, confidence 0.87

**Minute 2: Show the system**
- Open localhost:3000 (dashboard)
- Show the architecture: "Two backends — Java Spring Boot handles OAuth and webhooks, Python FastAPI handles model inference"
- Open GitHub repo, show the 137 commits and CI badges all green
- Say: "Fine-tuned CodeBERT outperforms GPT-4o zero-shot by 14 F1 points on the held-out test set"

### Interview questions you will be asked
1. "Why fine-tune instead of prompting GPT-4?" → F1 0.75 vs 0.61, cost, latency
2. "What is multi-label classification?" → One diff can have both security AND performance issues
3. "How does the webhook work?" → HMAC-SHA256 verification, @Async, return 200 immediately
4. "What is the sliding window?" → CodeBERT 512 token limit, stride 50, max-pool logits