import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, Descriptions, Empty, Input, Select, Space, Table, Tag, Typography } from 'antd'
import { ExceptionFilters, ExceptionRecord, fetchExceptions, fetchImportRows, ImportRow } from '../api'
import { QueryFeedback, formatTime } from './QueryFeedback'

const labels: Record<string,string> = { OPEN: '待核查', RESOLVED: '已解决', IGNORED: '已忽略' }
export function IssueCenter({ initialContext, onNavigate }: { initialContext: Record<string,string|number>; onNavigate: (key:string, context?:Record<string,string|number>) => void }) {
  const [filters, setFilters] = useState<ExceptionFilters>({ status: initialContext.status, batchId: initialContext.batchId, sourceRowNumber: initialContext.sourceRowNumber ?? initialContext.rowNumber, exceptionId: initialContext.exceptionId, sheetName: String(initialContext.sheetName ?? '') || undefined })
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<ExceptionRecord>()
  useEffect(()=>{setFilters({status:initialContext.status,batchId:initialContext.batchId,sourceRowNumber:initialContext.sourceRowNumber??initialContext.rowNumber,exceptionId:initialContext.exceptionId,sheetName:String(initialContext.sheetName??'')||undefined});setSearch('');setSelected(undefined)},[initialContext.status,initialContext.batchId,initialContext.sourceRowNumber,initialContext.rowNumber,initialContext.exceptionId,initialContext.sheetName])
  const list = useQuery({ queryKey: ['exceptions',filters,search], queryFn: () => fetchExceptions({...filters,q:search}), retry:false })
  const detail = useQuery({ queryKey:['exception-row',selected?.id], queryFn:() => fetchImportRows(selected!.batchId!,{sheetName:selected!.sheetName??undefined,sourceRowNumber:selected!.sourceRowNumber??undefined,businessKey:selected!.businessKey??undefined}).then(rows => rows.find(row => row.id === selected!.sourceRowId)), enabled:Boolean(selected?.batchId && selected.sourceRowId), retry:false })
  useEffect(() => {
    const targetId = Number(filters.exceptionId)
    if (targetId && list.data) setSelected(list.data.find(row => row.id === targetId))
  }, [filters.exceptionId, list.data])
  const setFilter = (key:keyof ExceptionFilters,value?:string|number) => setFilters(current => ({...current,[key]:value || undefined}))
  const columns = [
    {title:'类型',dataIndex:'type'},{title:'客户编号',dataIndex:'customerCode'},{title:'批次',dataIndex:'batchId'},
    {title:'工作表',dataIndex:'sheetName'},{title:'Excel 原始行',dataIndex:'sourceRowNumber'},
    {title:'业务标识',dataIndex:'businessKey'},{title:'原因',dataIndex:'reason'},
    {title:'状态',dataIndex:'status',render:(v:string)=><Tag color={v==='OPEN'?'warning':v==='RESOLVED'?'success':'default'}>{labels[v]??v}</Tag>},
    {title:'发生时间',dataIndex:'createdAt',render:(v:string)=>formatTime(v)},
    {title:'操作',render:(_:unknown,row:ExceptionRecord)=><Button type="link" onClick={()=>setSelected(row)}>查看详情</Button>},
  ]
  return <>
    <Space wrap className="page-toolbar">
      <Select aria-label="异常状态筛选" placeholder="全部状态" allowClear value={filters.status} onChange={v=>setFilter('status',v)} options={Object.entries(labels).map(([value,label])=>({value,label}))} style={{width:145}} />
      <Input aria-label="异常批次筛选" placeholder="批次编号" inputMode="numeric" value={filters.batchId??''} onChange={e=>setFilter('batchId',e.target.value)} style={{width:140}} />
      <Select aria-label="异常工作表筛选" placeholder="全部工作表" allowClear value={filters.sheetName} onChange={v=>setFilter('sheetName',v)} options={['客户基础信息','欠费账单明细','专线合同台账','跟进催缴记录','预警规则配置'].map(value=>({value,label:value}))} style={{width:185}} />
      <Input aria-label="异常行号筛选" placeholder="Excel 原始行号" inputMode="numeric" value={filters.sourceRowNumber??''} onChange={e=>setFilter('sourceRowNumber',e.target.value)} style={{width:155}} />
      <Input.Search aria-label="异常关键字" placeholder="客户、标识或原因" allowClear value={search} onChange={e=>setSearch(e.target.value)} style={{width:220}} />
      <Button onClick={()=>{setFilters({});setSearch('')}}>清除筛选</Button>
    </Space>
    <QueryFeedback error={list.error} retry={()=>list.refetch()} />
    <Card title={`异常核查 · ${list.data?.length??0} 条`}>
      <Table<ExceptionRecord> rowKey="id" loading={list.isFetching} dataSource={list.data??[]} columns={columns} scroll={{x:1250}} pagination={{pageSize:10}} rowClassName={row=>row.id===Number(filters.exceptionId)?'exception-target-row':''} />
      {list.data?.length===0&&!list.isFetching&&<Empty description="没有符合条件的异常记录" />}
    </Card>
    {selected&&<Card className="table-spaced" title="异常详情" extra={<Button disabled={!selected.batchId} onClick={()=>selected.batchId&&onNavigate('import',{batchId:selected.batchId,exceptionId:selected.id,sourceRowNumber:selected.sourceRowNumber??0,sheetName:selected.sheetName??'',businessKey:selected.businessKey??''})}>查看批次</Button>}>
      <Descriptions bordered column={{xs:1,sm:2,lg:3}} items={[
        {key:'id',label:'异常编号',children:selected.id},{key:'status',label:'状态',children:labels[selected.status]??selected.status},
        {key:'batch',label:'批次编号',children:selected.batchId??'无批次'},{key:'sheet',label:'工作表',children:selected.sheetName??'无工作表'},
        {key:'row',label:'Excel 原始行',children:selected.sourceRowNumber??'无来源行'},{key:'customer',label:'客户编号',children:selected.customerCode??'未关联客户'},
        {key:'key',label:'业务标识',children:selected.businessKey??'—'},{key:'time',label:'发生时间',children:formatTime(selected.createdAt)},
        {key:'reason',label:'原因',span:3,children:<Typography.Paragraph style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{selected.reason}</Typography.Paragraph>},
        {key:'resolution',label:'处理结论',span:3,children:selected.resolution??'尚无处理结论'},
      ]}/>
      {detail.isError&&<Alert type="error" showIcon message={(detail.error as Error).message}/>}
      {detail.isSuccess&&!detail.data&&<Alert type="warning" showIcon message="未能读取原始导入行" description="该异常没有关联导入行，或原始行不在当前账号授权范围内。"/>}
      {detail.data&&<><Typography.Title level={5} style={{marginTop:18}}>原始行数据</Typography.Title><Table size="small" rowKey="key" pagination={false} dataSource={Object.entries(detail.data.values??{}).map(([key,value])=>({key,value}))} columns={[{title:'字段',dataIndex:'key'},{title:'Excel 原始值',dataIndex:'value'}]}/><Alert style={{marginTop:12}} type="info" message={`导入行状态：${detail.data.status} · ${detail.data.category}`} description={detail.data.errorMessage??selected.reason}/></>}
    </Card>}
  </>
}
