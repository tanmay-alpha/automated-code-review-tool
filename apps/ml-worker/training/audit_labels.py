import json
import sys
from pathlib import Path
from statistics import mean

# Local import — label_mapper.py lives next to this file.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from label_mapper import CATEGORIES, map_comment_to_labels

def check_label_quality():
    data_dir = Path("training/data")
    
    # Load splits
    train_raw = json.loads((data_dir / "train.json").read_text()) if (data_dir / "train.json").exists() else []
    val_raw = json.loads((data_dir / "val.json").read_text()) if (data_dir / "val.json").exists() else []
    test_raw = json.loads((data_dir / "test.json").read_text()) if (data_dir / "test.json").exists() else []
    
    train_data = train_raw["data"] if isinstance(train_raw, dict) and "data" in train_raw else train_raw
    val_data = val_raw["data"] if isinstance(val_raw, dict) and "data" in val_raw else val_raw
    test_data = test_raw["data"] if isinstance(test_raw, dict) and "data" in test_raw else test_raw
    
    all_data = train_data + val_data + test_data
    total_samples = len(all_data)
    print(f"Total labeled samples loaded for analysis: {total_samples}")
    
    if total_samples == 0:
        print("WARNING: No data found.")
        return
        
    # 1. Inter-rater reliability simulation (running mapper on 100 samples)
    import random
    random.seed(42)
    sample_subset = random.sample(all_data, min(100, total_samples))
    agreements = 0
    for s in sample_subset:
        predicted = map_comment_to_labels(s["comment"])
        if predicted == s["labels"]:
            agreements += 1
    agreement_rate = agreements / len(sample_subset) * 100
    print(f"\n1. Inter-rater Reliability Simulation:")
    print(f"   Agreement rate with mapper rules: {agreement_rate:.1f}%")
    if agreement_rate >= 80:
        print("   PASS: Agreement rate is above 80%.")
    else:
        print("   FAIL: Agreement rate is below 80%.")
        
    # 2. Label imbalance check
    print(f"\n2. Label Prevalence and Imbalance Check:")
    counts = {c: 0 for c in CATEGORIES}
    for s in all_data:
        for i, val in enumerate(s["labels"]):
            if val:
                counts[CATEGORIES[i]] += 1
                
    imbalance_flag = False
    for cat in CATEGORIES:
        count = counts[cat]
        prevalence = count / total_samples
        prevalence_pct = prevalence * 100
        print(f"   {cat:<18}: {count:>4} samples ({prevalence_pct:.1f}%)")
        if prevalence < 0.05:
            print(f"     [FLAG] Category {cat} prevalence ({prevalence_pct:.1f}%) < 5% - may have insufficient training data for this category.")
            imbalance_flag = True
        elif prevalence > 0.60:
            print(f"     [FLAG] Category {cat} prevalence ({prevalence_pct:.1f}%) > 60% - excessive imbalance.")
            imbalance_flag = True
            
    if not imbalance_flag:
        print("   PASS: All categories fall within the 5% - 60% prevalence range.")
    else:
        print("   WARNING: Some categories exhibit imbalance flags.")
        
    # 3. Empty label check
    empty_label_samples = sum(1 for s in all_data if sum(s["labels"]) == 0)
    print(f"\n3. Empty Label Check:")
    print(f"   Samples with 0 active labels: {empty_label_samples}")
    if empty_label_samples == 0:
        print("   PASS: No empty label samples present after split filtering.")
    else:
        print("   FAIL: Empty label samples detected (should have been filtered).")
        
    # 4. Multi-label statistics
    multi_label_counts = [sum(s["labels"]) for s in all_data]
    multi_label_pct = sum(1 for c in multi_label_counts if c >= 2) / total_samples * 100
    print(f"\n4. Multi-label Statistics:")
    print(f"   Avg labels per sample: {mean(multi_label_counts):.2f}")
    print(f"   Samples with 2+ labels: {multi_label_pct:.1f}%")

if __name__ == "__main__":
    check_label_quality()
