"""Read-only precheck by default; explicit --replace replaces local business data."""
import argparse
import datetime as dt
import hashlib
import json
import os
from decimal import Decimal
from pathlib import Path

import openpyxl
import pymysql

FILES = [
    ('customer', '客户信息表_2026.10.09.xlsx', '客户编号 客户名称 客户类型 责任客户经理 联系电话 客户地址 接入专线总数 最近更新日期 数据来源 备注'.split()),
    ('bill', '欠费账单明细.xlsx', '账单编号 客户编号 账期 出账日期 应缴金额(元) 已缴金额(元) 欠费金额(元) 缴费截止日 账单状态 导入批次号'.split()),
    ('service', '专线合同台账.xlsx', '业务编号 客户编号 业务类型 业务带宽 合同签订日 服务开始日 服务到期日 年合同额(元) 续签状态 最近跟进日 备注'.split()),
    ('task', '跟进催款记录.xlsx', '记录编号 客户编号 关联业务编号 任务类型 任务标题 责任人 计划完成日 任务状态 处理结果 记录时间'.split()),
]


def date(value):
    if value is None or value == '':
        return None
    if isinstance(value, dt.datetime):
        return value.date()
    if isinstance(value, dt.date):
        return value
    return dt.date.fromisoformat(str(value).strip())


def money(value):
    amount = Decimal(str(value))
    if not amount.is_finite() or amount < 0 or amount != amount.quantize(Decimal('0.01')):
        raise ValueError('Invalid money: ' + str(value))
    return amount


def read_files(directory):
    datasets = {}
    for kind, name, headers in FILES:
        path = directory / name
        content = path.read_bytes()
        wb = openpyxl.load_workbook(path, data_only=False, read_only=True)
        if len(wb.worksheets) != 1:
            raise ValueError(name + ': expected one worksheet')
        sheet = wb.worksheets[0]
        values = list(sheet.iter_rows(values_only=True))
        actual = list(values[0])
        while actual and actual[-1] is None:
            actual.pop()
        if [str(x).strip().lstrip('*') for x in actual] != headers:
            raise ValueError(name + ': headers do not match: ' + repr(actual))
        rows = []
        for number, cells in enumerate(values[1:], 2):
            if not any(x is not None and str(x).strip() for x in cells):
                continue
            if any(isinstance(x, str) and x.startswith('=') for x in cells):
                raise ValueError(name + ': formula at row ' + str(number))
            if any(x is not None and str(x).strip() for x in cells[len(headers):]):
                raise ValueError(name + ': unexpected extra columns')
            row = dict(zip(headers, cells))
            row['_row'] = number
            rows.append(row)
        wb.close()
        datasets[kind] = dict(name=name, sheet=sheet.title, sha=hashlib.sha256(content).hexdigest(), rows=rows)
    customers = {r['客户编号'] for r in datasets['customer']['rows']}
    if len(customers) != len(datasets['customer']['rows']):
        raise ValueError('Duplicate customer code')
    services = {(r['客户编号'], r['业务编号']) for r in datasets['service']['rows']}
    bills, tasks = set(), set()
    for kind, ds in datasets.items():
        for r in ds['rows']:
            if not r.get('客户编号'):
                raise ValueError(ds['name'] + ': customer code required')
            r['_excluded'] = None if r['客户编号'] in customers else '客户关联失败'
            if kind == 'customer':
                for field in ('客户名称', '责任客户经理', '客户类型'):
                    if not r[field]:
                        raise ValueError(field + ': required')
                date(r['最近更新日期'])
                if r['接入专线总数'] is not None and (int(r['接入专线总数']) != r['接入专线总数'] or int(r['接入专线总数']) < 0):
                    raise ValueError('Invalid service count')
            elif kind == 'bill':
                key = (r['客户编号'], r['账单编号'], r['账期'])
                if not r['账单编号'] or key in bills:
                    raise ValueError('Missing/duplicate bill code')
                bills.add(key)
                dt.datetime.strptime(str(r['账期']), '%Y-%m')
                due, paid = money(r['应缴金额(元)']), money(r['已缴金额(元)'])
                if paid > due or (r['欠费金额(元)'] is not None and money(r['欠费金额(元)']) != due - paid):
                    raise ValueError('Bill amount mismatch')
                if not date(r['出账日期']) or not date(r['缴费截止日']):
                    raise ValueError('Bill dates required')
            elif kind == 'service':
                for f in ('业务编号', '业务类型', '合同签订日', '服务开始日', '服务到期日'):
                    if not r[f]:
                        raise ValueError(f + ': required')
                if date(r['服务开始日']) > date(r['服务到期日']):
                    raise ValueError('Service ends before it starts')
                date(r['合同签订日']); date(r['最近跟进日'])
                if r['年合同额(元)'] is not None:
                    money(r['年合同额(元)'])
            else:
                for f in ('记录编号', '任务类型', '任务标题', '责任人', '计划完成日', '记录时间'):
                    if not r[f]:
                        raise ValueError(f + ': required')
                if r['记录编号'] in tasks:
                    raise ValueError('Duplicate task code')
                tasks.add(r['记录编号'])
                date(r['计划完成日']); date(r['记录时间'])
                if r['关联业务编号'] and (r['客户编号'], r['关联业务编号']) not in services:
                    r['_excluded'] = '业务关联失败'
                if r['任务状态'] not in ('待处理', '处理中', '进行中', '待核实', '已完成', '已关闭'):
                    raise ValueError('Unsupported task status')
    if len(services) != len(datasets['service']['rows']):
        raise ValueError('Duplicate service code')
    return datasets


def connect():
    return pymysql.connect(host='127.0.0.1', port=3306, user=os.getenv('DB_USERNAME', 'root'),
                           password=os.getenv('DB_PASSWORD', ''), database='arrears_management',
                           charset='utf8mb4', autocommit=False)


def migrate(cur):
    # DDL is additive and committed by MySQL, separate from the replacement transaction.
    columns = {
        'customer': {'contact_phone': 'VARCHAR(50) NULL', 'customer_address': 'VARCHAR(500) NULL',
                     'service_count': 'INT NULL', 'source_updated_date': 'DATE NULL',
                     'data_source': 'VARCHAR(200) NULL', 'remarks': 'TEXT NULL', 'balance_known': 'TINYINT(1) NOT NULL DEFAULT 0'},
        'business_service': {'bandwidth': 'VARCHAR(100) NULL', 'contract_signed_date': 'DATE NULL',
                             'service_start_date': 'DATE NULL', 'annual_amount': 'DECIMAL(18,2) NULL',
                             'last_follow_up_date': 'DATE NULL', 'remarks': 'TEXT NULL'},
        'arrears_bill': {'bill_status': 'VARCHAR(30) NULL', 'external_batch_code': 'VARCHAR(100) NULL'},
        'task': {'source_record_code': 'VARCHAR(160) NULL', 'source_batch_id': 'BIGINT NULL',
                 'processing_result': 'TEXT NULL', 'source_recorded_at': 'DATETIME NULL'},
    }
    for table, fields in columns.items():
        cur.execute('SHOW COLUMNS FROM `' + table + '`')
        existing = {r[0] for r in cur.fetchall()}
        for field, sql_type in fields.items():
            if field not in existing:
                cur.execute('ALTER TABLE `' + table + '` ADD COLUMN `' + field + '` ' + sql_type)
    indexes = [('customer', 'uk_customer_code', 'customer_code'),
               ('task', 'uk_task_import_record', 'customer_id, source_record_code'),
               ('contract_expiry', 'uk_expiry_service_date', 'service_id, expiry_date')]
    for table, name, fields in indexes:
        cur.execute('SHOW INDEX FROM `' + table + '` WHERE Key_name=%s', (name,))
        if not cur.fetchone():
            cur.execute('ALTER TABLE `' + table + '` ADD UNIQUE KEY `' + name + '` (' + fields + ')')


def insert(cur, table, values):
    fields = list(values)
    cur.execute('INSERT INTO `' + table + '` (' + ','.join('`' + f + '`' for f in fields) +
                ') VALUES (' + ','.join(['%s'] * len(fields)) + ')', list(values.values()))
    return cur.lastrowid


def replace(datasets, backup):
    if not backup.is_file() or backup.stat().st_size < 1000:
        raise ValueError('A verified database backup is required')
    db = connect()
    try:
        with db.cursor() as cur:
            cur.execute("SELECT GET_LOCK('mobiletools_excel_replace',10)")
            if cur.fetchone()[0] != 1:
                raise RuntimeError('Another replacement is active')
            cur.execute('SELECT id,username,real_name FROM app_user')
            users = {r[1]: r[0] for r in cur.fetchall()}
            admin = users['admin']
            migrate(cur)
            db.commit()
            db.begin()
            before = {}
            tables = ['risk_result', 'exception_record', 'follow_up', 'task', 'user_customer_scope',
                      'arrears_bill', 'contract_expiry', 'business_service', 'customer',
                      'import_row', 'import_batch', 'risk_recalculation_run']
            for table in tables:
                cur.execute('SELECT COUNT(*) FROM `' + table + '`')
                before[table] = cur.fetchone()[0]
                cur.execute('DELETE FROM `' + table + '`')
            batches = {}
            for kind, ds in datasets.items():
                excluded = sum(bool(r['_excluded']) for r in ds['rows'])
                batches[kind] = insert(cur, 'import_batch', dict(file_name=ds['name'], source_type='EXCEL',
                    file_sha256=ds['sha'], idempotency_key=kind + ':' + ds['sha'], operator_user_id=admin,
                    import_status='COMPLETED', total_rows=len(ds['rows']), valid_rows=len(ds['rows'])-excluded,
                    excluded_rows=excluded, inserted_rows=len(ds['rows'])-excluded,
                    started_at=dt.datetime.now(), finished_at=dt.datetime.now()))
                for r in ds['rows']:
                    payload = {k: v for k, v in r.items() if not k.startswith('_')}
                    key = r.get('账单编号') or r.get('业务编号') or r.get('记录编号') or r['客户编号']
                    rowid = insert(cur, 'import_row', dict(batch_id=batches[kind], sheet_name=ds['sheet'],
                        source_row_number=r['_row'], business_key=key, customer_id=r['客户编号'],
                        row_status='EXCLUDED' if r['_excluded'] else 'VALID',
                        row_category='EXCLUDED' if r['_excluded'] else 'INSERTED', error_message=r['_excluded'],
                        raw_payload=json.dumps(payload, ensure_ascii=False, default=str)))
                    if r['_excluded']:
                        insert(cur, 'exception_record', dict(batch_id=batches[kind], source_row_id=rowid,
                            exception_type='ASSOCIATION_FAILED', business_key=key, reason=r['_excluded']))
            customers, services = {}, {}
            manager_accounts = {'黄铃': 'huangling', '周敏': 'zhoumin', '陈晨': 'chenchen'}
            for r in datasets['customer']['rows']:
                manager = users.get(manager_accounts.get(r['责任客户经理']))
                cid = insert(cur, 'customer', dict(customer_code=r['客户编号'], customer_name=r['客户名称'],
                    manager_name=r['责任客户经理'], manager_user_id=manager, source_batch_id=batches['customer'],
                    business_type=r['客户类型'], risk_status='待核查', due_summary='暂无到期信息',
                    contact_phone=str(r['联系电话']) if r['联系电话'] is not None else None,
                    customer_address=r['客户地址'], service_count=r['接入专线总数'],
                    source_updated_date=date(r['最近更新日期']), data_source=r['数据来源'], remarks=r['备注']))
                customers[r['客户编号']] = cid
                if manager:
                    insert(cur, 'user_customer_scope', dict(user_id=manager, customer_id=cid, created_by=admin))
            for r in datasets['service']['rows']:
                if r['_excluded']: continue
                cid = customers[r['客户编号']]
                sid = insert(cur, 'business_service', dict(customer_id=cid, service_code=r['业务编号'],
                    service_type=r['业务类型'], source_batch_id=batches['service'], bandwidth=r['业务带宽'],
                    contract_signed_date=date(r['合同签订日']), service_start_date=date(r['服务开始日']),
                    annual_amount=money(r['年合同额(元)']) if r['年合同额(元)'] is not None else None,
                    last_follow_up_date=date(r['最近跟进日']), remarks=r['备注']))
                services[(r['客户编号'], r['业务编号'])] = sid
                insert(cur, 'contract_expiry', dict(customer_id=cid, service_id=sid, expiry_date=date(r['服务到期日']),
                    renewal_status=r['续签状态'] or 'PENDING', source_batch_id=batches['service']))
            for r in datasets['bill']['rows']:
                if r['_excluded']: continue
                due, paid = money(r['应缴金额(元)']), money(r['已缴金额(元)'])
                insert(cur, 'arrears_bill', dict(customer_id=customers[r['客户编号']], bill_code=r['账单编号'],
                    billing_period=str(r['账期']), due_amount=due, paid_amount=paid, arrears_amount=due-paid,
                    bill_date=date(r['出账日期']), due_date=date(r['缴费截止日']),
                    source_batch_id=batches['bill'], bill_status=r['账单状态'], external_batch_code=str(r['导入批次号'])))
            for r in datasets['task']['rows']:
                if r['_excluded']: continue
                tid = insert(cur, 'task', dict(customer_id=customers[r['客户编号']],
                    service_id=services.get((r['客户编号'], r['关联业务编号'])), source_type='EXCEL',
                    source_record_code=r['记录编号'], source_batch_id=batches['task'], task_type=r['任务类型'],
                    title=r['任务标题'], owner_name=r['责任人'], deadline=date(r['计划完成日']),
                    task_status=r['任务状态'], priority='普通', created_by=admin, processing_result=r['处理结果'],
                    source_recorded_at=date(r['记录时间'])))
                # A task import is not a fabricated human follow-up. Only real nonempty results become notes.
                if r['处理结果'] and str(r['处理结果']).strip():
                    insert(cur, 'follow_up', dict(task_id=tid, note=r['处理结果'], source_record_code=r['记录编号'],
                        source_batch_id=batches['task'], created_by=r['责任人'], created_at=date(r['记录时间'])))
            cur.execute("UPDATE customer c SET due_summary=(SELECT CONCAT(MIN(e.expiry_date),' 到期') FROM contract_expiry e WHERE e.customer_id=c.id) WHERE EXISTS(SELECT 1 FROM contract_expiry e WHERE e.customer_id=c.id)")
            insert(cur, 'operation_audit_log', dict(operator_user_id=admin, operation_type='REPLACE_EXCEL_BUSINESS_DATA',
                target_type='DATABASE', result='SUCCESS', detail=json.dumps(dict(before=before, backup=str(backup)), ensure_ascii=False)))
            counts = {}
            for table in tables + ['app_user', 'risk_rule']:
                cur.execute('SELECT COUNT(*) FROM `' + table + '`')
                counts[table] = cur.fetchone()[0]
            assert counts['customer'] == len(datasets['customer']['rows'])
            for kind, table in [('bill','arrears_bill'), ('service','business_service'), ('task','task')]:
                assert counts[table] == sum(not r['_excluded'] for r in datasets[kind]['rows'])
            cur.execute('SELECT COUNT(*) FROM arrears_bill WHERE arrears_amount<>due_amount-paid_amount')
            assert cur.fetchone()[0] == 0
            db.commit()
            print(json.dumps(dict(status='COMMITTED', counts=counts, batches=batches), ensure_ascii=False))
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=Path)
    parser.add_argument('--replace', action='store_true')
    parser.add_argument('--backup', type=Path)
    args = parser.parse_args()
    datasets = read_files(args.directory)
    print(json.dumps({k: dict(file=v['name'], sha256=v['sha'], rows=len(v['rows']),
          excluded=sum(bool(r['_excluded']) for r in v['rows'])) for k, v in datasets.items()}, ensure_ascii=False))
    if args.replace:
        if args.backup is None:
            parser.error('--backup is required with --replace')
        replace(datasets, args.backup)
