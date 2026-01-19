import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { FileNode } from '../models';
import { Observable } from 'rxjs';

export type GithubFileResponse = {
  name?: string;
  path?: string;
  sha?: string;
  size?: number;
  type?: string;        // "file"
  content?: string;     // base64
  encoding?: string;    // "base64"
};

@Injectable({ providedIn: 'root' })
export class GithubImportService {
  private base = '/api/github';

  constructor(private http: HttpClient) { }

  importRepo(payload: { owner: string; repo: string; branch?: string; subPath?: string }): Observable<{ project: any, tree: FileNode[] }> {
    return this.http.post<{ project: any, tree: FileNode[] }>(`${this.base}/import`, payload, { withCredentials: true });
  }

  // ✅ NEW: fetch file content from backend
  getFile(params: { owner: string; repo: string; path: string; ref?: string }): Observable<GithubFileResponse> {
    let httpParams = new HttpParams()
      .set('owner', params.owner)
      .set('repo', params.repo)
      .set('path', params.path);

    if (params.ref) httpParams = httpParams.set('ref', params.ref);

    return this.http.get<GithubFileResponse>(`${this.base}/file`, {
      params: httpParams,
      withCredentials: true
    });
  }
}
