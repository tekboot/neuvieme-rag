import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface AskRequest {
  prompt: string;
}

export interface AskResponse {
  answer: string;
}

@Injectable({ providedIn: 'root' })
export class AiService {
  private baseUrl = 'http://localhost:8081/api/ai';

  constructor(private http: HttpClient) {}

  ask(prompt: string): Observable<AskResponse> {
    return this.http.post<AskResponse>(
      `${this.baseUrl}/ask`,
      { prompt }
    );
  }
}
