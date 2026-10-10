USE arrears_management;

INSERT INTO customer (customer_name, manager_name, business_type, balance, risk_status, due_summary)
SELECT '华东智联科技有限公司', '李晓敏', '专线', 38600.00, '高风险', '已逾期 12 天'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE customer_name = '华东智联科技有限公司');

INSERT INTO customer (customer_name, manager_name, business_type, balance, risk_status, due_summary)
SELECT '城市建设集团', '王芳', '集团专线', 12800.00, '待跟进', '7 天后到期'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE customer_name = '城市建设集团');

INSERT INTO customer (customer_name, manager_name, business_type, balance, risk_status, due_summary)
SELECT '新城物业服务有限公司', '陈琳', '云业务', 2300.00, '需核实', '余额低于阈值'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE customer_name = '新城物业服务有限公司');

INSERT INTO customer (customer_name, manager_name, business_type, balance, risk_status, due_summary)
SELECT '远海物流发展有限公司', '赵强', '专线', 8900.00, '正常', '30 天后到期'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE customer_name = '远海物流发展有限公司');

INSERT INTO task (title, owner_name, deadline, task_status, priority)
SELECT '联系华东智联科技有限公司催收欠费', '李晓敏', '2026-10-10', '待处理', '高'
WHERE NOT EXISTS (SELECT 1 FROM task WHERE title = '联系华东智联科技有限公司催收欠费');

INSERT INTO task (title, owner_name, deadline, task_status, priority)
SELECT '确认城市建设集团专线续签意向', '王芳', '2026-10-12', '进行中', '中'
WHERE NOT EXISTS (SELECT 1 FROM task WHERE title = '确认城市建设集团专线续签意向');

INSERT INTO task (title, owner_name, deadline, task_status, priority)
SELECT '核实新城物业余额预警准确性', '陈琳', '2026-10-11', '待处理', '高'
WHERE NOT EXISTS (SELECT 1 FROM task WHERE title = '核实新城物业余额预警准确性');

INSERT INTO task (title, owner_name, deadline, task_status, priority)
SELECT '处理 9 月欠费明细异常记录', '后台台席', '2026-10-15', '已完成', '低'
WHERE NOT EXISTS (SELECT 1 FROM task WHERE title = '处理 9 月欠费明细异常记录');
