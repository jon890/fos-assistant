"""공식 조건주문 원문 vector를 실제 prepare 소비 함수에 넣는다."""

import hashlib
import importlib
import json
import pathlib
import unittest
import copy
import types

from test_connector_manifest import ConnectorGateCase

REPO = pathlib.Path(__file__).resolve().parents[2]
RAW_LIMITS = {'modelArgs': 16384, 'executionArgs': 16384, 'summary': 8192, 'scope': 2048}
RAW_ONLY = {'RAW-' + field + '-BYTES' for field in RAW_LIMITS}


class ConnectorConditionalValidationTest(ConnectorGateCase):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        plugin = cls.plugin
        cls.validation = importlib.import_module(plugin.__name__ + '.connector_guard_validation')
        cls.guard = importlib.import_module(plugin.__name__ + '.connector_guard')
        cls.cases = json.loads((REPO / 'test/resources/conditional-snapshot-v1.json').read_text())['cases']

    def consume(self, case):
        model, args, summary, scope = [self.guard._strict_json(case[field + 'Json'])
                                       for field in ('modelArgs', 'executionArgs', 'summary', 'scope')]
        manifest = {'fields': [{'key': 'account', 'env': 'TEST_ACCOUNT'}], 'execution_guard': {
            'operations': {case['tool']: case['operation']}, 'kinds': {case['tool']: 'CONDITIONAL'},
            'scope_fields': [{'arg': key, 'field': 'account'} for key in scope]}}
        return self.validation._prepare_result({'v': 1, 'executionArgs': args, 'summary': summary}, model,
                                                manifest, case['tool'], {'TEST_ACCOUNT': '1234'})

    def test_actual_object_consumers(self):
        accepted = rejected = 0
        for case in self.cases:
            with self.subTest(case=case['name']):
                # 네 공백 원문은 Spring에서 거절하지만 객체 변환 뒤에는 허용된다.
                if case['valid'] or case['name'] in RAW_ONLY:
                    self.consume(case)
                    accepted += 1
                else:
                    with self.assertRaises((ValueError, UnicodeError)):
                        self.consume(case)
                    rejected += 1
        self.assertEqual((accepted, rejected), (51, 128))
        self.assertEqual(sum(case['name'] not in RAW_ONLY for case in self.cases), 175)

    def test_spring_raw_boundary_expectations(self):
        raw_only = [case for case in self.cases if case['name'] in RAW_ONLY]
        self.assertEqual({case['name'] for case in raw_only}, RAW_ONLY)
        for case in self.cases:
            for field in RAW_LIMITS:
                self.assertEqual(hashlib.sha256(case[field + 'Json'].encode()).hexdigest(), case[field + 'Sha256'])
        for case in raw_only:
            with self.subTest(case=case['name']):
                field = case['name'][4:-6]
                self.assertGreater(len(case[field + 'Json'].encode('utf-8')), RAW_LIMITS[field])
                self.assertFalse(case['valid'])
                self.consume(case)
        # valid는 Spring 원문 경계의 기대이며 위 길이 단언은 production 거절이 아니다.
        self.assertEqual((len(self.cases), sum(case['valid'] for case in self.cases)), (179, 47))
        self.assertEqual(sum(not case['valid'] for case in self.cases), 132)

    def test_reserved_scope_and_env(self):
        fixture = json.loads((REPO / 'test/resources/conditional-snapshot-v1.json').read_text())
        self.assertNotIn("account", self.guard.RESERVED)
        self.assertIn("orderId", self.guard.RESERVED)
        case = self.cases[0]
        args, summary, model = [json.loads(case[field + 'Json']) for field in ('executionArgs', 'summary', 'modelArgs')]
        manifest = {'fields': [{'key': 'account', 'env': 'TEST_ACCOUNT'}], 'execution_guard': {
            'operations': {case['tool']: 'CREATE'}, 'kinds': {case['tool']: 'CONDITIONAL'},
            'scope_fields': [{'arg': 'account_seq', 'field': 'account'}]}}
        for changed in ({'TEST_ACCOUNT': '5678'}, {}):
            with self.assertRaises(ValueError):
                self.validation._prepare_result({'v': 1, 'executionArgs': args, 'summary': summary}, model, manifest, case['tool'], changed)
        for scope in [{'arg': 'tenant', 'field': 'account'}, {'arg': 'account_seq', 'field': 'other'}]:
            changed = copy.deepcopy(manifest)
            changed['execution_guard']['scope_fields'] = [scope]
            if scope['field'] == 'other':
                changed['fields'] = [{'key': 'other', 'env': 'TEST_ACCOUNT'}]
            with self.assertRaises(ValueError):
                self.validation._prepare_result({'v': 1, 'executionArgs': args, 'summary': summary}, model, changed, case['tool'], {'TEST_ACCOUNT': '1234'})

    def test_official_detail_and_conditional_scope(self):
        fixture = json.loads((REPO / 'test/resources/conditional-snapshot-v1.json').read_text())
        official = json.loads((REPO / fixture['officialFixture']).read_text())['paths']['/api/v1/conditional-orders/{conditionalOrderId}']['get']['responses']['200']['content']['application/json']['example']['result']
        for case in self.cases:
            if case.get('officialExample'):
                self.assertEqual(json.loads(case['summaryJson'])['original'], official)
                self.consume(case)
        conditional = importlib.import_module(self.plugin.__name__ + '.connector_conditional_validation')
        case = self.cases[0]
        payload = {'v': 1, 'executionArgs': json.loads(case['executionArgsJson']), 'summary': json.loads(case['summaryJson'])}
        model = json.loads(case['modelArgsJson'])
        for reserved in fixture['reservedScopeNames']:
            with self.subTest(reserved=reserved), self.assertRaises(ValueError):
                conditional.validate_conditional_prepare(payload, model, 'CREATE', {reserved: '1234'})
        general = json.loads((REPO / 'test/fixtures/financial-approval-v1.json').read_text())['cases'][0]
        original = json.loads(general['modelArgsJson'])
        args = json.loads(general['executionArgsJson'].replace('account_seq', 'account'))
        summary = json.loads(general['summaryJson'])
        manifest = {'fields': [{'key': 'account', 'env': 'TEST_ACCOUNT'}], 'execution_guard': {
            'operations': {general['tool']: 'CREATE'}, 'scope_fields': [{'arg': 'account', 'field': 'account'}]}}
        self.validation._prepare_result({'v': 1, 'executionArgs': args, 'summary': summary}, original,
                                        manifest, general['tool'], {'TEST_ACCOUNT': args['account']})

    def test_tool_kind_decides_branch(self):
        case = self.cases[0]
        model, args, summary = [json.loads(case[field + 'Json']) for field in ('modelArgs', 'executionArgs', 'summary')]
        guard = {'operations': {'create_order': 'CREATE'}, 'scope_fields': [{'arg': 'account_seq', 'field': 'account'}]}
        # 일반 도구에 조건 summary 를 넣으면, 선언이 없을 때도 ORDER 를 선언했을 때도 거절한다.
        for kinds in ({}, {'kinds': {'create_order': 'ORDER'}}):
            manifest = {'fields': [{'key': 'account', 'env': 'TEST_ACCOUNT'}], 'execution_guard': {**guard, **kinds}}
            with self.subTest(kinds=kinds), self.assertRaises(ValueError):
                self.validation._prepare_result({'v': 1, 'executionArgs': args, 'summary': summary}, model,
                                                manifest, 'create_order', {'TEST_ACCOUNT': '1234'})
        general = json.loads((REPO / 'test/fixtures/financial-approval-v1.json').read_text())['cases'][0]
        args = json.loads(general['executionArgsJson'].replace('account_seq', 'account'))
        manifest = {'fields': [{'key': 'account', 'env': 'TEST_ACCOUNT'}], 'execution_guard': {
            'operations': {general['tool']: 'CREATE'}, 'kinds': {general['tool']: 'CONDITIONAL'},
            'scope_fields': [{'arg': 'account', 'field': 'account'}]}}
        with self.assertRaises(ValueError):
            self.validation._prepare_result({'v': 1, 'executionArgs': args, 'summary': json.loads(general['summaryJson'])},
                                            json.loads(general['modelArgsJson']), manifest, general['tool'],
                                            {'TEST_ACCOUNT': args['account']})

    def test_actual_text_prepare_parser(self):
        prepare = importlib.import_module(self.plugin.__name__ + '.connector_prepare')
        case = self.cases[0]
        model = json.loads(case['modelArgsJson'])
        manifest = {'fields': [{'key': 'account', 'env': 'TEST_ACCOUNT'}], 'execution_guard': {
            'operations': {case['tool']: 'CREATE'}, 'kinds': {case['tool']: 'CONDITIONAL'},
            'scope_fields': [{'arg': 'account_seq', 'field': 'account'}]}}
        raw = '{"v":1,"executionArgs":' + case['executionArgsJson'] + ',"summary":' + case['summaryJson'] + '}'
        for value in (raw, raw + ' {}', raw[:-1] + ',"v":1}'):
            result = types.SimpleNamespace(is_error=False, structured_content=None,
                                           content=[types.SimpleNamespace(type='text', text=value)])
            if value == raw:
                answer = prepare._prepare_answer(result, model, manifest, case['tool'], {'TEST_ACCOUNT': '1234'})
                self.assertTrue(answer['ok'])
            else:
                with self.assertRaises(ValueError):
                    prepare._prepare_answer(result, model, manifest, case['tool'], {'TEST_ACCOUNT': '1234'})


if __name__ == '__main__':
    unittest.main()
