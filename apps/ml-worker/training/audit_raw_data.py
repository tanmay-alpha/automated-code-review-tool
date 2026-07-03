import json
import sys
from pathlib import Path
from statistics import mean, median

def audit_raw_data():
    raw_dir = Path("training/data/raw")
    if not raw_dir.exists():
        print("ERROR: Raw data directory not found.")
        return
        
    json_files = sorted(raw_dir.glob("*.json"))
    if not json_files:
        print("ERROR: No JSON files found in raw directory.")
        return
        
    raw_samples = []
    for fp in json_files:
        with fp.open("r", encoding="utf-8") as f:
            try:
                data = json.load(f)
                if isinstance(data, list):
                    raw_samples.extend(data)
                elif isinstance(data, dict):
                    for pr_id, samples in data.items():
                        if isinstance(samples, list):
                            raw_samples.extend(samples)
            except Exception as e:
                continue
                
    total_raw = len(raw_samples)
    print(f"Total raw samples loaded: {total_raw}")
    
    # 1. Total samples check
    # Note: 50K+ is expected for the production dataset.
    print(f"\n1. Expected Count Check:")
    if total_raw >= 50000:
        print(f"   PASS: Raw dataset has {total_raw} samples (>= 50K).")
    else:
        print(f"   WARNING: Auditing with a subset/mock dataset of {total_raw} samples. (Production requires 50K+)")
        
    # 2. Empty diff check
    empty_diffs = sum(1 for s in raw_samples if not (s.get("diff") or "").strip())
    print(f"\n2. Empty Diff Check:")
    print(f"   Samples with empty diffs: {empty_diffs}")
    if empty_diffs == 0:
        print("   PASS: No empty diffs found.")
    else:
        print("   FAIL: Empty diffs detected in raw data.")
        
    # 3. Duplicate check
    seen_contents = set()
    duplicates = 0
    for s in raw_samples:
        content = (s.get("diff") or "") + "|||" + (s.get("comment") or "")
        if content in seen_contents:
            duplicates += 1
        seen_contents.add(content)
        
    print(f"\n3. Duplicate Check:")
    print(f"   Duplicate (diff + comment) pairs: {duplicates} ({duplicates / max(total_raw, 1) * 100:.1f}%)")
    
    # 4. Diff length distribution (using approximate whitespace-token count)
    lengths = []
    for s in raw_samples:
        diff = s.get("diff") or ""
        tokens_approx = len(diff.split())
        lengths.append(tokens_approx)
        
    bins = {"<100": 0, "100-256": 0, "256-512": 0, "512+": 0}
    for l in lengths:
        if l < 100:
            bins["<100"] += 1
        elif l <= 256:
            bins["100-256"] += 1
        elif l <= 512:
            bins["256-512"] += 1
        else:
            bins["512+"] += 1
            
    print(f"\n4. Diff Length Distribution (approx. tokens):")
    for k, v in bins.items():
        pct = v / max(total_raw, 1) * 100
        print(f"   {k:<10}: {v:>4} ({pct:.1f}%)")
        
    # 5. Language distribution in diffs
    # Simple heuristic check on diff extensions or keywords if file paths are absent,
    # or check file names. Let's inspect mock templates.
    languages = {"python": 0, "javascript": 0, "java": 0, "other": 0}
    for s in raw_samples:
        diff = (s.get("diff") or "").lower()
        # Heuristic rules
        if "def " in diff or "import " in diff and not "import java" in diff and not "require" in diff:
            languages["python"] += 1
        elif "const " in diff or "let " in diff or "function(" in diff:
            languages["javascript"] += 1
        elif "public class " in diff or "private class " in diff or "import java" in diff:
            languages["java"] += 1
        else:
            languages["other"] += 1
            
    print(f"\n5. Language Distribution:")
    for lang, count in languages.items():
        pct = count / max(total_raw, 1) * 100
        print(f"   {lang:<12}: {count:>4} ({pct:.1f}%)")
        
    # 6. Comment Quality
    comment_lens = [len((s.get("comment") or "").strip()) for s in raw_samples]
    avg_len = mean(comment_lens) if comment_lens else 0
    med_len = median(comment_lens) if comment_lens else 0
    above_50 = sum(1 for l in comment_lens if l > 50)
    above_50_pct = above_50 / max(total_raw, 1) * 100
    
    print(f"\n6. Comment Quality:")
    print(f"   Average comment length: {avg_len:.1f} chars")
    print(f"   Median comment length:  {med_len:.1f} chars")
    print(f"   Comments > 50 chars:    {above_50} ({above_50_pct:.1f}%)")

if __name__ == "__main__":
    audit_raw_data()
