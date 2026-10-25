import type { LocationQueryValue } from 'vue-router'

/**
 * 只接受站内相对路径作为登录后的回跳地址，防止 ?redirect=https://evil 之类的开放重定向。
 */
export function safeRedirect(value: LocationQueryValue | LocationQueryValue[] | undefined): string {
  const target = Array.isArray(value) ? value[0] : value
  if (typeof target === 'string' && target.startsWith('/') && !target.startsWith('//') && !target.includes('\\')) {
    return target
  }
  return '/'
}
