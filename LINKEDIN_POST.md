---
🚀 Just shipped CodeLens — an AI-powered semantic code review engine.

The problem: ESLint and pylint catch syntax errors. But they miss:
❌ N+1 database query loops inside controllers
❌ God classes with 15 unrelated responsibilities
❌ Hardcoded API keys inside business logic branches

My solution: fine-tuned microsoft/codebert-base on 50,000+ real GitHub PR review comments to detect 6 categories of architectural anti-patterns.

Results:
✅ Macro-F1: 0.75 (vs 0.61 for GPT-4o zero-shot on same test set)
✅ 14-point improvement over prompting a large model
✅ Sub-200ms inference latency

What I built:
🔧 Java 21 + Spring Boot 3.3 — GitHub OAuth, HMAC-verified webhooks, JWT auth
🐍 Python FastAPI — CodeBERT inference with sliding-window tokenization
⚛️ Next.js 15 — PR diff viewer with inline anti-pattern annotations
🔌 VS Code extension — squiggly underlines on save
🤖 GitHub Actions bot — auto-comments on every PR

Tech stack: Java · Python · TypeScript · Spring Boot · FastAPI · PostgreSQL · Redis · HuggingFace · Docker

137 commits. 20 GitHub issues. 8 weeks of engineering.

→ GitHub: github.com/tanmay-alpha/codelens

#MachineLearning #SoftwareEngineering #OpenSource #Java #Python #NLP #DeveloperTools
---
