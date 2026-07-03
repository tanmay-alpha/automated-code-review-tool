import httpx
import time
import statistics
import sys

ML_BASE = "http://localhost:8000"
SECRET = "localtestsecret123"

# Test payloads of different sizes
payloads = {
    "small_10_lines": "+def f():\n" * 10,
    "medium_50_lines": "+x = db.query(user.id)\n" * 50,
    "large_200_lines": "+for u in users:\n+    db.execute(q)\n" * 100,
    "xlarge_500_lines": "+code here\n" * 500,
}

results = {}
for size_name, diff in payloads.items():
    latencies = []
    print(f"Benchmarking {size_name}...")
    for run in range(10):  # 10 runs each
        start = time.perf_counter()
        try:
            r = httpx.post(f"{ML_BASE}/ml/review",
                json={"diff": diff, "language": "python", "mode": "diff"},
                headers={"X-ML-Worker-Secret": SECRET},
                timeout=60
            )
            latency_ms = (time.perf_counter() - start) * 1000
            if r.status_code == 200:
                latencies.append(latency_ms)
            else:
                print(f"  Run {run} failed with status {r.status_code}")
        except Exception as e:
            print(f"  Run {run} failed with exception: {e}")
    
    if latencies:
        results[size_name] = {
            "mean_ms": statistics.mean(latencies),
            "median_ms": statistics.median(latencies),
            "p95_ms": sorted(latencies)[int(0.95 * len(latencies))],
            "min_ms": min(latencies),
            "max_ms": max(latencies),
        }
        for k, v in results[size_name].items():
            print(f"  {k}: {v:.1f}")
    else:
        print(f"  No successful runs for {size_name}")

# SLA check
sla_limits = {"small_10_lines": 500, "medium_50_lines": 1000, "large_200_lines": 5000, "xlarge_500_lines": 30000}
print("\nSLA Compliance:")
for size, limit in sla_limits.items():
    if size in results:
        actual = results[size]["p95_ms"]
        status = "PASS" if actual < limit else "FAIL"
        print(f"  {size}: P95={actual:.0f}ms vs limit {limit}ms -> {status}")
