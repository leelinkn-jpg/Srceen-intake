import json
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ENTRY = Path(__file__).resolve().parents[1] / 'scripts' / 'cleanup_reports.py'


class CleanupTests(unittest.TestCase):
    def test_retention_only_removes_expired_generated_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reports = root / '综合报告'
            feedback = reports / '反馈'
            feedback.mkdir(parents=True)
            old = int((time.time() - 40 * 86400) * 1000)
            now = int(time.time() * 1000)
            for name, status, stamp in [('expired', 'complete', old), ('new', 'complete', now), ('draft', 'writing', old)]:
                (reports / (name + '.json')).write_text(json.dumps({'status': status, 'generatedAt': stamp}))
                (reports / (name + '.md')).write_text('report')
            (feedback / 'unknown.json').write_text('{}')
            (feedback / 'old.json').write_text(json.dumps({'respondedAt': old}))
            source = root / '工作' / '待办.md'
            source.parent.mkdir()
            source.write_text('confirmed task')
            command = [sys.executable, str(ENTRY), '--root', str(root)]
            subprocess.run(command, check=True, capture_output=True)
            self.assertTrue((reports / 'expired.md').exists())
            subprocess.run(command + ['--apply'], check=True, capture_output=True)
            self.assertFalse((reports / 'expired.json').exists())
            self.assertFalse((reports / 'expired.md').exists())
            self.assertFalse((feedback / 'old.json').exists())
            self.assertTrue((feedback / 'unknown.json').exists())
            self.assertTrue((reports / 'new.md').exists())
            self.assertTrue((reports / 'draft.md').exists())
            self.assertEqual('confirmed task', source.read_text())
