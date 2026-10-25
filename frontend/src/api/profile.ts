import axios from 'axios'
import { api, type ApiErrorBody } from './index'

/** 与患者的关系：只影响页面文案，不代表任何权限（FR-003） */
export type Relationship = 'SELF' | 'CHILD' | 'SPOUSE' | 'OTHER'
export type ProfileStatus = 'ACTIVE' | 'AUTH_SUSPENDED' | 'DELETING'

/** 对应后端 ProfileView；时间为 UTC */
export interface ProfileView {
  id: number
  nickname: string | null
  relationship: Relationship
  status: ProfileStatus
  version: number
  authorizationDeclaredAt: string
  declarationVersion: string
}

export interface CreateProfilePayload {
  relationship: Relationship
  nickname?: string
  authorizationDeclared: boolean
}

const PROFILE_NOT_FOUND = 'HT-3001'

export const profileApi = {
  /** POST /api/v1/patient-profiles：建档（US-003），声明时间与版本由服务端记录 */
  create: (payload: CreateProfilePayload) =>
    api.post<ProfileView>('/patient-profiles', payload).then((res) => res.data),

  /** GET /api/v1/patient-profiles/current：当前账号的有效档案；尚未建档返回 null */
  current: async (): Promise<ProfileView | null> => {
    try {
      const res = await api.get<ProfileView>('/patient-profiles/current')
      return res.data
    } catch (error) {
      if (
        axios.isAxiosError<ApiErrorBody>(error) &&
        error.response?.status === 404 &&
        error.response.data?.code === PROFILE_NOT_FOUND
      ) {
        return null
      }
      throw error
    }
  },
}
