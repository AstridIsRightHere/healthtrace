import { createPinia } from 'pinia'

// ADR-010：Pinia 只放最少共享状态，不持久化医疗数据或令牌
export const pinia = createPinia()
