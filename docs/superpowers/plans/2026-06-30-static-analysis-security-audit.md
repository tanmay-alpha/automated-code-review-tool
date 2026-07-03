# CodeLens Static Analysis Security Audit — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Perform a comprehensive pre-production static analysis security audit across all 5 CodeLens application layers (ml-worker/Python, api/Java, web/Next.js, vscode-ext/TypeScript, github-action/Node.js) and generate a triaged master report.

**Architecture:** Multi-layer audit using specialized tools per language/runtime:
- Python (ml-worker): bandit, safety, mypy, pylint, radon
- Java (api): OWASP dependency-check, SpotBugs+FindSecBugs, maven dependency:analyze
- JavaScript/TypeScript (web, vscode-ext, github-action): npm audit
- All layers: detect-secrets + grep for common secret patterns

**Tech Stack:** bandit 1.7.9, safety 3.2.4, pylint 3.2.6, mypy 1.10.0, coverage 7.5.4, radon 6.0.1, detect-secrets 1.5.0, OWASP dependency-check-maven 9.2.0, SpotBugs 4.8.5.0, findsecbugs 1.13.0

## Global Constraints

- Do NOT modify requirements.txt or package.json when installing tools for scanning (tools are dev-only, not production deps)
- All CRITICAL and HIGH findings must be fixed in this session
- All findings must be triaged: CRITICAL, HIGH, MEDIUM, LOW
- Report output: `security_reports/` directory at repo root
- Commit all reports and fixes before final push

---

## SECTION 1: Python Static Analysis (ml-worker)

### Task 1.1: Install Python audit tools

- [ ] **Step 1:** Install tools (no requirements.txt modification)

```bash
pip install bandit==1.7.9 safety==3.2.4 pylint==3.2.6 mypy==1.10.0 coverage==7.5.4 radon==6.0.1 detect-secrets==1.5.0
```

### Task 1.2: Bandit security scan

- [ ] **Step 1:** Run bandit on ml-worker app and training dirs

```bash
cd C:/Users/TANMAY/OneDrive/Desktop/codelens
bandit -r apps/ml-worker/app/ -f txt -l -i > security_reports/bandit_report.txt 2>&1
bandit -r apps/ml-worker/training/ -f txt -l -i >> security_reports/bandit_report.txt 2>&1
```

- [ ] **Step 2:** Parse output — for every B104, B105, B106, B107 (hardcoded secrets), B201, B602, B603: document in CRITICAL_findings.md and fix immediately if in production code
- [ ] **Step 3:** For B311 (random not cryptographically secure): check if security-sensitive; fix if yes

### Task 1.3: Safety CVE scan

- [ ] **Step 1:** Run safety on both requirements files

```bash
cd C:/Users/TANMAY/OneDrive/Desktop/codelens
safety check -r apps/ml-worker/requirements.txt --json > security_reports/safety_python.json 2>&1
safety check -r apps/ml-worker/requirements-train.txt --json >> security_reports/safety_python.json 2>&1
```

- [ ] **Step 2:** Parse JSON — CRITICAL/HIGH: update package version in requirements.txt, run pytest after each
- [ ] **Step 3:** MEDIUM: document in MEDIUM_findings.md

### Task 1.4: MyPy type checking

- [ ] **Step 1:** Run mypy on ml-worker app dir

```bash
cd C:/Users/TANMAY/OneDrive/Desktop/codelens/apps/ml-worker
mypy app/ --ignore-missing-imports --strict --html-report ../security_reports/mypy_html > ../security_reports/mypy_report.txt 2>&1
```

- [ ] **Step 2:** Fix all "Argument", "Return", "Incompatible types" errors immediately
- [ ] **Step 3:** Add type hints to all public functions missing them

### Task 1.5: Pylint code quality

- [ ] **Step 1:** Run pylint

```bash
cd C:/Users/TANMAY/OneDrive/Desktop/codelens
pylint apps/ml-worker/app/ --disable=C,R --enable=W,E --output-format=json > security_reports/pylint_report.json 2>&1
```

- [ ] **Step 2:** Fix all E1101 (no member), W0611 (unused import), W0612 (unused variable), E0401 (import error)
- [ ] **Step 3:** Document all W0703 (broad exception caught) in findings

### Task 1.6: Cyclomatic complexity

- [ ] **Step 1:** Run radon

```bash
cd C:/Users/TANMAY/OneDrive/Desktop/codelens
radon cc apps/ml-worker/app/ -s -n C > security_reports/complexity_report.txt 2>&1
```

- [ ] **Step 2:** Refactor any function with complexity D, E, or F (score > 10) to complexity ≤ C (score ≤ 10)

---

## SECTION 2: Java Static Analysis (api)

### Task 2.1: OWASP Dependency Check

- [ ] **Step 1:** Add plugin to pom.xml if not present

```xml
<plugin>
  <groupId>org.owasp</groupId>
  <artifactId>dependency-check-maven</artifactId>
  <version>9.2.0</version>
  <configuration>
    <outputDirectory>../../security_reports</outputDirectory>
    <format>JSON</format>
    <failBuildOnCVSS>7</failBuildOnCVSS>
  </configuration>
</plugin>
```

- [ ] **Step 2:** Run dependency-check

```bash
cd C:/Users/TANMAY/OneDrive/Desktop/codelens/apps/api
mvn org.owasp:dependency-check-maven:check 2>&1 | tail -20
```

- [ ] **Step 3:** For any CVE with CVSS ≥ 7.0: update dependency in pom.xml to patched version

### Task 2.2: SpotBugs + FindSecBugs

- [ ] **Step 1:** Add plugin to pom.xml

```xml
<plugin>
  <groupId>com.github.spotbugs</groupId>
  <artifactId>spotbugs-maven-plugin</artifactId>
  <version>4.8.5.0</version>
  <configuration>
    <effort>Max</effort>
    <threshold>Low</threshold>
    <xmlOutput>true</xmlOutput>
    <xmlOutputDirectory>../../security_reports</xmlOutputDirectory>
    <plugins>
      <plugin>
        <groupId>com.h3xstream.findsecbugs</groupId>
        <artifactId>findsecbugs-plugin</artifactId>
        <version>1.13.0</version>
      </plugin>
    </plugins>
  </configuration>
</plugin>
```

- [ ] **Step 2:** Run spotbugs

```bash
mvn spotbugs:check 2>&1 > security_reports/spotbugs_report.txt
```

- [ ] **Step 3:** Fix FindSecBugs patterns: PREDICTABLE_RANDOM, SQL_INJECTION, HARD_CODE_PASSWORD, WEAK_CRYPTOGRAPHY, COOKIE_NO_HTTPONLY, XSS_REQUEST_PARAMETER

### Task 2.3: Maven dependency audit

- [ ] **Step 1:** Run dependency:analyze

```bash
mvn dependency:analyze 2>&1 > security_reports/maven_deps.txt
```

- [ ] **Step 2:** Fix all "Used undeclared dependencies" (add explicit dep)
- [ ] **Step 3:** Remove "Declared but unused dependencies" to reduce attack surface

---

## SECTION 3: Frontend Security Analysis

### Task 3.1: Web npm audit

- [ ] **Step 1:** Run npm audit

```bash
cd C:/Users/TANMAY/OneDrive/Desktop/codelens/apps/web
npm audit --json > ../security_reports/npm_audit_web.json 2>&1
```

- [ ] **Step 2:** For critical/high: run `npm audit fix` and verify no breaking changes
- [ ] **Step 3:** For moderate: document in findings
- [ ] **Step 4:** Run `npm ci` after fixes, then `npx tsc --noEmit`

### Task 3.2: VSCode extension npm audit

- [ ] **Step 1:** Run npm audit

```bash
cd C:/Users/TANMAY/OneDrive/Desktop/codelens/apps/vscode-ext
npm audit --json > ../security_reports/npm_audit_ext.json 2>&1
```

- [ ] **Step 2:** Same triage process as Task 3.1

### Task 3.3: GitHub Action npm audit

- [ ] **Step 1:** Run npm audit

```bash
cd C:/Users/TANMAY/OneDrive/Desktop/codelens/github-action
npm audit --json > ../security_reports/npm_audit_action.json 2>&1
```

- [ ] **Step 2:** Same triage process as Task 3.1

---

## SECTION 4: Secrets Scanning

### Task 4.1: detect-secrets scan

- [ ] **Step 1:** Run detect-secrets

```bash
cd C:/Users/TANMAY/OneDrive/Desktop/codelens
detect-secrets scan --all-files > security_reports/secrets_scan.json 2>&1
```

- [ ] **Step 2:** Review every finding — real secrets: git revert + rotate; false positives: add to .secrets.baseline

### Task 4.2: Manual grep patterns

- [ ] **Step 1:** Run grep commands for common secret patterns

```bash
grep -r "sk-" . --include="*.py" --include="*.java" --include="*.ts" --include="*.js" -l
grep -r "ghp_" . --include="*.py" --include="*.java" --include="*.ts" --include="*.js" -l
grep -r "password\s*=\s*['\"]" . --include="*.py" --include="*.java" -i -l
grep -r "secret\s*=\s*['\"][^{]" . --include="*.py" --include="*.java" -i -l
grep -r "api_key\s*=\s*['\"]" . --include="*.py" --include="*.java" -i -l
```

- [ ] **Step 2:** Any matches in non-test files: investigate and fix immediately

---

## SECTION 5: Master Report Generation

### Task 5.1: Create MASTER_AUDIT_REPORT.md

- [ ] **Step 1:** Create `security_reports/CRITICAL_findings.md`
- [ ] **Step 2:** Create `security_reports/MEDIUM_findings.md`
- [ ] **Step 3:** Create `security_reports/MASTER_AUDIT_REPORT.md` with all 7 sections

---

## SECTION 6: Commits

### Task 6.1: Commit and push

- [ ] **Step 1:** Commit all reports

```bash
git add security_reports/
git commit -m "security: add static analysis reports — bandit, safety, mypy, spotbugs, npm audit"
```

- [ ] **Step 2:** Commit each fix separately

```bash
git add [fixed files]
git commit -m "fix(security): [specific fix description]"
```

- [ ] **Step 3:** Push

```bash
git push origin main
```

- [ ] **Step 4:** Print final executive summary from MASTER_AUDIT_REPORT.md
