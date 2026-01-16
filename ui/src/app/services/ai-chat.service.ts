import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export type ContextMode = 'all' | 'selected';

export interface GithubFileRef {
  owner: string;
  repo: string;
  branch?: string;
  subPath?: string;
}

export interface ContextFile {
  source: 'device' | 'github';
  path: string;
  github?: GithubFileRef;
}

export interface ChatContext {
  mode: ContextMode;
  files?: ContextFile[];
}

export interface ChatPayload {
  message: string;
  context: ChatContext;
}

@Injectable({ providedIn: 'root' })
export class AiChatService {
  private base = 'http://localhost:8081/api/ai'; // change in env

  constructor(private http: HttpClient) {}

  chat(payload: ChatPayload): Observable<{ reply: string }> {
    // Debug logging
    console.log('[AiChatService] Sending chat payload:', JSON.stringify(payload, null, 2));
    return this.http.post<{ reply: string }>(`${this.base}/chat`, payload, { withCredentials: true });
  }
}
