import { request } from '../request';

export function fetchClearChatMemory(sessionId?: string) {
  return request<{
    deletedConversations: number;
    deletedRedisKeys: number;
  }>({
    url: '/users/conversation',
    method: 'delete',
    params: sessionId ? { sessionId } : undefined
  });
}
