#!/usr/bin/env python3
"""Проверка бюджетов и исходов настоящего clock-shift-run.sh без продукта.

python3 tools/clock-shift-selftest.py
python3 tools/clock-shift-selftest.py --script .scratch/clock-old.sh

Запускает сам скрипт в отдельном дереве с короткими заглушками тяжёлых
команд. timeout записывает оба фактических бюджета и имитирует незавершение;
это позволяет проверить пределы без шестиминутного ожидания в каждом тесте.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('clock-shift-run.sh')


class ClockRun(unittest.TestCase):
    def run_clock(self, mode='budget', other_check=False):
        with tempfile.TemporaryDirectory(prefix='parts-clock-test-') as tmp:
            root = Path(tmp)

            def executable(path, body):
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(body, encoding='utf-8')
                target.chmod(0o755)

            executable('tools/clock-shift-run.sh', SCRIPT.read_text(encoding='utf-8'))
            executable('ops/deploy.sh', '#!/bin/sh\n# --selftest\necho selftest-complete\n')
            if other_check:
                executable('tools/check.sh', '#!/bin/sh\n# --selftest\necho other-complete\n')
            executable('frontend/node_modules/.bin/vitest', '#!/bin/sh\necho "Tests 1 passed"\n')
            executable('bin/node', '#!/bin/sh\nexit 0\n')
            executable('mvnw', '#!/bin/sh\necho "BUILD SUCCESS"\n')
            executable('bin/java', '#!/bin/sh\necho java-real\n')
            executable('bin/faketime', '#!/bin/sh\nshift 2\n'
                       'if [ "$1" = java ]; then echo java-shifted; else exec "$@"; fi\n')
            executable('bin/timeout', '''#!/usr/bin/env python3
import json, os, subprocess, sys
shifted = int(subprocess.check_output(['date', '+%s'])) - int(subprocess.check_output(['/bin/date', '+%s'])) > 86400
with open(os.environ['CLOCK_TEST_LOG'], 'a') as log:
    log.write(json.dumps({'budget': int(sys.argv[1]), 'command': sys.argv[2:], 'shifted': shifted}) + '\\n')
mode = os.environ['CLOCK_TEST_MODE']
if sys.argv[2] == './ops/deploy.sh':
    if mode == 'budget' and shifted and int(sys.argv[1]) < 360:
        print('fixture reached deadline', flush=True)
        sys.exit(124)
    if mode == 'both_timeout' or mode == 'shifted_timeout' and shifted or mode == 'plain_timeout' and not shifted:
        print('fixture reached deadline', flush=True)
        sys.exit(124)
    if mode.startswith('incomplete_') and shifted:
        print('fixture command interrupted', flush=True)
        sys.exit(int(mode.split('_')[1]))
    if mode == 'changed' and shifted:
        print('fixture assertion failed', flush=True)
        sys.exit(1)
os.execv(sys.argv[2], sys.argv[2:])
''')
            log = root / 'calls.jsonl'
            env = dict(os.environ, PATH=str(root / 'bin') + os.pathsep + os.environ['PATH'],
                       CLOCK_TEST_LOG=str(log), CLOCK_TEST_MODE=mode)
            result = subprocess.run(['bash', 'tools/clock-shift-run.sh', '--java'], cwd=str(root),
                                    env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, timeout=20)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            return result, calls

    def test_heavy_check_has_equal_measured_budget_and_still_runs_twice(self):
        result, calls = self.run_clock(other_check=True)
        self.assertEqual(result.returncode, 0, result.stdout)
        deploy = [call for call in calls if call['command'][0] == './ops/deploy.sh']
        self.assertEqual([call['budget'] for call in deploy], [360, 360])
        self.assertEqual([call['shifted'] for call in deploy], [False, True])
        ordinary = [call for call in calls if call['command'][0] == './tools/check.sh']
        self.assertEqual([call['budget'] for call in ordinary], [180, 180])
        self.assertIn('без сдвига', result.stdout)
        self.assertIn('360 с', result.stdout)

    def test_timeout_in_either_or_both_runs_is_unchecked(self):
        for mode in ('plain_timeout', 'shifted_timeout', 'both_timeout'):
            with self.subTest(mode=mode):
                result, _ = self.run_clock(mode)
                self.assertEqual(result.returncode, 2, result.stdout)
                self.assertIn('НЕ ПРОВЕРЕНО: ops/deploy.sh', result.stdout)
                self.assertIn('fixture reached deadline', result.stdout)
                self.assertNotIn('календарная мина', result.stdout)
                self.assertNotIn('ни одна проверка не поменяла исход', result.stdout)

    def test_failed_launch_and_signal_are_unchecked(self):
        for code in (125, 126, 127, 137, 143):
            with self.subTest(code=code):
                result, _ = self.run_clock('incomplete_' + str(code))
                self.assertEqual(result.returncode, 2, result.stdout)
                self.assertIn('НЕ ПРОВЕРЕНО: ops/deploy.sh', result.stdout)
                self.assertIn('fixture command interrupted', result.stdout)

    def test_completed_changed_assertion_still_fails(self):
        result, _ = self.run_clock('changed')
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn('исход поменялся: без сдвига 0, со сдвигом 1', result.stdout)
        self.assertIn('fixture assertion failed', result.stdout)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--script', type=Path, default=SCRIPT)
    args = parser.parse_args()
    SCRIPT = args.script.resolve()
    unittest.main(argv=['clock-shift-selftest'], verbosity=2)
