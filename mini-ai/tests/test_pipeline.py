import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ENTRY = Path(__file__).resolve().parents[1] / "bin" / "run_daily_pipeline"


class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for name in ("工作", "健康", "成长"):
            rules = self.root / name / "AI规则" / "RULES.md"
            rules.parent.mkdir(parents=True)
            rules.write_text("测试规则", encoding="utf-8")
        self.state = self.root / "系统" / "系统状态" / "分析任务" / "2026-09-19.json"

    def run_job(self, runner=""):
        return subprocess.run([sys.executable, str(ENTRY), "--root", str(self.root), "--date", "2026-09-19", "--trigger", "test"],
                              env={**os.environ, "MIAOJI_MODEL_RUNNER": runner}, capture_output=True, text=True)

    def read(self):
        return json.loads(self.state.read_text())

    def write(self, state):
        self.state.write_text(json.dumps(state), encoding="utf-8")

    def test_queue_can_retry_after_model_is_configured(self):
        self.assertEqual(0, self.run_job().returncode)
        self.assertEqual("queued", self.read()["status"])
        self.assertNotEqual(0, self.run_job("/bin/false").returncode)
        self.assertEqual("failed", self.read()["status"])
        self.assertEqual(2, self.read()["attempt"])

    def test_complete_is_idempotent_but_late_data_creates_revision(self):
        self.run_job()
        state = self.read()
        state["status"] = "complete"
        self.write(state)
        self.run_job()
        self.assertEqual(state, self.read())
        (self.root / "健康" / "健康.csv").write_text("date,sleep\n2026-09-19,8\n")
        self.run_job()
        self.assertEqual("queued", self.read()["status"])
        self.assertEqual(2, self.read()["revision"])

    def test_stale_running_is_reclaimed(self):
        self.run_job()
        state = self.read()
        state.update(status="running", startedAt=0)
        self.write(state)
        self.run_job()
        self.assertEqual("queued", self.read()["status"])

    def test_generated_reports_do_not_trigger_new_revision(self):
        self.run_job()
        state = self.read()
        state["status"] = "complete"
        self.write(state)
        directory = self.root / "工作" / "AI报告"
        directory.mkdir()
        (directory / "日报.md").write_text("测试报告")
        self.run_job()
        self.assertEqual(state, self.read())


if __name__ == "__main__":
    unittest.main()
