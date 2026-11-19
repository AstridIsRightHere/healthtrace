import { api } from './index';

export interface InvitationCreated {
  id: number;
  plainToken: string;
  expiresAt: string;
}

export interface InvitationListItem {
  id: number;
  createdAt: string;
  expiresAt: string;
  used: boolean;
  usedAt: string | null;
  usedBy: number | null;
}

export interface MetricDefinition {
  code: string;
  dictionaryId: number;
  nationalCode: string | null;
  standardName: string;
  standardUnit: string | null;
  reportType: string;
  sortOrder: number;
}

export interface MetricAlias {
  id: number;
  alias: string;
  requiredUnit: string | null;
}

export interface MetricDefinitionDetail extends MetricDefinition {
  aliases: MetricAlias[];
}

export const adminApi = {
  async createInvitation(validDays: number): Promise<InvitationCreated> {
    const { data } = await api.post('/admin/invitations', { validDays });
    return data;
  },

  async listInvitations(): Promise<InvitationListItem[]> {
    const { data } = await api.get('/admin/invitations');
    return data;
  },

  async disableInvitation(id: number): Promise<void> {
    await api.delete(`/admin/invitations/${id}`);
  },

  async listMetrics(): Promise<MetricDefinition[]> {
    const { data } = await api.get('/admin/metric-dictionary');
    return data;
  },

  async getMetricDetail(code: string): Promise<MetricDefinitionDetail> {
    const { data } = await api.get(`/admin/metric-dictionary/${code}`);
    return data;
  },
};
