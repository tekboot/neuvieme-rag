import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { environment } from '../../environments/environment';

export interface AuthState {
  authenticated: boolean;
  githubAuthenticated: boolean;
  name?: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private base = environment.apiBaseUrl;

  // Reactive auth state
  authState = signal<AuthState>({
    authenticated: false,
    githubAuthenticated: false
  });

  constructor(private http: HttpClient) {}

  // Backend endpoint that tells if user is authenticated
  me(): Observable<AuthState> {
    const url = `${this.base}/auth/me`;
    console.log('[AuthService] Calling me() endpoint:', url);
    return this.http.get<AuthState>(url, {
      withCredentials: true
    }).pipe(
      tap(state => {
        console.log('[AuthService] Auth state received:', state);
        this.authState.set(state);
      })
    );
  }

  // Full page redirect (NOT XHR)
  connectGithub() {
    // Store current URL for return after OAuth
    localStorage.setItem('deepcode_return_url', window.location.href);
    const oauthUrl = `${this.base}/oauth2/authorization/github`;
    console.log('[AuthService] Redirecting to GitHub OAuth:', oauthUrl);
    window.location.href = oauthUrl;
  }

  // Check if GitHub auth is needed and redirect
  handleGithubAuthRequired(): void {
    console.warn('[AuthService] GitHub authentication required - redirecting to OAuth');
    this.connectGithub();
  }

  // Return to stored URL after OAuth (call on app init)
  checkReturnUrl(): void {
    const returnUrl = localStorage.getItem('deepcode_return_url');
    if (returnUrl) {
      localStorage.removeItem('deepcode_return_url');
      // Already at the return URL after OAuth redirect, just clear it
    }
  }
}
