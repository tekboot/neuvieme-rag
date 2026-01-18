import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface RagChatRequest {
  message: string;
  projectIds: string[];
  model?: string;
  embedModel?: string;
  topK?: number;
  strategy?: 'use_existing' | 'reindex';
  mode?: 'all' | 'selected';
  files?: any[];
  chunkSize?: number;
  chunkOverlap?: number;
}

export interface RagChatResponse {
  answer: string;
  rag?: {
    strategyUsed: string;
    usedExisting: boolean;
    indexedNow: boolean;
    chunksUsed: number;
    chunksCreated: number;
    filesIndexed: number;
    messageLog: string[];
  };
}

@Injectable({ providedIn: 'root' })
export class RagChatService {
  private readonly baseUrl = 'http://localhost:8081/api/ai';

  constructor(private http: HttpClient) { }

  /**
   * Send a RAG-augmented chat message.
   * Uses vector search to find relevant code chunks as context.
   */
  chat(request: RagChatRequest): Observable<RagChatResponse> {
    return this.http.post<RagChatResponse>(
      `${this.baseUrl}/chat-rag`,
      request,
      { withCredentials: true }
    );
  }
}
