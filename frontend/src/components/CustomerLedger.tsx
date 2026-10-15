import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, DatePicker, Descriptions, Drawer, Empty, Input, Select, Space, Table, Tag } from 'antd'
import type { TableProps } from 'antd'
import dayjs, { Dayjs } from 'dayjs'
import { Customer, CustomerFilters, fetchCustomerDetail, fetchCustomerPage, fetchServicePage, fetchServiceDetail } from '../api'
import { QueryFeedback } from './QueryFeedback'

const statusColors: Record<string,string> = { '高风险':'error','中风险':'warning','低风险':'success','待核查':'processing' }
const asText = (value: unknown) => value == null || value === '' ? '—' : String(value)
const money = (value: unknown) => value == null ? '—' : `¥${Number(value).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})}`

export function CustomerLedger({ context, onClearContext }: { context: Record<string,string|number>; onClearContext:()=>void }) {
  const [filters,setFilters] = useState<CustomerFilters>({ page:1,pageSize:10,sort:'customerCode',direction:'asc' })
  const [selected,setSelected] = useState<number>()
  const [selectedService,setSelectedService] = useState<number>()
  const customerId = Number(context.customerId) || undefined
  const query = useQuery({ queryKey:['customer-page',filters,context], queryFn:()=>fetchCustomerPage({...filters,customerId,riskLevel:typeof context.riskLevel==='string'?context.riskLevel:filters.riskLevel,expiryFrom:typeof context.expiryFrom==='string'?context.expiryFrom:filters.expiryFrom,expiryTo:typeof context.expiryTo==='string'?context.expiryTo:filters.expiryTo}),retry:false })
  const detail = useQuery({ queryKey:['customer-detail',selected],queryFn:()=>fetchCustomerDetail(selected!),enabled:selected!==undefined,retry:false })
  const servicesPage = useQuery({queryKey:['expiry-business-page',filters.q,filters.expiryFrom,filters.expiryTo,filters.page,filters.pageSize],queryFn:()=>fetchServicePage({q:filters.q,from:typeof context.expiryFrom==='string'?context.expiryFrom:filters.expiryFrom,to:typeof context.expiryTo==='string'?context.expiryTo:filters.expiryTo,page:filters.page,pageSize:filters.pageSize}),enabled:Boolean(context.expiryFrom||context.expiryTo),retry:false})
  const serviceDetail = useQuery({queryKey:['service-detail',selectedService],queryFn:()=>fetchServiceDetail(selectedService!),enabled:selectedService!==undefined,retry:false})
  const set = (key:keyof CustomerFilters,value:unknown) => setFilters(current=>({...current,[key]:value || undefined,page:1}))
  const columns: TableProps<Customer>['columns'] = [
    {title:'客户编号',dataIndex:'customerCode',sorter:true,sortOrder:filters.sort==='customerCode'?(filters.direction==='asc'?'ascend':'descend'):undefined},
    {title:'客户名称',dataIndex:'name',sorter:true,render:(v:string)=><strong>{v}</strong>},
    {title:'客户经理',dataIndex:'manager',sorter:true},{title:'业务类型',dataIndex:'type'},
    {title:'累计欠费',dataIndex:'balance',sorter:true,render:(v:number)=>money(v)},
    {title:'风险状态',dataIndex:'status',render:(v:string)=><Tag color={statusColors[v]??'default'}>{v}</Tag>},
    {title:'最近到期',dataIndex:'due',sorter:true},
    {title:'操作',render:(_,row)=><Button type="link" onClick={()=>setSelected(row.id)}>客户详情</Button>},
  ]
  const change: TableProps<Customer>['onChange'] = (pagination,_filters,sorter) => {
    const s=Array.isArray(sorter)?sorter[0]:sorter
    const allowed: Record<string,string>={customerCode:'customerCode',name:'name',manager:'manager',balance:'balance',status:'status',due:'due'}
    setFilters(current=>({...current,page:pagination.current??1,pageSize:pagination.pageSize??10,...(s.order&&s.field?{sort:allowed[String(s.field)]??'customerCode',direction:s.order==='ascend'?'asc':'desc'}:{})}))
  }
  const range = (values:[Dayjs|null,Dayjs|null]|null,key:'expiryFrom'|'expiryTo') => { const a=values?.[0]?.format('YYYY-MM-DD');const b=values?.[1]?.format('YYYY-MM-DD');setFilters(current=>({...current,page:1,expiryFrom:a,expiryTo:b})) }
  const d=detail.data
  return <>
    <Space wrap className="page-toolbar">
      <Input.Search aria-label="客户台账搜索" placeholder="客户编号、名称或业务编号" allowClear value={filters.q} onChange={e=>set('q',e.target.value)} style={{width:260}} />
      <Select aria-label="风险级别筛选" placeholder="全部风险" allowClear value={filters.riskLevel ?? (typeof context.riskLevel==='string'?context.riskLevel:undefined)} onChange={v=>set('riskLevel',v)} options={[['HIGH','高风险'],['MEDIUM','中风险'],['LOW','低风险'],['PENDING','待核查']].map(([value,label])=>({value,label}))} style={{width:140}} />
      <Input aria-label="客户经理筛选" placeholder="客户经理" allowClear value={filters.manager} onChange={e=>set('manager',e.target.value)} style={{width:140}} />
      <Input aria-label="业务类型筛选" placeholder="业务类型" allowClear value={filters.businessType} onChange={e=>set('businessType',e.target.value)} style={{width:150}} />
      <DatePicker.RangePicker aria-label="到期日期筛选" value={filters.expiryFrom||filters.expiryTo?[filters.expiryFrom?dayjs(filters.expiryFrom):null,filters.expiryTo?dayjs(filters.expiryTo):null]:null} onChange={v=>range(v,'expiryFrom')} />
      <Select aria-label="异常状态筛选" placeholder="全部异常" allowClear value={filters.exceptionStatus} onChange={v=>set('exceptionStatus',v)} options={['OPEN','RESOLVED','IGNORED'].map(value=>({value,label:value}))} style={{width:135}} />
      <Button onClick={()=>{setFilters({page:1,pageSize:10,sort:'customerCode',direction:'asc'});onClearContext()}}>清除筛选</Button>
    </Space>
    {(customerId||context.riskLevel||context.expiryFrom)&&<Alert style={{marginBottom:12}} type="info" showIcon message={customerId?`客户 #${customerId}`:context.riskLevel?`风险条件：${context.riskLevel}`:`到期范围：${context.expiryFrom} 至 ${context.expiryTo??context.expiryFrom}`} />}
    {context.expiryFrom||context.expiryTo ? <>
      <QueryFeedback error={servicesPage.error} retry={()=>servicesPage.refetch()} />
      <Card title="到期业务列表"><Table rowKey="id" loading={servicesPage.isFetching} dataSource={servicesPage.data?.items??[]} pagination={{current:servicesPage.data?.page??filters.page,pageSize:servicesPage.data?.pageSize??filters.pageSize,total:servicesPage.data?.total??0,showSizeChanger:true}} onChange={pagination=>setFilters(c=>({...c,page:pagination.current??1,pageSize:pagination.pageSize??10}))} locale={{emptyText:<Empty description="所选日期范围内没有到期业务"/>}} scroll={{x:900}} columns={[{title:'客户编号',dataIndex:'customerCode'},{title:'客户名称',dataIndex:'customerName'},{title:'业务编号',dataIndex:'serviceCode'},{title:'业务类型',dataIndex:'serviceType'},{title:'带宽',dataIndex:'bandwidth'},{title:'到期日',dataIndex:'expiryDate'},{title:'续签状态',dataIndex:'renewalStatus'},{title:'操作',render:(_,row)=><Button type="link" onClick={()=>setSelectedService(row.id)}>业务详情</Button>}]}/></Card>
    </> : <>
      <QueryFeedback error={query.error} retry={()=>query.refetch()} />
      <Card title="客户统一台账"><Table<Customer> rowKey="id" columns={columns} dataSource={query.data?.items??[]} loading={query.isFetching} onChange={change} pagination={{current:query.data?.page??filters.page,pageSize:query.data?.pageSize??filters.pageSize,total:query.data?.total??0,showSizeChanger:true,pageSizeOptions:[10,20,50,100]}} locale={{emptyText:<Empty description="没有符合条件的客户"/>}} scroll={{x:1050}} /></Card>
    </>}
    <Drawer title="客户详情与历史" width={900} open={selected!==undefined} onClose={()=>setSelected(undefined)}>
      <QueryFeedback error={detail.error} retry={()=>detail.refetch()} />
      {d&&<>
        <Descriptions bordered size="small" column={{xs:1,sm:2}} items={[
          {key:'code',label:'客户编号',children:asText(d.customer.customer_code)},{key:'name',label:'客户名称',children:asText(d.customer.customer_name)},
          {key:'manager',label:'责任客户经理',children:asText(d.customer.manager_name)},{key:'phone',label:'联系电话',children:asText(d.customer.contact_phone)},
          {key:'address',label:'客户地址',children:asText(d.customer.customer_address)},{key:'updated',label:'最近更新时间',children:asText(d.customer.updated_at)},
          {key:'source',label:'数据来源',children:asText(d.customer.data_source)},{key:'remarks',label:'备注',children:asText(d.customer.remarks)},
        ]}/>
        <Card size="small" title={`关联业务 · ${d.services.length}`} className="table-spaced"><Table size="small" rowKey="id" dataSource={d.services} pagination={false} scroll={{x:700}} columns={[{title:'业务编号',dataIndex:'service_code'},{title:'业务类型',dataIndex:'service_type'},{title:'带宽',dataIndex:'bandwidth'},{title:'年合同额',dataIndex:'annual_amount',render:money},{title:'服务到期日',dataIndex:'service_expiry_date',render:(v,record)=>asText(v??record.expiry_date)},{title:'来源批次',dataIndex:'source_batch_id'}]}/></Card>
        <Card size="small" title={`欠费账单 · ${d.bills.length}`} className="table-spaced"><Table size="small" rowKey="id" dataSource={d.bills} pagination={{pageSize:5}} scroll={{x:700}} columns={[{title:'账单编号',dataIndex:'bill_code'},{title:'账期',dataIndex:'billing_period'},{title:'应缴',dataIndex:'due_amount',render:money},{title:'已缴',dataIndex:'paid_amount',render:money},{title:'欠费',dataIndex:'arrears_amount',render:money},{title:'缴费截止日',dataIndex:'due_date'},{title:'来源批次',dataIndex:'source_batch_id'}]}/></Card>
        <Card size="small" title={`到期记录 · ${d.expiries.length}`} className="table-spaced"><Table size="small" rowKey="id" dataSource={d.expiries} pagination={false} columns={[{title:'合同编号',dataIndex:'contract_code'},{title:'到期日期',dataIndex:'expiry_date'},{title:'续签状态',dataIndex:'renewal_status'},{title:'来源批次',dataIndex:'source_batch_id'}]}/></Card>
        <Card size="small" title={`风险历史 · ${d.risks.length}`} className="table-spaced"><Table size="small" rowKey="id" dataSource={d.risks} pagination={{pageSize:5}} columns={[{title:'规则',dataIndex:'rule_id'},{title:'观察值',dataIndex:'observed_value'},{title:'阈值',dataIndex:'threshold_value'},{title:'等级',dataIndex:'risk_level'},{title:'状态',dataIndex:'status'},{title:'计算时间',dataIndex:'calculated_at'},{title:'来源批次',dataIndex:'source_batch_id'}]}/></Card>
        <Card size="small" title={`任务历史 · ${d.tasks.length}`} className="table-spaced"><Table size="small" rowKey="id" dataSource={d.tasks} pagination={{pageSize:5}} columns={[{title:'任务',dataIndex:'title'},{title:'负责人',dataIndex:'owner_name'},{title:'截止日',dataIndex:'deadline'},{title:'状态',dataIndex:'task_status'},{title:'来源批次',dataIndex:'source_batch_id'}]}/></Card>
        <Card size="small" title={`跟进记录 · ${d.followUps.length}`} className="table-spaced"><Table size="small" rowKey="id" dataSource={d.followUps} pagination={{pageSize:5}} columns={[{title:'记录时间',dataIndex:'created_at'},{title:'处理人',dataIndex:'created_by'},{title:'内容',dataIndex:'note'},{title:'下一步',dataIndex:'next_action'}]}/></Card>
        <Card size="small" title={`导入来源批次 · ${d.batches.length}`} className="table-spaced"><Table size="small" rowKey="id" dataSource={d.batches} pagination={false} columns={[{title:'批次',dataIndex:'id'},{title:'文件',dataIndex:'file_name'},{title:'来源',dataIndex:'source_type'},{title:'状态',dataIndex:'import_status'},{title:'导入时间',dataIndex:'created_at'}]}/></Card>
        {![d.services,d.bills,d.expiries,d.risks,d.tasks,d.followUps,d.batches].some(rows=>rows.length>0)&&<Empty description="暂无关联业务或历史记录"/>}
      </>}
    </Drawer>
    <Drawer title="业务详情" width={760} open={selectedService!==undefined} onClose={()=>setSelectedService(undefined)}>
      <QueryFeedback error={serviceDetail.error} retry={()=>serviceDetail.refetch()} />
      {serviceDetail.data&&<>
        <Descriptions bordered size="small" column={2} items={Object.entries(serviceDetail.data.service).map(([key,value])=>({key,label:key,children:asText(value)}))}/>
        <Card size="small" title={`关联账单 · ${serviceDetail.data.bills.length}`} className="table-spaced"><Table size="small" rowKey="id" dataSource={serviceDetail.data.bills} pagination={false} columns={[{title:'账单编号',dataIndex:'bill_code'},{title:'账期',dataIndex:'billing_period'},{title:'欠费',dataIndex:'arrears_amount',render:money},{title:'截止日',dataIndex:'due_date'}]}/></Card>
        <Card size="small" title={`到期记录 · ${serviceDetail.data.expiries.length}`} className="table-spaced"><Table size="small" rowKey="id" dataSource={serviceDetail.data.expiries} pagination={false} columns={[{title:'合同编号',dataIndex:'contract_code'},{title:'到期日',dataIndex:'expiry_date'},{title:'续签状态',dataIndex:'renewal_status'},{title:'来源批次',dataIndex:'source_batch_id'}]}/></Card>
        <Card size="small" title={`风险记录 · ${serviceDetail.data.risks.length}`} className="table-spaced"><Table size="small" rowKey="id" dataSource={serviceDetail.data.risks} pagination={{pageSize:5}} columns={[{title:'观察值',dataIndex:'observed_value'},{title:'阈值',dataIndex:'threshold_value'},{title:'风险等级',dataIndex:'risk_level'},{title:'计算时间',dataIndex:'calculated_at'}]}/></Card>
        <Card size="small" title={`督办任务 · ${serviceDetail.data.tasks.length}`} className="table-spaced"><Table size="small" rowKey="id" dataSource={serviceDetail.data.tasks} pagination={{pageSize:5}} columns={[{title:'任务',dataIndex:'title'},{title:'负责人',dataIndex:'owner_name'},{title:'状态',dataIndex:'task_status'},{title:'截止日',dataIndex:'deadline'}]}/></Card>
      </>}
    </Drawer>
  </>
}
