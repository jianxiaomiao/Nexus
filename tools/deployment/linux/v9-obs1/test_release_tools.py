"""Offline safety checks; no ECS, MySQL, credentials or browser are accessed."""

import contextlib
import hashlib
import importlib.util
import io
import json
import os
import struct
import tarfile
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import verify_release
import verify_v8_backup


RELEASE = 'V2026.10.27.1__v9-obs1'


class ReleaseArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.release = self.root / RELEASE
        artifacts = []
        for name, data in [('server/nexus-server.jar', b'fake-jar-for-validation-only'),
                           ('console/dist/index.html', b'<html>offline fixture</html>')]:
            path = self.release / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            artifacts.append(dict(path=name, length=len(data), sha256=hashlib.sha256(data).hexdigest()))
        self.manifest = dict(version=RELEASE, platform='linux-x86_64', javaMajorVersion=21,
                             playwrightVersion='1.63.0', gitCommit='a' * 40,
                             gitWorkingTreeDirty=False, artifacts=artifacts)
        self.save_manifest()

    def save_manifest(self):
        (self.release / 'release-info.json').write_text(json.dumps(self.manifest), encoding='utf-8')

    def archive(self, extra=None):
        target = self.root / 'fixture.tar.gz'
        with tarfile.open(target, 'w:gz') as bundle:
            bundle.add(self.release, arcname=RELEASE)
            if extra:
                bundle.addfile(extra, io.BytesIO(b'X') if extra.isfile() else None)
        return target

    def test_clean_archive_roundtrip_and_hashes(self):
        root = verify_release.validate_archive(self.archive(), RELEASE, self.root / 'extracted')
        with contextlib.redirect_stdout(io.StringIO()):
            result = verify_release.validate_directory(root, RELEASE)
        self.assertEqual(2, result['artifactCount'])

    def test_dirty_candidate_refused(self):
        self.manifest['gitWorkingTreeDirty'] = True
        self.save_manifest()
        with self.assertRaises(SystemExit):
            verify_release.validate_directory(self.release, RELEASE)

    def test_tampered_same_length_file_refused(self):
        jar = self.release / 'server/nexus-server.jar'
        jar.write_bytes(b'X' * jar.stat().st_size)
        with self.assertRaises(SystemExit):
            verify_release.validate_directory(self.release, RELEASE)

    def test_unlisted_config_refused(self):
        (self.release / 'application-prod.yml').write_text('secret fixture')
        with self.assertRaises(SystemExit):
            verify_release.validate_directory(self.release, RELEASE)

    def test_path_traversal_absolute_and_backslash_refused(self):
        for name in (RELEASE + '/../escape', '/absolute', RELEASE + '/console\\..\\escape'):
            with self.subTest(name=name):
                info = tarfile.TarInfo(name)
                info.size = 1
                with self.assertRaises(SystemExit):
                    verify_release.validate_archive(self.archive(info), RELEASE, None)

    def test_symlink_and_hardlink_refused(self):
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
            info = tarfile.TarInfo(RELEASE + '/console/dist/link')
            info.type = kind
            info.linkname = '/etc/passwd'
            with self.subTest(kind=kind), self.assertRaises(SystemExit):
                verify_release.validate_archive(self.archive(info), RELEASE, None)

    def test_duplicate_member_refused(self):
        info = tarfile.TarInfo(RELEASE + '/console/dist/index.html')
        info.size = 1
        with self.assertRaises(SystemExit):
            verify_release.validate_archive(self.archive(info), RELEASE, None)


class BackupRewritingTests(unittest.TestCase):
    def test_only_database_selection_changes(self):
        dump = ('CREATE DATABASE /*!32312 IF NOT EXISTS*/ `nexus` /*!40100 DEFAULT CHARACTER SET utf8mb4 */;\n'
                'USE `nexus`;\n'
                "INSERT INTO example VALUES ('nexus remains business data');\n")
        rewritten = verify_v8_backup.prepare_dump(dump, 'probe_123')
        self.assertIn('USE `probe_123`;', rewritten)
        self.assertIn("'nexus remains business data'", rewritten)
        self.assertNotIn('USE `nexus`;', rewritten)

    def test_missing_or_repeated_selection_refused(self):
        for text in ('USE `nexus`;', 'CREATE DATABASE `nexus`;\nUSE `nexus`;\nUSE `nexus`;'):
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                verify_v8_backup.prepare_dump(text, 'probe_123')

    def test_original_schema_qualified_statement_refused(self):
        text = 'CREATE DATABASE `nexus`;\nUSE `nexus`;\nINSERT INTO `nexus`.`users` VALUES (1);'
        with self.assertRaises(RuntimeError):
            verify_v8_backup.prepare_dump(text, 'probe_123')


spec = importlib.util.spec_from_file_location('acceptance', Path(__file__).with_name('03-accept-v9-obs1.py'))
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


class AcceptanceFlowTests(unittest.TestCase):
    def test_full_flow_checks_real_contract_and_cleans_before_marker(self):
        self.exercise(False)

    def test_failed_job_cannot_create_pass_marker(self):
        self.exercise(True)

    def exercise(self, failed):
        png = b'\x89PNG\r\n\x1a\n' + b'\x00' * 8 + struct.pack('>II', 1280, 720) + b'\x00' * 1100
        state = {'completed': False, 'cleaned': False}
        job_id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

        class FakeClient:
            def api(self, method, path, body=None, authorization=None, expected=200):
                if path == '/api/auth/register':
                    self_test.assertEqual(201, expected)
                    return {'data': {'id': 1}}
                if path == '/api/auth/login':
                    return {'data': {'accessToken': 'test-jwt'}}
                if path == '/api/application/create':
                    return {'data': {'id': 10}}
                if path == '/api/apiKey/create':
                    return {'data': {'id': 20 if body['name'].endswith('A') else 21, 'apiKey': 'test-key'}}
                if path.endswith('/quota'):
                    return {'data': {'usedCount': 2 if state['completed'] else state.get('quota', 0)}}
                if path == '/v1/utils/uuid':
                    state['quota'] = 1
                    return {'data': {'uuid': 'fixture-uuid'}}
                if path == '/v1/web/screenshot-jobs':
                    self_test.assertEqual(201, expected)
                    time.sleep(0.2)
                    return {'data': {'jobId': job_id, 'status': 'QUEUED'}}
                if expected == 404:
                    return {'code': 'SCREENSHOT_JOB_NOT_FOUND'}
                if path.endswith(job_id):
                    state['completed'] = True
                    return {'data': {'status': 'FAILED' if failed else 'SUCCEEDED',
                                     'resultSha256': hashlib.sha256(png).hexdigest(), 'resultSizeBytes': len(png)}}
                raise AssertionError('unexpected path: ' + path)

            def request(self, method, path, body=None, authorization=None):
                if path.endswith('/result'):
                    state['browser_finished'] = True
                    return 200, {'content-type': 'image/png', 'cache-control': 'no-store'}, png
                return 404, {}, b''

        def fake_metric(name, query=''):
            if name.endswith(('queue.delay', 'execution')):
                return {'COUNT': int(state['completed'])}
            if name.endswith('record.failures'):
                return {'COUNT': 0}
            return {'VALUE': 0}

        def fake_run(command, **kwargs):
            if command[0] == 'aa-status':
                value = b'nexus-playwright-headless-shell'
            elif command[0] == 'sysctl':
                value = b'1'
            elif command[0] == 'systemctl':
                value = b'1234' if 'show' in command else b'active'
            elif command[0] == 'curl':
                value = b'nexus_screenshot_execution_seconds_count nexus_screenshot_queue_delay_seconds_count nexus_screenshot_jobs_unfinished nexus_usage_record_failures_total'
            else:
                value = b''
            return type('Result', (), {'stdout': value})()

        def fake_cleanup(*args):
            state['cleaned'] = True

        self_test = self
        with tempfile.TemporaryDirectory() as temporary:
            marker = Path(temporary) / 'pass'
            with contextlib.ExitStack() as stack:
                for name, value in {
                    'PASS_MARKER': marker, 'load_state': lambda: {'STATUS': 'PENDING'},
                    'Client': FakeClient, 'metric': fake_metric, 'run': fake_run,
                    'mysql': lambda sql: '3' if sql.startswith('SELECT status') else '1',
                    'cleanup': fake_cleanup,
                    'browser_processes': lambda: [] if state.get('browser_finished') else [Path('fixture-browser')],
                    'wait_usage': lambda key: [('uuid.generate', 200), ('web.screenshot', 201)],
                }.items():
                    stack.enter_context(patch.object(acceptance, name, value))
                stack.enter_context(patch.object(os, 'geteuid', return_value=0, create=True))
                stack.enter_context(patch.object(os, 'chown', create=True))
                stack.enter_context(patch.object(Path, 'resolve', return_value=Path(temporary) / RELEASE))
                stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
                if failed:
                    with self.assertRaisesRegex(RuntimeError, 'ended as FAILED'):
                        acceptance.main()
                    self.assertFalse(marker.exists())
                else:
                    acceptance.main()
                    self.assertTrue(marker.exists())
                self.assertEqual(not failed, state['cleaned'])


if __name__ == '__main__':
    unittest.main()
