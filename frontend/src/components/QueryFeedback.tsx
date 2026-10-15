import { Alert, Button } from 'antd'

export function QueryFeedback({ error, retry }: { error: Error | null; retry: () => unknown }) {
  return error ? <Alert type="error" showIcon message={error.message} style={{ marginBottom: 16 }} action={<Button onClick={() => retry()}>重试</Button>} /> : null
}

export function formatTime(value?: string | null) {
  return value ? value.replace('T', ' ').replace(/\.\d+$/, '') : '—'
}
