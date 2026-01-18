import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

export interface OllamaModelDetails {
  parent_model: string;
  format: string;
  family: string;
  families: string[];
  parameter_size: string;
  quantization_level: string;
}

export interface OllamaModel {
  name: string;
  model: string;
  modified_at: string;
  size: number;
  digest: string;
  details: OllamaModelDetails;
}

export interface PullResult {
  success: boolean;
  message?: string;
  error?: string;
}

// Static fallback model list when Ollama is not available
const STATIC_MODELS: OllamaModel[] = [
  {
    name: 'qwen2.5-coder',
    model: 'qwen2.5-coder',
    size: 5046586573,
    modified_at: '2025-01-16T12:00:00.000000000Z',
    digest: 'static',
    details: { parent_model: '', format: 'gguf', family: 'qwen2', families: ['qwen2'], parameter_size: '7B', quantization_level: 'Q4_0' }
  },
  {
    name: 'llama3.2',
    model: 'llama3.2',
    size: 2147483648,
    modified_at: '2025-01-16T12:00:00.000000000Z',
    digest: 'static',
    details: { parent_model: '', format: 'gguf', family: 'llama', families: ['llama'], parameter_size: '3B', quantization_level: 'Q4_K_M' }
  },
  {
    name: 'mistral',
    model: 'mistral',
    size: 4402341478,
    modified_at: '2025-01-16T12:00:00.000000000Z',
    digest: 'static',
    details: { parent_model: '', format: 'gguf', family: 'llama', families: ['llama'], parameter_size: '7B', quantization_level: 'Q4_0' }
  },
];

@Injectable({ providedIn: 'root' })
export class OllamaService {
  private base = 'http://localhost:8081/api/ollama';
  private useStaticFallback = false;

  constructor(private http: HttpClient) { }

  /**
   * Get list of installed Ollama models.
   * Falls back to static list if API fails.
   */
  getModels(): Observable<OllamaModel[]> {
    if (this.useStaticFallback) {
      return of(STATIC_MODELS);
    }

    return this.http.get<OllamaModel[]>(`${this.base}/models`, { withCredentials: true }).pipe(
      catchError((err) => {
        console.warn('[OllamaService] API failed, using static fallback list', err);
        this.useStaticFallback = true;
        return of(STATIC_MODELS);
      })
    );
  }

  /**
   * Pull/install a new model from Ollama registry.
   * Returns mock success if using static fallback.
   */
  pullModel(modelName: string): Observable<PullResult> {
    if (this.useStaticFallback) {
      // Mock pull success
      return of({
        success: true,
        message: `Mock: Model "${modelName}" would be installed (Ollama not available)`
      });
    }

    return this.http.post<PullResult>(
      `${this.base}/pull`,
      { model: modelName },
      { withCredentials: true }
    ).pipe(
      catchError((err) => {
        return of({
          success: false,
          error: err?.error?.message || 'Pull failed'
        });
      })
    );
  }

  /**
   * Reset to try API again (useful after Ollama starts)
   */
  resetFallback(): void {
    this.useStaticFallback = false;
  }
}
