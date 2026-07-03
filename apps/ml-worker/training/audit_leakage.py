import json
import hashlib
from pathlib import Path

def check_leakage():
    data_dir = Path("training/data")
    
    # Load all three splits
    train_raw = json.loads((data_dir / "train.json").read_text()) if (data_dir / "train.json").exists() else []
    val_raw = json.loads((data_dir / "val.json").read_text()) if (data_dir / "val.json").exists() else []
    test_raw = json.loads((data_dir / "test.json").read_text()) if (data_dir / "test.json").exists() else []
    
    # Unwrap data if wrapped in warning dictionary
    train_data = train_raw["data"] if isinstance(train_raw, dict) and "data" in train_raw else train_raw
    val_data = val_raw["data"] if isinstance(val_raw, dict) and "data" in val_raw else val_raw
    test_data = test_raw["data"] if isinstance(test_raw, dict) and "data" in test_raw else test_raw
    
    print(f"Train samples: {len(train_data)}")
    print(f"Val samples: {len(val_data)}")
    print(f"Test samples: {len(test_data)}")
    
    if not train_data:
        print("WARNING: No split data found. Run split.py first to generate splits.")
        return
    
    # Hash each sample's diff content
    def hash_sample(s):
        if isinstance(s, dict):
            content = s.get("diff", "") + s.get("comment", "")
        else:
            content = str(s)
        return hashlib.md5(content.encode()).hexdigest()
    
    train_hashes = set(hash_sample(s) for s in train_data)
    val_hashes = set(hash_sample(s) for s in val_data)
    test_hashes = set(hash_sample(s) for s in test_data)
    
    train_val_overlap = train_hashes & val_hashes
    train_test_overlap = train_hashes & test_hashes
    val_test_overlap = val_hashes & test_hashes
    
    print(f"\nLeakage Analysis:")
    print(f"Train-Val overlap: {len(train_val_overlap)} samples ({len(train_val_overlap)/max(len(val_data),1)*100:.1f}% of val)")
    print(f"Train-Test overlap: {len(train_test_overlap)} samples ({len(train_test_overlap)/max(len(test_data),1)*100:.1f}% of test)")
    print(f"Val-Test overlap: {len(val_test_overlap)} samples ({len(val_test_overlap)/max(len(test_data),1)*100:.1f}% of test)")
    
    if train_test_overlap:
        print("CRITICAL: Train-test leakage detected! Model metrics are invalid.")
        print("Fix: Regenerate splits ensuring no overlap. Run split.py with PR-level splitting.")
    else:
        print("PASS: No train-test leakage detected.")
    
    # Check label distribution across splits
    if train_data and isinstance(train_data[0], dict) and "labels" in train_data[0]:
        categories = ["SECURITY", "PERFORMANCE", "ARCHITECTURE", "RELIABILITY", "READABILITY", "MAINTAINABILITY"]
        for split_name, split_data in [("Train", train_data), ("Val", val_data), ("Test", test_data)]:
            labels = [s["labels"] for s in split_data if "labels" in s]
            if labels:
                counts = [sum(l[i] for l in labels) for i in range(len(categories))]
                print(f"\n{split_name} label distribution:")
                for cat, count in zip(categories, counts):
                    print(f"  {cat}: {count} ({count/len(labels)*100:.1f}%)")

if __name__ == "__main__":
    check_leakage()
