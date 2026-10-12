"""조건주문의 모델·실행·표시와 공식 원주문 투영을 함께 검증한다."""

import re
import uuid
from datetime import date, datetime
from decimal import Decimal

SUMMARY = set('orderKind account operation conditionalOrderId symbol market currency type quantity orderType expireDate first second original normalization'.split())
WRITE = {'type', 'quantity', 'orderType', 'expireDate', 'first'}
DETAIL = set('conditionalOrderId type status symbol market quantity orderType first createdAt'.split())
LEG = {'type', 'status', 'orderSide'}
STATUS = {'WATCHING', 'PAUSED', 'ORDERING', 'ORDERED', 'COMPLETED', 'EXPIRED'}
PATHS = ('symbol', 'quantity', 'first.triggerPrice', 'first.orderPrice', 'second.triggerPrice', 'second.orderPrice')
MARKETS = {'KOSPI': ('KR', 'KRW'), 'KOSDAQ': ('KR', 'KRW'), 'KR_ETC': ('KR', 'KRW'),
           'NYSE': ('US', 'USD'), 'NASDAQ': ('US', 'USD'), 'AMEX': ('US', 'USD'), 'US_ETC': ('US', 'USD')}


def _require(condition):
    if not condition:
        raise ValueError('조건주문 입력을 검증하지 못했다')


def _keys(value, required, optional=frozenset()):
    _require(isinstance(value, dict) and required <= value.keys() <= required | optional)


def _text(value, maximum=256):
    _require(isinstance(value, str) and 1 <= len(value.encode('utf-16-le')) // 2 <= maximum
             and not any(ord(c) <= 31 or 127 <= ord(c) <= 159 for c in value))
    value.encode('utf-8')
    return value


def _id(value):
    _text(value)
    _require(value not in ('.', '..') and '\ufeff' not in value and not re.search(r'[\s/\\?#%:]', value))


def _symbol(value):
    _require(re.fullmatch(r'[A-Za-z0-9.-]{1,32}', _text(value, 32)))


def _decimal(value):
    _require(isinstance(value, str) and len(value) <= 30 and re.fullmatch(r'[0-9]+(?:\.[0-9]+)?', value))
    _require(Decimal(value) > 0)
    integer, _, fraction = value.partition('.')
    integer = integer.lstrip('0') or '0'
    fraction = fraction.rstrip('0')
    return integer + ('.' + fraction if fraction else '')


def _date(value):
    _require(isinstance(value, str) and re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}', value))
    _require(date.fromisoformat(value).isoformat() == value)


def _price(value, market):
    canonical = _decimal(value)
    fraction = canonical.partition('.')[2]
    _require(not fraction if market == 'KR' else len(fraction) <= (4 if Decimal(canonical) < 1 else 2))


def _write(value, market):
    _keys(value, WRITE, {'symbol', 'conditionalOrderId', 'second', 'confirmHighValueOrder'})
    _require(value['type'] in ('SINGLE', 'OCO', 'OTO') and value['orderType'] in ('LIMIT', 'MARKET'))
    _decimal(value['quantity'])
    _date(value['expireDate'])
    _require(('second' in value) == (value['type'] != 'SINGLE'))
    _require(value['type'] == 'SINGLE' or value['orderType'] == 'LIMIT')
    for name in ('first', 'second'):
        if name not in value:
            continue
        leg = value[name]
        _keys(leg, {'orderSide', 'triggerPrice'} | ({'orderPrice'} if value['orderType'] == 'LIMIT' else set()))
        _require(leg['orderSide'] in ('BUY', 'SELL'))
        _decimal(leg['triggerPrice'])
        if 'orderPrice' in leg:
            _price(leg['orderPrice'], market)
    if value['type'] != 'SINGLE':
        _require(value['second']['orderSide'] == 'SELL' and value['first']['orderSide'] == ('SELL' if value['type'] == 'OCO' else 'BUY'))
        if value['type'] == 'OCO':
            _require(Decimal(value['first']['triggerPrice']) > Decimal(value['second']['triggerPrice']))
    if 'confirmHighValueOrder' in value:
        _require(value['confirmHighValueOrder'] is False)


def _detail(value, market):
    _keys(value, DETAIL, {'expireDate', 'second'})
    _id(value['conditionalOrderId'])
    _symbol(value['symbol'])
    _require(value['market'] == market and value['type'] in ('SINGLE', 'OCO', 'OTO')
             and isinstance(value['status'], str) and value['status'] in STATUS and value['orderType'] in ('LIMIT', 'MARKET'))
    _decimal(value['quantity'])
    if 'expireDate' in value:
        _date(value['expireDate'])
    created = _text(value['createdAt'], 64)
    _require(re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})', created))
    _require(datetime.fromisoformat(created.replace('Z', '+00:00')).utcoffset() is not None)
    second = value.get('second')
    _require(second is None if value['type'] == 'SINGLE' else isinstance(second, dict))
    for name in ('first', 'second'):
        if name == 'second' and second is None:
            continue
        leg = value[name]
        _keys(leg, LEG, {'triggerPrice', 'targetProfitRate', 'orderPrice', 'triggeredOrderId'})
        _require(leg['type'] in ('STOP', 'PROFIT_RATE') and isinstance(leg['status'], str) and leg['status'] in STATUS | {'HOLDING', 'CANCELED'}
                 and leg['orderSide'] in ('BUY', 'SELL'))
        for field in ('triggerPrice', 'targetProfitRate', 'orderPrice'):
            if field in leg and leg[field] is not None:
                if field == 'targetProfitRate':
                    _require(isinstance(leg[field], str) and re.fullmatch(r'-?[0-9]+(?:\.[0-9]+)?', leg[field]) and len(leg[field]) <= 30)
                else:
                    _decimal(leg[field])
        if leg.get('triggeredOrderId') is not None:
            _id(leg['triggeredOrderId'])
    if second is not None:
        _require(value['first']['type'] == second['type'])


def _path(value, path):
    for part in path.split('.'):
        if not isinstance(value, dict) or part not in value:
            return None
        value = value[part]
    return value


def _normalization(model, args, changes):
    from .connector_guard_validation import _same_json
    expected = []
    for path in PATHS:
        before, after = _path(model, path), _path(args, path)
        if before is None:
            _require(after is None)
            continue
        canonical = before.upper() if path == 'symbol' else _decimal(before)
        _require(after == canonical)
        if before != after:
            expected.append({'field': path, 'before': before, 'after': after})
    _require(_same_json(changes, expected))
    for field in ('type', 'orderType', 'expireDate', 'conditionalOrderId'):
        _require(_same_json(model.get(field), args.get(field)))
    for leg in ('first', 'second'):
        _require((leg in model) == (leg in args))
        if leg in model:
            _require(_same_json(model[leg]['orderSide'], args[leg]['orderSide']))


def validate_conditional_prepare(payload, original_args, operation, scope):
    """실제 prepare 소비자에서 호출하며 금융 실행 권한을 만들지 않는다."""
    from .connector_guard_validation import _same_json
    args, summary = payload['executionArgs'], payload['summary']
    _keys(summary, SUMMARY)
    _keys(scope, {'account_seq'})
    _require(summary['orderKind'] == 'CONDITIONAL' and summary['operation'] == operation
             and isinstance(summary['account'], str) and re.fullmatch(r'[0-9]{4}', summary['account']))
    _require(isinstance(args.get('market'), str) and args['market'] in MARKETS)
    market, currency = MARKETS[args['market']]
    _require(summary['market'] == market and summary['currency'] == currency and args.get('currency') == currency)
    common = {'account_seq', 'market', 'currency'}
    _require(args.get('account_seq') == scope['account_seq'])
    if operation == 'CREATE':
        _keys(original_args, WRITE | {'symbol'}, {'second', 'confirmHighValueOrder'})
        _keys(args, common | WRITE | {'symbol', 'clientOrderId'}, {'second'})
        _symbol(original_args['symbol'])
        _symbol(args['symbol'])
        _require(summary['conditionalOrderId'] is None and summary['original'] is None)
        _require(isinstance(args['clientOrderId'], str) and str(uuid.UUID(args['clientOrderId'])) == args['clientOrderId'])
    else:
        _keys(original_args, {'conditionalOrderId'} | (WRITE if operation == 'MODIFY' else set()),
              {'second', 'confirmHighValueOrder'} if operation == 'MODIFY' else set())
        _keys(args, common | {'conditionalOrderId', 'expected_conditional_order'} | (WRITE if operation == 'MODIFY' else set()),
              {'second'} if operation == 'MODIFY' else set())
        _id(original_args['conditionalOrderId'])
        _require(args['conditionalOrderId'] == original_args['conditionalOrderId'] == summary['conditionalOrderId'])
        _detail(summary['original'], market)
        _require(summary['original']['conditionalOrderId'] == args['conditionalOrderId']
                 and _same_json(args['expected_conditional_order'], summary['original']))
    if operation != 'CANCEL':
        _write(original_args, market)
        writing = {k: v for k, v in args.items() if k not in common | {'clientOrderId', 'expected_conditional_order'}}
        _write(writing, market)
        _normalization(original_args, args, summary['normalization'])
        for field in ('type', 'quantity', 'orderType', 'expireDate', 'first', 'second'):
            _require(_same_json(summary[field], args.get(field)))
        _require(summary['symbol'] == (args['symbol'] if operation == 'CREATE' else summary['original']['symbol']))
    else:
        _require(summary['normalization'] == [])
        for field in ('symbol', 'type', 'quantity', 'orderType', 'expireDate', 'first', 'second'):
            _require(_same_json(summary[field], summary['original'].get(field)))
    return payload
