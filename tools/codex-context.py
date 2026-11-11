#!/usr/bin/env python3
"""Маршрутизация контекста Codex и проверка ссылок; только стандартная библиотека.

python3 tools/codex-context.py ops/backup.sh [ещё пути]
python3 tools/codex-context.py --check
python3 tools/codex-context.py --selftest

Выдача путей не означает загрузку документов в модель. Настоящий стартовый
запрос CLI проверяет codex-context-probe.py. Шаблоны правил берутся из paths:
Claude без копирования таблицы. Не сканирует worktree или node_modules.
"""
import argparse
import fnmatch
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BOOTSTRAP = ('AGENTS.md', 'CLAUDE.md', 'docs/agent-workflow.md', 'docs/codex.md')
EXPLICIT_ONLY = {'uborka', 'vykladka'}
LIMIT = 32 * 1024


def frontmatter(path):
    text = path.read_text(encoding='utf-8')
    if not text.startswith('---\n') or '\n---\n' not in text[4:]:
        raise ValueError('нет законченного frontmatter: ' + str(path))
    return text[4:].split('\n---\n', 1)[0]


def rule_patterns(path):
    header = frontmatter(path)
    if not re.search(r'^paths:\s*$', header, re.M):
        raise ValueError('нет paths: в ' + str(path))
    patterns = []
    for line in header.splitlines()[1:]:
        match = re.fullmatch(r'\s+-\s+["\']([^"\']+)["\']\s*', line)
        if not match:
            raise ValueError('неподдерживаемая запись paths: ' + str(path))
        pattern = match.group(1)
        if pattern.startswith('/') or '..' in Path(pattern).parts:
            raise ValueError('шаблон вне проекта: ' + pattern)
        patterns.append(pattern)
    if not patterns:
        raise ValueError('пустой paths: ' + str(path))
    return patterns


def repository_files(root, pattern):
    result = subprocess.run(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z', '--', pattern],
                            cwd=str(root), check=True, stdout=subprocess.PIPE)
    return [Path(p) for p in result.stdout.decode().split('\0') if p]


def tracked_sources(root):
    return repository_files(root, '*CLAUDE.md')


def context(root, paths):
    files = list(BOOTSTRAP)
    for raw in paths:
        target = (root / raw).resolve()
        try:
            relative = target.relative_to(root.resolve())
        except ValueError:
            raise ValueError('путь вне проекта: ' + raw)
        if any(p in relative.parts for p in ('.git', 'node_modules', 'target', 'dist')):
            raise ValueError('сгенерированный/служебный путь: ' + raw)
        if relative.parts[:2] == ('.claude', 'worktrees'):
            raise ValueError('чужое рабочее дерево: ' + raw)
        directory = target.is_dir() or raw.endswith('/')
        parent = relative if directory else relative.parent
        ancestors = [Path('.')] + list(reversed(parent.parents)) + [parent]
        for ancestor in ancestors:
            for name in ('AGENTS.md', 'CLAUDE.md'):
                path = ancestor / name
                if (root / path).is_file():
                    files.append(path.as_posix())
        if relative.parts and relative.parts[0] == 'frontend':
            files.append('docs/frontend-rules.md')
        if relative.parts and relative.parts[0] == 'src':
            files.append('docs/java-rules.md')
            # Тестовые пакеты повторяют имя производственного модуля.
            if relative.parts[:3] == ('src', 'test', 'java'):
                modules = list(relative.parts[3:])
                # Сквозные тесты затрагивают несколько модулей. Одного имени
                # пакета мало: например, отчёт импортирует StockLedger.
                if target.is_file() and target.suffix == '.java':
                    modules += re.findall(r'^import\s+(?:static\s+)?ru\.partsflow\.([a-z]+)\.',
                                          target.read_text(encoding='utf-8'), re.M)
                for part in dict.fromkeys(modules):
                    memo = Path('src/main/java/ru/partsflow') / part / 'CLAUDE.md'
                    if (root / memo).is_file():
                        files.extend([str(memo.parent / 'AGENTS.md'), str(memo)])
        if relative.parts and relative.parts[0] == 'tasks':
            files.append('tasks/README.md')
        if directory:
            for memo in tracked_sources(root):
                if parent == Path('.') or parent in memo.parents:
                    files.extend([str(memo.parent / 'AGENTS.md'), str(memo)])
        for rule in sorted((root / '.claude/rules').glob('*.md')):
            for pattern in rule_patterns(rule):
                prefix = re.split(r'[*?\[]', pattern, maxsplit=1)[0]
                matched = fnmatch.fnmatchcase(relative.as_posix(), pattern)
                if directory:
                    matched = matched or parent == Path('.') or prefix.startswith(parent.as_posix() + '/')
                if matched:
                    files.append(rule.relative_to(root).as_posix())
                    break
    return list(dict.fromkeys(files))


def quoted_fields(path):
    """Читает простой поднабор TOML наших ролей; полноценный парсер — сам CLI."""
    fields = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        key, sep, value = line.partition(' = ')
        if not sep or key in fields:
            raise ValueError('неожиданный формат роли: ' + str(path))
        fields[key] = json.loads(value)
    return fields


def check(root):
    errors = []
    required = list(BOOTSTRAP) + ['tools/AGENTS.md', 'tasks/AGENTS.md', 'src/AGENTS.md']
    try:
        memos = tracked_sources(root)
        required += [str(p.parent / 'AGENTS.md') for p in memos]
        for p in repository_files(root, '.claude/rules/*.md'):
            rule_patterns(root / p)
        for source in sorted((root / '.claude/skills').glob('*/SKILL.md')):
            name = source.parent.name
            entry = root / '.agents/skills' / name / 'SKILL.md'
            header = frontmatter(entry)
            if not re.search(r'^name: ' + re.escape(name) + r'$', header, re.M):
                errors.append('неверное имя скилла: ' + str(entry))
            description = re.search(r'^description: (.+)$', header, re.M)
            value = json.loads(description.group(1)) if description else None
            if not isinstance(value, str) or not value.strip():
                errors.append('нет корректного description: ' + str(entry))
            if source.relative_to(root).as_posix() not in entry.read_text(encoding='utf-8'):
                errors.append('нет ссылки на процедуру: ' + str(entry))
            policy = (entry.parent / 'agents/openai.yaml').read_text(encoding='utf-8')
            expected = 'false' if name in EXPLICIT_ONLY else 'true'
            if not re.search(r'^  allow_implicit_invocation: ' + expected + r'$', policy, re.M):
                errors.append('нарушена политика вызова: ' + name)
        for source in sorted((root / '.claude/agents').glob('*.md')):
            role = root / '.codex/agents' / (source.stem + '.toml')
            data = quoted_fields(role)
            if set(data) != {'name', 'description', 'developer_instructions'}:
                errors.append('неожиданные настройки роли: ' + str(role))
            if data.get('name') != source.stem or not data.get('description'):
                errors.append('неверные метаданные роли: ' + str(role))
            if any(not isinstance(data.get(key), str) or not data[key].strip()
                   for key in ('name', 'description', 'developer_instructions')):
                errors.append('поля роли должны быть непустыми строками: ' + str(role))
            if source.relative_to(root).as_posix() not in str(data.get('developer_instructions', '')):
                errors.append('нет ссылки на договор: ' + str(role))
        # Не допускаем молча оставшиеся обёртки после удаления исходника.
        for entry in (root / '.agents/skills').glob('*/SKILL.md'):
            required.append('.claude/skills/' + entry.parent.name + '/SKILL.md')
        for role in (root / '.codex/agents').glob('*.toml'):
            required.append('.claude/agents/' + role.stem + '.md')
        for path in dict.fromkeys(required):
            if not (root / path).is_file():
                errors.append('нет файла: ' + path)
        for entry in repository_files(root, '*AGENTS.md'):
            chain = [root / 'AGENTS.md']
            chain.extend(root / p / 'AGENTS.md' for p in reversed(entry.parent.parents)
                         if p != Path('.') and (root / p / 'AGENTS.md').is_file())
            if entry.parent != Path('.'):
                chain.append(root / entry)
            size = sum(len(p.read_bytes()) + 2 for p in chain if p.is_file())
            if size > LIMIT:
                errors.append('стартовая цепочка >32 КиБ: ' + str(entry.parent))
        # Новая ссылка в точке входа должна разрешаться в репозитории.
        for entry in [root / p for p in dict.fromkeys(required) if p.endswith('AGENTS.md')] + list((root / '.agents/skills').glob('*/SKILL.md')):
            if not entry.is_file():
                continue
            for ref in re.findall(r'`([^`\n]+)`', entry.read_text(encoding='utf-8')):
                if re.fullmatch(r'[.\w/-]+\.(?:md|py|sh|toml|yaml)', ref) and not (root / ref).is_file():
                    errors.append('битая ссылка: ' + str(entry.relative_to(root)) + ' -> ' + ref)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        errors.append(str(error))
    return errors


def selftest():
    class Routes(unittest.TestCase):
        def test_all_project_rule_routes(self):
            for rule in sorted((ROOT / '.claude/rules').glob('*.md')):
                for pattern in rule_patterns(rule):
                    # Текущие paths используют только *; новые формы надо
                    # сначала проверить, иначе самопроверка молчала бы о них.
                    self.assertNotIn('?', pattern)
                    self.assertNotIn('[', pattern)
                    path = pattern.replace('*', 'context-probe')
                    self.assertIn(rule.relative_to(ROOT).as_posix(), context(ROOT, [path]))

        def test_exact_glob_and_directory(self):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                (root / '.claude/rules').mkdir(parents=True)
                rule = root / '.claude/rules/backups.md'
                rule.write_text('---\npaths:\n  - "ops/backup*.sh"\n---\nbody\n')
                (root / 'ops').mkdir()
                (root / 'ops/AGENTS.md').write_text('instructions')
                (root / 'ops/CLAUDE.md').write_text('memo')
                subprocess.run(['git', 'init', '-q', str(root)], check=True)
                subprocess.run(['git', 'add', '.'], cwd=str(root), check=True)
                for path in ('ops/backup.sh', 'ops/backup-new.sh', str(root / 'ops/backup.sh'), 'ops/'):
                    result = context(root, [path])
                    self.assertIn('.claude/rules/backups.md', result)
                    self.assertIn('ops/CLAUDE.md', result)
                self.assertNotIn('.claude/rules/backups.md', context(root, ['ops/deploy.sh']))
                rule.write_text('---\npaths:\n  - "tools/new*.py"\n---\nbody\n')
                self.assertIn('.claude/rules/backups.md', context(root, ['tools/new-guard.py']))

        def test_invalid_patterns_fail(self):
            with tempfile.TemporaryDirectory() as tmp:
                rule = Path(tmp) / 'rule.md'
                for header in ('paths: []', 'paths:\n  - "../secret"', 'paths:\n  - unquoted', 'other: true'):
                    rule.write_text('---\n' + header + '\n---\nbody\n')
                    with self.assertRaises(ValueError):
                        rule_patterns(rule)

        def test_cross_module_test_imports(self):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                test = root / 'src/test/java/ru/partsflow/reports/ReportTest.java'
                test.parent.mkdir(parents=True)
                test.write_text('package ru.partsflow.reports;\n'
                                'import ru.partsflow.inventory.StockLedger;\n'
                                'import static ru.partsflow.sales.Calculations.total;\n')
                for module in ('reports', 'inventory', 'sales'):
                    memo = root / 'src/main/java/ru/partsflow' / module / 'CLAUDE.md'
                    memo.parent.mkdir(parents=True)
                    memo.write_text('module memo')
                files = context(root, [str(test)])
                for module in ('reports', 'inventory', 'sales'):
                    self.assertIn('src/main/java/ru/partsflow/' + module + '/CLAUDE.md', files)

        def test_scope_and_deduplication(self):
            for path in ('../outside', '.claude/worktrees/agent/file', 'frontend/node_modules/x', 'target/x'):
                with self.assertRaises(ValueError):
                    context(ROOT, [path])
            result = context(ROOT, ['ops/deploy.sh', 'ops/switch-build.sh'])
            self.assertEqual(len(result), len(set(result)))
            self.assertIn('.claude/rules/ops-vykladka.md', result)
            self.assertIn('.claude/rules/ops-chasy.md', context(ROOT, ['tools/date-mine-guard.py']))
            self.assertIn('src/main/java/ru/partsflow/inventory/CLAUDE.md',
                          context(ROOT, ['src/test/java/ru/partsflow/inventory/NewTest.java']))

        def test_missing_sources_policy_and_size(self):
            # Копия только инструкции: не трогаем рабочую копию или профиль.
            import shutil
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                files = set(BOOTSTRAP) | {'tools/AGENTS.md', 'tasks/AGENTS.md', 'src/AGENTS.md'}
                for directory, pattern in (('.agents/skills', '**/*'), ('.codex/agents', '*.toml'),
                                           ('.claude/agents', '*.md'), ('.claude/skills', '*/SKILL.md'),
                                           ('.claude/rules', '*.md')):
                    files.update(str(p.relative_to(ROOT)) for p in (ROOT / directory).glob(pattern) if p.is_file())
                for p in tracked_sources(ROOT):
                    files.update((str(p), str(p.parent / 'AGENTS.md')))
                # Ссылки проверяются тоже, поэтому копируем названные документы.
                for entry in ROOT.glob('docs/*.md'):
                    files.add(str(entry.relative_to(ROOT)))
                files.update(('tools/codex-context.py', 'tools/codex-context-probe.py', 'tools/board.py',
                              'tools/head-green.sh', 'tools/can-start.sh', 'tasks/README.md', 'db/verify.sh',
                              'tools/dev-up.sh', 'tools/checks.sh', 'tools/smoke.sh'))
                for path in files:
                    if (ROOT / path).is_file():
                        (root / path).parent.mkdir(parents=True, exist_ok=True)
                        shutil.copyfile(str(ROOT / path), str(root / path))
                subprocess.run(['git', 'init', '-q', str(root)], check=True)
                subprocess.run(['git', 'add', '.'], cwd=str(root), check=True)
                self.assertEqual(check(root), [])
                policy = root / '.agents/skills/uborka/agents/openai.yaml'
                original = policy.read_text()
                policy.write_text(original.replace('allow_implicit_invocation: false', 'allow_implicit_invocation: true'))
                self.assertTrue(check(root))
                policy.write_text(original)
                entry = root / '.agents/skills/volna/SKILL.md'
                original = entry.read_text()
                entry.write_text(re.sub(r'^description: .+$', 'description: 123', original, flags=re.M))
                self.assertTrue(check(root))
                entry.write_text(original)
                role = root / '.codex/agents/reviewer.toml'
                original = role.read_text()
                role.write_text(re.sub(r'^description = .+$', 'description = 123', original, flags=re.M))
                self.assertTrue(check(root))
                role.write_text(original)
                source = root / '.claude/agents/reviewer.md'
                original = source.read_bytes()
                source.unlink()
                self.assertTrue(check(root))
                source.write_bytes(original)
                (root / 'AGENTS.md').write_text('x' * (LIMIT + 1))
                self.assertTrue(any('32 КиБ' in error for error in check(root)))

    return unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Routes)).wasSuccessful()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('paths', nargs='*')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--selftest', action='store_true')
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args()
    if args.selftest:
        return 0 if selftest() else 1
    if args.check:
        errors = check(ROOT)
        print('\n'.join(errors) if errors else 'OK: памятки, скиллы, роли, paths и стартовые цепочки Codex')
        return 1 if errors else 0
    try:
        files = context(ROOT, args.paths)
        if args.json:
            print(json.dumps(files, ensure_ascii=False, indent=2))
        else:
            print('Прочитать (пути от корня проекта; это список, не подтверждение загрузки):')
            print('\n'.join(files))
        return 0
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
