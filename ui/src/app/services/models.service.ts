import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface ModelInfo {
  name: string;
  size: string;
  modifiedAt: string;
  status: string;
  active: boolean;
}

export interface PullResult {
  success: boolean;
  message?: string;
  error?: string;
}

@Injectable({ providedIn: 'root' })
export class ModelsService {
  private base = 'http://localhost:8081/api/admin';

  constructor(private http: HttpClient) {}

  /** List all installed models */
  listModels(): Observable<ModelInfo[]> {
    return this.http.get<ModelInfo[]>(`${this.base}/models`, { withCredentials: true });
  }

  /** Get only active models (for chat dropdown) */
  getActiveModels(): Observable<ModelInfo[]> {
    return this.http.get<ModelInfo[]>(`${this.base}/models/active`, { withCredentials: true });
  }

  /** Pull/install a new model */
  pullModel(name: string): Observable<PullResult> {
    return this.http.post<PullResult>(`${this.base}/models/pull`, { name }, { withCredentials: true });
  }

  /** Activate a model for use in chat */
  activateModel(name: string): Observable<{ success: boolean; message?: string }> {
    return this.http.post<{ success: boolean; message?: string }>(
      `${this.base}/models/activate`,
      { name },
      { withCredentials: true }
    );
  }

  /** Deactivate a model */
  deactivateModel(name: string): Observable<{ success: boolean; message?: string }> {
    return this.http.post<{ success: boolean; message?: string }>(
      `${this.base}/models/deactivate`,
      { name },
      { withCredentials: true }
    );
  }
}
