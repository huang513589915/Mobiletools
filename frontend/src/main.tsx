import React from 'react'
import ReactDOM from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import App from './App'
import './styles.css'

const queryClient = new QueryClient()

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <ConfigProvider locale={zhCN} theme={{
        token: {
          colorPrimary: '#2563eb',
          colorInfo: '#2563eb',
          colorSuccess: '#10b981',
          colorWarning: '#f59e0b',
          colorError: '#ef4444',
          colorBgLayout: '#f4f7fb',
          colorBgContainer: '#ffffff',
          colorText: '#1f2937',
          colorTextSecondary: '#526174',
          colorBorderSecondary: '#e2e8f0',
          borderRadius: 10,
          controlHeight: 40,
          fontSize: 14,
          lineHeight: 1.55,
          fontFamily: '"Inter", "Microsoft YaHei", "PingFang SC", sans-serif',
        },
        components: {
          Menu: { itemBg: 'transparent', itemColor: '#526174', itemSelectedBg: '#eff6ff', itemSelectedColor: '#1d4ed8', itemHoverBg: '#f4f7fb' },
          Table: { headerBg: '#f8fafc', headerColor: '#526174', rowHoverBg: '#f8fbff', borderColor: '#e2e8f0' },
          Card: { headerFontSize: 16, headerHeight: 60 },
        },
      }}>
        <App />
      </ConfigProvider>
    </QueryClientProvider>
  </React.StrictMode>,
)
