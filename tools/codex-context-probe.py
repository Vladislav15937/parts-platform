#!/usr/bin/env python3
"""Проверить настоящий контекст нового Codex CLI через локальный приёмник.

Не вызывает удалённую модель, не читает auth пользователя и не запускает
агентов. Нужны Codex CLI и разрешение среды на локальный TCP socket.
Проверяет HTTP-запрос, собранный CLI, и skills/list штатного app-server.
Профиль, логи и принятые запросы живут во временном каталоге, не в git.
"""
import argparse
import importlib.util
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('codex_context', str(ROOT / 'tools/codex-context.py'))
context = importlib.util.module_from_spec(spec)
spec.loader.exec_module(context)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def strings(value):
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        return [text for v in value.values() for text in strings(v)]
    if isinstance(value, list):
        return [text for v in value for text in strings(v)]
    return []


class Receiver(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        try:
            raw = self.rfile.read(int(self.headers['Content-Length']))
            request = json.loads(raw)
            self.server.requests.put(request)
            message = {'id': 'msg_context_probe', 'type': 'message', 'role': 'assistant',
                       'status': 'completed', 'content': [{'type': 'output_text',
                                                         'text': 'context-probe-ok', 'annotations': []}]}
            response = {'id': 'resp_context_probe', 'object': 'response', 'created_at': int(time.time()),
                        'status': 'completed', 'output': [message],
                        'usage': {'input_tokens': 0, 'output_tokens': 0, 'total_tokens': 0}}
            events = [ {'type': 'response.created', 'response': dict(response, status='in_progress', output=[])},
                       {'type': 'response.output_item.done', 'output_index': 0, 'item': message},
                       {'type': 'response.completed', 'response': response} ]
            payload = ''.join('event: ' + e['type'] + '\ndata: ' + json.dumps(e) + '\n\n' for e in events).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except Exception as error:
            self.server.requests.put(error)
            self.send_error(500)


def skills_list(codex, env, cwd, log):
    """Штатное API обнаружения без запуска модельного turn."""
    with log.open('w') as errors:
        proc = subprocess.Popen([codex, 'app-server', '--stdio'], cwd=str(cwd), env=env,
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors, text=True)
        messages = queue.Queue()

        def reader():
            for line in proc.stdout:
                try:
                    messages.put(json.loads(line))
                except ValueError:
                    pass

        thread = threading.Thread(target=reader, daemon=True)
        thread.start()

        def send(value):
            proc.stdin.write(json.dumps(value) + '\n')
            proc.stdin.flush()

        def wait(request_id):
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline:
                try:
                    value = messages.get(timeout=max(.01, deadline - time.monotonic()))
                except queue.Empty:
                    break
                if value.get('id') == request_id:
                    require('error' not in value, 'app-server: ' + str(value.get('error')))
                    return value['result']
                if 'id' in value and 'method' in value:
                    send({'id': value['id'], 'error': {'code': -32601, 'message': 'probe does not approve actions'}})
            raise RuntimeError('app-server не ответил; журнал: ' + str(log))

        try:
            send({'id': 1, 'method': 'initialize', 'params': {
                'clientInfo': {'name': 'parts-context-probe', 'version': '1.0'},
                'capabilities': {'experimentalApi': True}}})
            wait(1)
            send({'method': 'initialized', 'params': {}})
            send({'id': 2, 'method': 'skills/list', 'params': {
                'cwds': [str(cwd), str(cwd / 'frontend')], 'forceReload': True}})
            return wait(2)
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
            proc.stdin.close()
            proc.stdout.close()


def probe(output):
    codex = shutil.which('codex')
    require(codex, 'Codex CLI не найден в PATH')
    errors = context.check(ROOT)
    require(not errors, '\n'.join(errors))
    output.mkdir(parents=True, exist_ok=True)
    profile = output / 'profile'
    profile.mkdir()
    # Новый профиль: без пользовательских auth, токенов, MCP и хуков.
    (profile / 'config.toml').write_text(
        '[projects.' + json.dumps(str(ROOT)) + ']\ntrust_level = "trusted"\n', encoding='utf-8')
    env = dict(os.environ, CODEX_HOME=str(profile))
    for key in list(env):
        if any(token in key.upper() for token in ('API_KEY', 'AUTH_TOKEN', 'ACCESS_TOKEN')):
            env.pop(key)
    server = HTTPServer(('127.0.0.1', 0), Receiver)
    server.requests = queue.Queue()
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()
    base = 'http://127.0.0.1:' + str(server.server_port) + '/v1'
    summary = {'codex': subprocess.check_output([codex, '--version'], env=env, text=True).strip(),
               'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=str(ROOT), text=True).strip(),
               'remote_model_called': False, 'directories': [], 'skills': [], 'explicit_invocations': []}
    try:
        memos = context.tracked_sources(ROOT)
        directories = {Path('.'), Path('src'), Path('tools'), Path('tasks')}
        directories.update(p.parent for p in memos)
        implicit = {p.parent.name: json.loads(re.search(
            r'^description: (.+)$', context.frontmatter(p), re.M).group(1))
            for p in (ROOT / '.agents/skills').glob('*/SKILL.md')}
        roles = [context.quoted_fields(p) for p in (ROOT / '.codex/agents').glob('*.toml')]
        cases = [(directory, None) for directory in sorted(directories)]
        cases += [(Path('.'), skill) for skill in sorted(context.EXPLICIT_ONLY)]
        for index, (directory, selected_skill) in enumerate(cases):
            cwd = ROOT / directory
            args = [codex, 'exec', '--ephemeral', '--json', '-s', 'read-only',
                    '-c', 'model="context-probe"', '-c', 'model_provider="context_probe"',
                    '-c', 'model_providers.context_probe.name="Local context probe"',
                    '-c', 'model_providers.context_probe.base_url=' + json.dumps(base),
                    '-c', 'model_providers.context_probe.wire_api="responses"',
                    '-c', 'model_providers.context_probe.requires_openai_auth=false',
                    '-c', 'model_providers.context_probe.request_max_retries=0',
                    '-c', 'model_providers.context_probe.stream_max_retries=0',
                    ('Используй $' + selected_skill + '. ' if selected_skill else '') +
                    'Проверка загрузки контекста. Ничего не исполняй и не читай. Ответь context-probe-ok.']
            completed = subprocess.run(args, cwd=str(cwd), env=env, stdout=subprocess.PIPE,
                                       stderr=subprocess.PIPE, text=True, timeout=45)
            (output / ('cli-' + str(index) + '.log')).write_text(completed.stderr + completed.stdout)
            require(completed.returncode == 0, 'CLI отказал для ' + str(directory) + '; журнал: ' + str(output / ('cli-' + str(index) + '.log')))
            try:
                request = server.requests.get(timeout=3)
            except queue.Empty:
                raise RuntimeError('нет HTTP-запроса CLI: ' + str(directory))
            require(isinstance(request, dict), 'приём запроса не удался: ' + str(request))
            (output / ('request-' + str(index) + '.json')).write_text(json.dumps(request, ensure_ascii=False, indent=2))
            text = '\n'.join(strings(request))
            chain = [ROOT / 'AGENTS.md']
            ancestors = list(reversed(directory.parents)) + [directory]
            chain += [ROOT / p / 'AGENTS.md' for p in ancestors
                      if p != Path('.') and (ROOT / p / 'AGENTS.md').is_file()]
            for entry in chain:
                require(entry.read_text(encoding='utf-8').strip() in text,
                        'не передана инструкция ' + str(entry.relative_to(ROOT)) + ' из ' + str(directory))
            for name, description in implicit.items():
                included = description in text
                require(included == (name not in context.EXPLICIT_ONLY or name == selected_skill),
                        'неверная автоматическая загрузка скилла ' + name + ' из ' + str(directory))
            for role in roles:
                require(role['description'] in text, 'CLI не обнаружил роль: ' + role['name'])
            if selected_skill:
                entry = ROOT / '.agents/skills' / selected_skill / 'SKILL.md'
                body = entry.read_text().split('\n---\n', 1)[1].strip()
                require(body in text, 'явный вызов не загрузил тело скилла ' + selected_skill)
                summary['explicit_invocations'].append(selected_skill)
                print('OK явный вызов CLI: $' + selected_skill + ' — тело скилла передано', flush=True)
                continue
            # Большие источники должны читаться по задаче, не копироваться целиком.
            require((ROOT / 'CLAUDE.md').read_text().strip() not in text,
                    'корневой CLAUDE.md ошибочно включён в стартовый запрос целиком')
            summary['directories'].append({'cwd': str(directory), 'instructions': [str(p.relative_to(ROOT)) for p in chain]})
            print('OK запрос CLI:', directory, '—', len(chain), 'AGENTS.md, 3 автоматических скилла, 11 ролей', flush=True)
        result = skills_list(codex, env, ROOT, output / 'app-server.log')
        (output / 'skills-list.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        for entry in result['data']:
            local = {s['name']: s for s in entry['skills'] if str(ROOT / '.agents/skills') in s['path']}
            require(set(local) == set(implicit), 'skills/list не обнаружил все пять проектных скиллов')
            require(not entry['errors'], 'skills/list вернул ошибки: ' + str(entry['errors']))
            require(all(s['enabled'] for s in local.values()), 'проектный скилл отключён')
            summary['skills'].append({'cwd': entry['cwd'], 'names': sorted(local)})
        (output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
        print('OK skills/list: 5 скиллов из корня и frontend; explicit-only проверено по запросам CLI')
        print('Протокол:', output)
    finally:
        server.shutdown()
        server.server_close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='новый каталог для протокола вне репозитория')
    args = parser.parse_args()
    output = args.output or Path(tempfile.mkdtemp(prefix='parts-codex-context-'))
    try:
        require(ROOT not in output.resolve().parents and output.resolve() != ROOT,
                'протокол должен храниться вне репозитория')
        probe(output)
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print('Проверка НЕ пройдена: ' + str(error), file=sys.stderr)
        print('Протокол: ' + str(output), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
