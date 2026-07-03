from locust import HttpUser, task, between
import random

class CodeLensAPIUser(HttpUser):
    wait_time = between(0.5, 2)
    
    def on_start(self):
        # We can't do real OAuth in load test, so test public endpoints
        self.client.verify = False
    
    @task(5)
    def health_check(self):
        self.client.get("/actuator/health")
    
    @task(3)
    def auth_redirect(self):
        with self.client.get("/api/auth/github", allow_redirects=False, catch_response=True) as response:
            if response.status_code in [302, 307, 429]:
                response.success()
            else:
                response.failure(f"Unexpected status: {response.status_code}")
    
    @task(2)
    def unauthorized_repos(self):
        with self.client.get("/api/repos", catch_response=True) as response:
            if response.status_code == 401:
                response.success()
            else:
                response.failure(f"Unexpected status: {response.status_code}")
    
    @task(1)
    def forged_webhook(self):
        with self.client.post("/api/webhook/github",
            json={},
            headers={
                "X-Hub-Signature-256": "sha256=invalid",
                "X-GitHub-Event": "pull_request",
                "X-GitHub-Delivery": f"load-test-{random.randint(1,999999)}"
            },
            catch_response=True
        ) as response:
            if response.status_code == 200:
                response.success()
            else:
                response.failure(f"Unexpected status: {response.status_code}")
