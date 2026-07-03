import psutil
import time
import subprocess
import requests
import sys

print("Monitoring resource usage during sustained load...")
print("Time | CPU% | Memory MB | ML Worker Memory MB")

# Run 5 minutes of monitoring while sending requests
start_time = time.time()
while time.time() - start_time < 300:  # 5 minutes
    # Send a request to keep the system active
    try:
        r = requests.post("http://localhost:8000/ml/review",
            json={"diff": "+for u in users:\n+    db.query(u)\n" * 20, "language": "python", "mode": "diff"},
            headers={"X-ML-Worker-Secret": "localtestsecret123"},
            timeout=30
        )
    except Exception as e:
        print(f"Request failed: {e}")
        pass
    
    # Monitor system resources
    cpu = psutil.cpu_percent(interval=1)
    mem = psutil.virtual_memory().used / 1024 / 1024
    
    # Get Docker container memory
    result = subprocess.run(
        ["docker", "stats", "--no-stream", "--format", "{{.Name}}:{{.MemUsage}}"],
        capture_output=True, text=True, shell=True
    )
    
    elapsed = int(time.time() - start_time)
    print(f"{elapsed}s | CPU:{cpu:.1f}% | System RAM:{mem:.0f}MB")
    
    found_worker = False
    for line in result.stdout.strip().split("\n"):
        if "ml-worker" in line:
            print(f"  ML Worker container: {line}")
            found_worker = True
            
    if not found_worker:
        print(f"  [WARNING] ML Worker container not found in docker stats. Output: {result.stdout.strip()}")
    
    sys.stdout.flush()
    time.sleep(30)
