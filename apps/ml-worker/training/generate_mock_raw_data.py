import json
import random
from pathlib import Path

# Taxonomy keywords for generating realistic comments
TAXONOMY_KEYWORDS = {
    "SECURITY": ["password", "secret", "api_key", "token", "hardcoded credentials in plaintext", "sql injection vulnerability"],
    "PERFORMANCE": ["n+1 query loop", "nested loop is very slow", "repeated call bottleneck", "optimize query cache"],
    "ARCHITECTURE": ["god class violates single responsibility", "circular dependency coupling", "layer separation design"],
    "RELIABILITY": ["bare except block catches everything", "null check validation exception", "resource leak unclosed"],
    "READABILITY": ["magic number with unclear meaning", "misleading variable naming", "readable document comments"],
    "MAINTAINABILITY": ["method is too long refactor needed", "deep nesting complex logic", "duplicate copy-paste code"]
}

DIFF_TEMPLATES = [
    "+def process_data(user):\n+    for u in users:\n+        posts = Post.where(user=u)\n+    return posts",
    "+password = 'cl_live_abc123xyz789'\n+api_key = 'sk-live-randomkey'",
    "+class LargeGodClass:\n+    def do_everything(self):\n+        pass\n+    def do_other_things(self):\n+        pass",
    "+try:\n+    do_something()\n+except:\n+    pass",
    "+const TIMEOUT = 86400;\n+let val = x * 4.25;",
    "+if (a) {\n+    if (b) {\n+        if (c) {\n+            if (d) {\n+                do_work();\n+            }\n+        }\n+    }\n+}"
]

def generate_mock_raw_data(num_prs=150, samples_per_pr=3, num_duplicates=5):
    # Resolve correct absolute path to training/data/raw
    script_dir = Path(__file__).resolve().parent
    raw_dir = script_dir / "data" / "raw"
    
    # Clean previous JSON files in raw_dir
    if raw_dir.exists():
        for f in raw_dir.glob("*.json"):
            f.unlink()
    else:
        raw_dir.mkdir(parents=True, exist_ok=True)
    
    categories = list(TAXONOMY_KEYWORDS.keys())
    generated_samples = []
    
    # 1. Generate regular PR files
    for i in range(num_prs):
        pr_id = f"pr_{10000 + i:05d}"
        pr_samples = []
        for j in range(samples_per_pr):
            cat = random.choice(categories)
            kw = random.choice(TAXONOMY_KEYWORDS[cat])
            comment = f"Please fix this: {kw}. This is crucial for codebase health."
            diff = random.choice(DIFF_TEMPLATES)
            
            sample = {
                "diff": diff,
                "comment": comment,
                "label": 1
            }
            pr_samples.append(sample)
            generated_samples.append(sample)
            
        output_file = raw_dir / f"{pr_id}.json"
        output_file.write_text(json.dumps(pr_samples, indent=2), encoding="utf-8")
        
    # 2. Generate explicit duplicate sample files under different PR IDs (to test leakage detection)
    for k in range(num_duplicates):
        src_sample = random.choice(generated_samples)
        dup_pr_id = f"pr_dup_{k:03d}"
        dup_samples = [{
            "diff": src_sample["diff"],
            "comment": src_sample["comment"],
            "label": 1
        }]
        output_file = raw_dir / f"{dup_pr_id}.json"
        output_file.write_text(json.dumps(dup_samples, indent=2), encoding="utf-8")
        
    print(f"Mock raw data files generated at: {raw_dir}")
    print(f"Generated {num_prs + num_duplicates} files.")

if __name__ == "__main__":
    generate_mock_raw_data()
