import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { FileNode } from '../models';
import { Observable } from 'rxjs';

export interface GithubFileResponse {
  name?: string;
  path?: string;
  sha?: string;
  size?: number;
  type?: string;
  content?: string;
  encoding?: string;
}

export interface GithubRepoInfo {
  name: string;
  fullName: string;
  defaultBranch: string;
  isPrivate: boolean;
  description: string | null;
  updatedAt: string;
  stargazersCount: number;
  language: string | null;
}

export interface GithubStatusResponse {
  connected: boolean;
  reason?: string;
}

export interface GithubImportRequest {
  owner: string;
  repo: string;
  branch?: string;
  subPath?: string;
  indexMode?: 'PREINDEX' | 'LAZY';
  embedModel?: string;
}

export interface GithubBranch {
  name: string;
}

export interface GithubImportResponse {
  project: {
    id: string;
    name: string;
    displayName: string;
    source: string;
    githubOwner: string;
    githubRepo: string;
    githubBranch: string;
    fileCount: number;
  };
  tree: FileNode[];
  indexingStarted: boolean;
}

@Injectable({ providedIn: 'root' })
export class GithubImportService {
  private base = '/api/github';

  constructor(private http: HttpClient) { }

  /**
   * Check GitHub connection status.
   */
  checkStatus(): Observable<GithubStatusResponse> {
    return this.http.get<GithubStatusResponse>(`${this.base}/status`, { withCredentials: true });
  }

  /**
   * List repositories for a specific owner (or authenticated user if no owner).
   */
  listRepos(owner?: string): Observable<GithubRepoInfo[]> {
    let params = new HttpParams();
    if (owner) {
      params = params.set('owner', owner);
    }
    return this.http.get<GithubRepoInfo[]>(`${this.base}/repos`, {
      params,
      withCredentials: true
    });
  }

  /**
   * Import a GitHub repository.
   */
  importRepo(payload: GithubImportRequest): Observable<GithubImportResponse> {
    return this.http.post<GithubImportResponse>(`${this.base}/import`, payload, { withCredentials: true });
  }

  /**
   * Fetch file content from backend.
   */
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

  /**
   * List branches for a repository.
   */
  listBranches(owner: string, repo: string): Observable<GithubBranch[]> {
    const params = new HttpParams()
      .set('owner', owner)
      .set('repo', repo);
    return this.http.get<GithubBranch[]>(`${this.base}/branches`, {
      params,
      withCredentials: true
    });
  }
}
