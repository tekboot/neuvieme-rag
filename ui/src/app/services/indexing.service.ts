import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface GithubContext {
  owner: string;
  repo: string;
  branch?: string;
  subPath?: string;
}

export interface ContextFile {
  source: 'device' | 'github';
  path: string;
  github?: GithubContext;
}

export interface IndexRequest {
  projectId: string;
  mode?: 'selected' | 'all';
  files?: ContextFile[];
  fileContents?: Record<string, string>;
  filePaths?: string[];
  source?: 'device' | 'github';
  embedModel?: string;
  chunkSize?: number;
  chunkOverlap?: number;
}

export interface IndexStatusResponse {
  projectId: string;
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'COMPLETED_WITH_ERRORS' | 'FAILED';
  totalFiles: number;
  indexedFiles: number;
  failedFiles: number;
  totalChunks: number;
  progress: number; // 0-100
  message: string;
  embedModel: string | null;
  chunkSize: number | null;
  chunkOverlap: number | null;
  errorMessage: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

@Injectable({ providedIn: 'root' })
export class IndexingService {
  private readonly baseUrl = `${environment.apiBaseUrl}/api/index`;

  constructor(private http: HttpClient) { }

  /**
   * Start indexing a project.
   */
  indexProject(request: IndexRequest): Observable<{ message: string; projectId: string; fileCount?: number }> {
    return this.http.post<{ message: string; projectId: string; fileCount?: number }>(
      `${this.baseUrl}/project`,
      request,
      { withCredentials: true }
    );
  }

  /**
   * Get indexing status for a project.
   */
  getStatus(projectId: string): Observable<IndexStatusResponse> {
    return this.http.get<IndexStatusResponse>(
      `${this.baseUrl}/status/${projectId}`,
      { withCredentials: true }
    );
  }

  /**
   * Delete index for a project.
   */
  deleteIndex(projectId: string): Observable<{ message: string; projectId: string }> {
    return this.http.delete<{ message: string; projectId: string }>(
      `${this.baseUrl}/project/${projectId}`,
      { withCredentials: true }
    );
  }

  /**
   * Poll for indexing status until complete or error.
   */
  pollStatus(projectId: string, intervalMs = 1000): Observable<IndexStatusResponse> {
    return new Observable(subscriber => {
      const poll = () => {
        this.getStatus(projectId).subscribe({
          next: status => {
            subscriber.next(status);
            // Continue polling while PENDING or IN_PROGRESS
            if (status.status === 'PENDING' || status.status === 'IN_PROGRESS') {
              setTimeout(poll, intervalMs);
            } else {
              subscriber.complete();
            }
          },
          error: err => subscriber.error(err)
        });
      };
      poll();
    });
  }
}
